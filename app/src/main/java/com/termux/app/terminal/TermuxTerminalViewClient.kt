package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.models.UserAction
import com.termux.app.settings.BackKeyBehaviour
import com.termux.app.settings.SoftKeyboardToggleBehaviour
import com.termux.app.settings.TermuxSettings
import com.termux.app.settings.VolumeKeysBehaviour
import com.termux.app.terminal.io.KeyboardShortcut
import com.termux.app.terminal.io.ModifierKeys
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.shell.ShellUtils
import com.termux.shared.termux.TermuxBootstrap
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.data.TermuxUrlUtils
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP
import com.termux.shared.termux.terminal.TermuxTerminalClientBase
import com.termux.shared.view.KeyboardUtils
import com.termux.shared.view.ViewUtils
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession

class TermuxTerminalViewClient(
    private val activity: TermuxActivity?,
    private val mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient,
) : TermuxTerminalClientBase() {

    private val mActivity: TermuxActivity
        get() = activity!!

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys. */
    private var mVirtualControlKeyDown = false
    private var mVirtualFnKeyDown = false

    private var mShowSoftKeyboardRunnable: Runnable? = null

    private var mShowSoftKeyboardIgnoreOnce = false
    private var mShowSoftKeyboardWithDelayOnce = false

    private var mTerminalCursorBlinkerStateAlreadySet = false

    private val mSessionShortcuts = mutableListOf<KeyboardShortcut>()

    fun getActivity(): TermuxActivity? = activity

    /**
     * Should be called when mActivity.onCreate() is called
     */
    fun onCreate() {
        onReloadProperties()

        mActivity.getTerminalView().setFontSize(mActivity.getPreferences().getFontSize())
        mActivity.getTerminalView().setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn())
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    fun onStart() {
        // Apply the terminal key-logging preference. This is also required if the user changed it
        // from the settings activity and returns.
        val isTerminalViewKeyLoggingEnabled = mActivity.getPreferences().isTerminalViewKeyLoggingEnabled()

        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled)
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    fun onResume() {
        // Show the soft keyboard if required
        setSoftKeyboardState(true, mActivity.isActivityRecreated())

        onCurrentSessionDisplayed()
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    fun onStop() {
        // Stop terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(false)
    }

    /**
     * Should be called when mActivity.reloadProperties() is called
     */
    fun onReloadProperties() {
        setSessionShortcuts()
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    fun onReloadActivityStyling() {
        // Show the soft keyboard if required
        setSoftKeyboardState(false, true)

        // Start terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(true)
    }

    /**
     * Should be called when a different session becomes the displayed one.
     *
     * <p>Switching sessions stops the previous blinker, so blinking has to be re-armed
     * for the newly displayed session instead of waiting for the next resume.
     */
    fun onCurrentSessionDisplayed() {
        mTerminalCursorBlinkerStateAlreadySet = false
        startTerminalCursorBlinkerIfReady()
    }

    /**
     * Should be called when the attached terminal becomes ready for use.
     */
    override fun onTerminalReady(readySession: TerminalSession) {
        if (mTerminalCursorBlinkerStateAlreadySet) return
        if (mActivity.getCurrentSession() !== readySession) return
        startTerminalCursorBlinkerIfReady()
    }

    /**
     * Starts cursor blinking once the displayed session has a live backend.
     *
     * <p>Backend initialization is lazy and happens on the first canvas size pass,
     * which is after onResume(). When the backend is not up yet the caller is
     * onTerminalReady(), which arrives once the size pass initialized it.
     */
    private fun startTerminalCursorBlinkerIfReady() {
        if (mTerminalCursorBlinkerStateAlreadySet) return
        if (!mActivity.isVisible()) return

        val currentSession = mActivity.getCurrentSession()
        if (currentSession == null || !currentSession.hasActiveTerminalBackend()) return

        setTerminalCursorBlinkerState(true)
        mTerminalCursorBlinkerStateAlreadySet = true
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase = scale > 1f
            changeFontSize(increase)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val session = mActivity.getCurrentSession()
        if (session == null || !session.hasActiveTerminalBackend()) return true

        val url = getTerminalTranscriptUrlOnTap(e)
        if (url != null) {
            ShareUtils.openUrl(mActivity, url)
            return true
        }

        if (session.isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)) return false

        val isUnconditionalKeyboard = mActivity.getSettings().unconditionalSoftKeyboardOnTap
        if ((isUnconditionalKeyboard || !session.isMouseTrackingActive()) && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity))
                showSoftKeyboardAndRemember()
            else
                Logger.logVerbose(LOG_TAG, "Not showing soft keyboard onSingleTapUp since its disabled")
            return true
        }
        return false
    }

    override fun onSoftKeyboardDismissed() {
        if (!mActivity.hasWindowFocus()) return
        hideSoftKeyboardAndRemember()
    }

    override fun onSoftKeyboardVisibilityChanged(isVisible: Boolean) {
        if (!mActivity.hasWindowFocus()) return
        rememberSoftKeyboardState(
            if (isVisible) TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE else TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_HIDDEN
        )
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean =
        mActivity.getSettings().backKey == BackKeyBehaviour.ESCAPE

    override fun shouldEnforceCharBasedInput(): Boolean = mActivity.getSettings().enforceCharBasedInput

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = mActivity.getSettings().ctrlSpaceWorkaround

    override fun shouldOpenTerminalTranscriptURLOnClick(): Boolean = mActivity.getSettings().terminalOnclickUrlOpen

    override fun getTerminalTranscriptUrlOnTap(e: MotionEvent): String? {
        return getTerminalTranscriptUrlOnTap(
            e,
            mActivity.getCurrentSession(),
            mActivity.getTerminalView(),
            mActivity.getSettings().terminalOnclickUrlOpen,
            mActivity.getSettings().terminalOnclickUrlOpenWhenMouseTrackingActive
        )
    }

    override fun isTerminalViewSelected(): Boolean = mActivity.getTerminalView().hasFocus()

    @SuppressLint("RtlHardcoded")
    override fun onKeyDown(keyCode: Int, e: KeyEvent, currentSession: TerminalSession): Boolean {
        if (handleVirtualKeys(keyCode, e, true)) return true

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession)
            return true
        } else if (!mActivity.getSettings().disableHardwareKeyboardShortcuts &&
            e.isCtrlPressed && e.isAltPressed) {
            // Get the unmodified code point:
            val unicodeChar = e.getUnicodeChar(0)

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'.code/* next */) {
                mTermuxTerminalSessionActivityClient.switchToSession(true)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p'.code /* previous */) {
                mTermuxTerminalSessionActivityClient.switchToSession(false)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mActivity.showTabsGrid()
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mActivity.getScreenState().tabsGridVisible = false
            } else if (unicodeChar == 'k'.code/* keyboard */) {
                onToggleSoftKeyboardRequest()
            } else if (unicodeChar == 'm'.code/* menu */) {
                mActivity.getTerminalView().showContextMenu()
            } else if (unicodeChar == 'r'.code/* rename */) {
                mTermuxTerminalSessionActivityClient.renameSession(currentSession)
            } else if (unicodeChar == 'c'.code/* create */) {
                mTermuxTerminalSessionActivityClient.addNewSession(false, null)
            } else if (unicodeChar == 'u'.code /* urls */) {
                showUrlSelection()
            } else if (unicodeChar == 'v'.code) {
                doPaste()
            } else if (unicodeChar == '+'.code || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.code) {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                changeFontSize(true)
            } else if (unicodeChar == '-'.code) {
                changeFontSize(false)
            } else if (unicodeChar >= '1'.code && unicodeChar <= '9'.code) {
                val index = unicodeChar - '1'.code
                mTermuxTerminalSessionActivityClient.switchToSession(index)
            }
            return true
        }

        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        // If emulator is not set, like if bootstrap installation failed and user dismissed the error
        // dialog, then just exit the activity, otherwise they will be stuck in a broken state.
        val currentSession = mActivity.getCurrentSession()
        if (keyCode == KeyEvent.KEYCODE_BACK && (currentSession == null || !currentSession.hasActiveTerminalBackend())) {
            mActivity.finishActivityIfNotFinishing()
            return true
        }

        return handleVirtualKeys(keyCode, e, false)
    }

    /** Handle dedicated volume buttons as virtual keys if applicable. */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val inputDevice: InputDevice? = event.device
        if (mActivity.getSettings().volumeKeys == VolumeKeysBehaviour.VOLUME) {
            return false
        } else if (inputDevice != null && inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down
            return true
        }
        return false
    }

    override fun readControlKey(): Boolean = readModifier { it.readCtrl() } || mVirtualControlKeyDown

    override fun readAltKey(): Boolean = readModifier { it.readAlt() }

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    private inline fun readModifier(read: (ModifierKeys) -> Boolean): Boolean {
        val modifiers = mActivity.getModifierKeys()
        val pressed = read(modifiers)
        modifiers.clearConsumed()
        return pressed
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (!session.isRunning() && (codePoint == '\r'.code || codePoint == '\n'.code || codePoint == 13 || codePoint == 10 || (ctrlDown && codePoint == 106))) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(session)
            return true
        }

        if (mVirtualFnKeyDown) {
            var resultingKeyCode = -1
            var resultingCodePoint = -1
            var altDown = false
            val lowerCase = Character.toLowerCase(codePoint)
            when (lowerCase) {
                // Arrow keys.
                'w'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP
                'a'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT
                's'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
                'd'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT

                // Page up and down.
                'p'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP
                'n'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN

                // Some special keys:
                't'.code -> resultingKeyCode = KeyEvent.KEYCODE_TAB
                'i'.code -> resultingKeyCode = KeyEvent.KEYCODE_INSERT
                'h'.code -> resultingCodePoint = '~'.code

                // Special characters to input.
                'u'.code -> resultingCodePoint = '_'.code
                'l'.code -> resultingCodePoint = '|'.code

                // Function keys.
                '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code, '8'.code, '9'.code ->
                    resultingKeyCode = (codePoint - '1'.code) + KeyEvent.KEYCODE_F1
                '0'.code -> resultingKeyCode = KeyEvent.KEYCODE_F10

                // Other special keys.
                'e'.code -> resultingCodePoint = /*Escape*/ 27
                '.'.code -> resultingCodePoint = /*^.*/ 28

                'b'.code, // alt+b, jumping backward in readline.
                'f'.code, // alf+f, jumping forward in readline.
                'x'.code -> { // alt+x, common in emacs.
                    resultingCodePoint = lowerCase
                    altDown = true
                }

                // Volume control.
                'v'.code -> {
                    resultingCodePoint = -1
                    val audio = mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }

            }

            if (resultingKeyCode != -1) {
                session.write(KeyHandler.getCode(resultingKeyCode, 0, session.isCursorKeysApplicationMode(), session.isKeypadApplicationMode()))
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint)
            }
            return true
        } else if (ctrlDown) {
            if (mSessionShortcuts.isNotEmpty()) {
                val codePointLowerCase = Character.toLowerCase(codePoint)
                for (i in mSessionShortcuts.size - 1 downTo 0) {
                    val shortcut = mSessionShortcuts[i]
                    if (codePointLowerCase == shortcut.codePoint) {
                        when (shortcut.shortcutAction) {
                            SHORTCUT_CREATE_SESSION -> {
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null)
                                return true
                            }
                            SHORTCUT_NEXT_SESSION -> {
                                mTermuxTerminalSessionActivityClient.switchToSession(true)
                                return true
                            }
                            SHORTCUT_PREVIOUS_SESSION -> {
                                mTermuxTerminalSessionActivityClient.switchToSession(false)
                                return true
                            }
                            SHORTCUT_RENAME_SESSION -> {
                                mTermuxTerminalSessionActivityClient.renameSession(mActivity.getCurrentSession())
                                return true
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Set the terminal sessions shortcuts.
     */
    private fun setSessionShortcuts() {
        val s = mActivity.getSettings()
        mSessionShortcuts.clear()
        addShortcut(s.shortcutCreateSession, SHORTCUT_CREATE_SESSION)
        addShortcut(s.shortcutNextSession, SHORTCUT_NEXT_SESSION)
        addShortcut(s.shortcutPreviousSession, SHORTCUT_PREVIOUS_SESSION)
        addShortcut(s.shortcutRenameSession, SHORTCUT_RENAME_SESSION)
    }

    private fun addShortcut(shortcut: String?, action: Int) {
        val codePoint = TermuxSettings.shortcutCodePoint(shortcut)
        if (codePoint != null)
            mSessionShortcuts.add(KeyboardShortcut(codePoint, action))
    }

    fun changeFontSize(increase: Boolean) {
        mActivity.getPreferences().changeFontSize(increase)
        mActivity.getTerminalView().setFontSize(mActivity.getPreferences().getFontSize())
    }

    /**
     * Called when user requests the soft keyboard to be toggled with the ctrl+alt+k hardware
     * keyboard shortcut.
     */
    fun onToggleSoftKeyboardRequest() {
        // If soft keyboard toggle behaviour is enable/disabled
        if (mActivity.getSettings().softKeyboardToggleBehaviour == SoftKeyboardToggleBehaviour.ENABLE_DISABLE) {
            // If soft keyboard is visible
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle")
                mActivity.getPreferences().setSoftKeyboardEnabled(false)
                hideSoftKeyboardAndRemember()
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
                return
            }

            // Show with a delay, otherwise pressing keyboard toggle won't show the keyboard after
            // switching back from another app if keyboard was previously disabled by user.
            // Also request focus, since it wouldn't have been requested at startup by
            // setSoftKeyboardState if keyboard was disabled. #2112
            Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle")
            mActivity.getPreferences().setSoftKeyboardEnabled(true)
            mActivity.getTerminalView().requestFocus()
            if (mShowSoftKeyboardWithDelayOnce) {
                mShowSoftKeyboardWithDelayOnce = false
                showSoftKeyboardAndRemember(true, 500)
                return
            }

            showSoftKeyboardAndRemember()
            return
        }

        // If soft keyboard toggle behaviour is show/hide
        if (!mActivity.getPreferences().isSoftKeyboardEnabled()) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle")
            disableSoftKeyboardByPolicy()
            return
        }

        Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (KeyboardUtils.isSoftKeyboardVisible(mActivity)) {
                hideSoftKeyboardAndRemember()
                return
            }

            mActivity.getTerminalView().requestFocus()
            showSoftKeyboardAndRemember()
            return
        }

        KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
        KeyboardUtils.toggleSoftKeyboard(mActivity)
    }

    fun setSoftKeyboardState(isStartup: Boolean, isReloadTermuxProperties: Boolean) {
        var noShowKeyboard = false

        // Requesting terminal view focus is necessary regardless of if soft keyboard is to be
        // disabled or hidden at startup, otherwise if hardware keyboard is attached and user
        // starts typing on hardware keyboard without tapping on the terminal first, then a colour
        // tint will be added to the terminal as highlight for the focussed view. Test with a light
        // theme. For android 8.+, the "defaultFocusHighlightEnabled" attribute is also set to false
        // in TerminalView layout to fix the issue.

        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity,
                mActivity.getPreferences().isSoftKeyboardEnabled(),
                mActivity.getPreferences().isSoftKeyboardEnabledOnlyIfNoHardware())) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard")
            disableSoftKeyboardByPolicy()
            mActivity.getTerminalView().requestFocus()
            noShowKeyboard = true
            // Delay is only required if onCreate() is called like when Termux app is exited with
            // double back press, not when Termux app is switched back from another app and keyboard
            // toggle is pressed to enable keyboard
            if (isStartup && mActivity.isOnResumeAfterOnCreate())
                mShowSoftKeyboardWithDelayOnce = true
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity)

            // Clear any previous flags to disable soft keyboard in case setting updated
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)

            if (restoreRememberedSoftKeyboardState()) {
                noShowKeyboard = true
            }
            // If soft keyboard is to be hidden on startup
            else if (isStartup && mActivity.getSettings().hideSoftKeyboardOnStartup) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup")
                // Required to keep keyboard hidden when Termux app is switched back from another app
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity)

                hideSoftKeyboardAndRemember()
                mActivity.getTerminalView().requestFocus()
                noShowKeyboard = true
                // Required to keep keyboard hidden on app startup
                mShowSoftKeyboardIgnoreOnce = true
            }
        }

        mActivity.getTerminalView().setOnFocusChangeListener { _, hasFocus ->
            // Force show soft keyboard if TerminalView has focus and close it if it doesn't
            if (hasFocus) {
                if (mShowSoftKeyboardIgnoreOnce) {
                    mShowSoftKeyboardIgnoreOnce = false
                    return@setOnFocusChangeListener
                }
                Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change")
                rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE)
            } else {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change")
                if (mActivity.hasWindowFocus())
                    rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_HIDDEN)
            }

            KeyboardUtils.setSoftKeyboardVisibility(getShowSoftKeyboardRunnable(), mActivity, mActivity.getTerminalView(), hasFocus)
        }

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled
        if (!isReloadTermuxProperties && !noShowKeyboard) {
            // Request focus for TerminalView
            // Also show the keyboard, since onFocusChange will not be called if TerminalView already
            // had focus on startup to show the keyboard, like when opening url with context menu
            // "Select URL" long press and returning to Termux app with back button. This
            // will also show keyboard even if it was closed before opening url. #2111
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard")
            mActivity.getTerminalView().requestFocus()
            showSoftKeyboardAndRemember(true)
        }
    }

    private fun showSoftKeyboardAndRemember() {
        showSoftKeyboardAndRemember(false)
    }

    private fun showSoftKeyboardAndRemember(withDelay: Boolean) {
        showSoftKeyboardAndRemember(withDelay, 300)
    }

    private fun showSoftKeyboardAndRemember(withDelay: Boolean, delayInMillis: Long) {
        KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
        if (!withDelay) {
            getShowSoftKeyboardRunnable().run()
            return
        }

        mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), delayInMillis)
    }

    private fun hideSoftKeyboardAndRemember() {
        mActivity.getTerminalView().removeCallbacks(getShowSoftKeyboardRunnable())
        KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView())
        rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_HIDDEN)
    }

    private fun disableSoftKeyboardByPolicy() {
        mActivity.getTerminalView().removeCallbacks(getShowSoftKeyboardRunnable())
        KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
    }

    private fun restoreRememberedSoftKeyboardState(): Boolean {
        if (!mActivity.getSettings().rememberSoftKeyboardState) return false

        val lastSoftKeyboardState = mActivity.getPreferences().getLastSoftKeyboardState()
        if (TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_UNKNOWN == lastSoftKeyboardState) return false

        if (TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE == lastSoftKeyboardState) {
            Logger.logVerbose(LOG_TAG, "Restoring remembered visible soft keyboard state")
            mActivity.getTerminalView().requestFocus()
            showSoftKeyboardAndRemember(true)
            return true
        }

        Logger.logVerbose(LOG_TAG, "Restoring remembered hidden soft keyboard state")
        KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity)
        hideSoftKeyboardAndRemember()
        mActivity.getTerminalView().requestFocus()
        mShowSoftKeyboardIgnoreOnce = true
        return true
    }

    private fun rememberSoftKeyboardState(state: String) {
        if (!mActivity.getSettings().rememberSoftKeyboardState) return
        mActivity.getPreferences().setLastSoftKeyboardState(state)
    }

    private fun getShowSoftKeyboardRunnable(): Runnable {
        val runnable = mShowSoftKeyboardRunnable ?: Runnable {
            if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Not showing soft keyboard since it is disabled")
                return@Runnable
            }

            mActivity.getTerminalView().showSoftKeyboard()
            rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE)
        }
        mShowSoftKeyboardRunnable = runnable
        return runnable
    }

    fun setTerminalCursorBlinkerState(start: Boolean) {
        if (start) {
            mActivity.setTerminalCursorBlinkerState(true, true)
        } else {
            // Disable cursor blinker
            mActivity.setTerminalCursorBlinkerState(false, true)
        }
    }

    fun shareSessionTranscript() {
        val session = mActivity.getCurrentSession() ?: return

        var transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = DataUtils.getTruncatedCommandOutput(transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false).trim()
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_transcript),
            transcriptText, mActivity.getString(R.string.title_share_transcript_with))
    }

    fun shareSelectedText() {
        val selectedText = mActivity.getTerminalView().getStoredSelectedText()
        if (DataUtils.isNullOrEmpty(selectedText)) return
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_selected_text),
            selectedText, mActivity.getString(R.string.title_share_selected_text_with))
    }

    fun showUrlSelection() {
        val session = mActivity.getCurrentSession() ?: return

        val text = ShellUtils.getTerminalSessionTranscriptText(session, true, true)

        val urlSet = TermuxUrlUtils.extractUrls(text)
        if (urlSet.isEmpty()) {
            AlertDialog.Builder(mActivity).setMessage(R.string.title_select_url_none_found).show()
            return
        }

        val urls = urlSet.toTypedArray()
        urls.reverse() // Latest first.

        // Click to copy url to clipboard:
        val dialog = AlertDialog.Builder(mActivity).setItems(urls) { _, which ->
            val url = urls[which] as String
            ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard))
        }.setTitle(R.string.title_select_url_dialog).create()

        // Long press to open URL:
        dialog.setOnShowListener { _ ->
            val lv = dialog.listView // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                val url = urls[position] as String
                ShareUtils.openUrl(mActivity, url)
                true
            }
        }

        dialog.show()
    }

    fun reportIssueFromTranscript() {
        val session = mActivity.getCurrentSession() ?: return

        val transcriptText = ShellUtils.getTerminalSessionTranscriptText(session, false, true) ?: return

        MessageDialogUtils.showMessage(mActivity, TermuxConstants.TERMUX_APP_NAME + " Report Issue",
            mActivity.getString(R.string.msg_add_termux_debug_info),
            mActivity.getString(com.termux.shared.R.string.action_yes), { _, _ -> reportIssueFromTranscript(transcriptText, true) },
            mActivity.getString(com.termux.shared.R.string.action_no), { _, _ -> reportIssueFromTranscript(transcriptText, false) },
            null)
    }

    private fun reportIssueFromTranscript(transcriptText: String, addTermuxDebugInfo: Boolean) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true)

        Thread {
            val reportString = StringBuilder()

            val title = TermuxConstants.TERMUX_APP_NAME + " Report Issue"

            reportString.append("## Transcript\n")
            reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true))
            reportString.append("\n##\n")

            if (addTermuxDebugInfo) {
                reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES))
            } else {
                reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, TermuxUtils.AppInfoMode.TERMUX_PACKAGE))
            }

            reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(mActivity, true))

            if (TermuxBootstrap.isAppPackageManagerAPT()) {
                val termuxAptInfo = TermuxUtils.geAPTInfoMarkdownString(mActivity)
                if (termuxAptInfo != null)
                    reportString.append("\n\n").append(termuxAptInfo)
            }

            if (addTermuxDebugInfo) {
                val termuxDebugInfo = TermuxUtils.getTermuxDebugMarkdownString(mActivity)
                if (termuxDebugInfo != null)
                    reportString.append("\n\n").append(termuxDebugInfo)
            }

            val userActionName = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName()

            val reportInfo = ReportInfo(userActionName,
                TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title)
            reportInfo.setReportString(reportString.toString())
            reportInfo.setReportStringSuffix("\n\n" + TermuxUtils.getReportIssueMarkdownString(mActivity))
            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                "${Environment.getExternalStorageDirectory()}/" +
                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true))

            ReportActivity.startReportActivity(mActivity, reportInfo)
        }.start()
    }

    fun doPaste() {
        val session = mActivity.getCurrentSession() ?: return
        if (!session.isRunning()) return

        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null)
            session.paste(text)
    }

    companion object {
        private const val LOG_TAG = "TermuxTerminalViewClient"

        private const val SHORTCUT_CREATE_SESSION = 1
        private const val SHORTCUT_NEXT_SESSION = 2
        private const val SHORTCUT_PREVIOUS_SESSION = 3
        private const val SHORTCUT_RENAME_SESSION = 4
    }
}
