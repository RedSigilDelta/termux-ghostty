package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.settings.BellBehaviour
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.terminal.TermuxTerminalStyling
import com.termux.shared.termux.terminal.io.BellHandler
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.session.TerminalSessionBackend

/** The [com.termux.terminal.TerminalSessionClient] implementation that may require an Activity for its interface methods. */
open class TermuxTerminalSessionActivityClient(private val activity: TermuxActivity?) : TermuxTerminalSessionClientBase() {

    private val mActivity: TermuxActivity
        get() = activity!!

    private var mBellSoundPool: SoundPool? = null

    private var mBellSoundId = 0

    private val mSessionBackends: MutableMap<String, TerminalSessionBackend> = HashMap()

    /**
     * Should be called when mActivity.onCreate() is called
     */
    fun onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    fun onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by onStop if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast())
            termuxSessionListNotifyUpdated()
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView().onScreenUpdated()
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    fun onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool()
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    fun onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // onStart if needed.
        setCurrentStoredSession()

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool()
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    fun onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    override fun onTerminalReady(readySession: TerminalSession) {
        // The view client is created after this client, so resolve it lazily.
        val viewClient: TermuxTerminalViewClient? = mActivity.getTermuxTerminalViewClient()
        if (viewClient != null) viewClient.onTerminalReady(readySession)
    }

    override fun onFrameAvailable(changedSession: TerminalSession) {
        if (!mActivity.isVisible()) return

        if (mActivity.getCurrentSession() === changedSession) mActivity.getTerminalView().onFrameAvailable()
    }

    override fun onTitleChanged(updatedSession: TerminalSession) {
        if (!mActivity.isVisible()) return

        termuxSessionListNotifyUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val service = mActivity.getTermuxService()

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index = service.getIndexOfSession(finishedSession)

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        var isPluginExecutionCommandWithPendingResult = false
        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.executionCommand.isPluginExecutionCommandWithPendingResult()
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"${finishedSession.mSessionName}\" session will be force finished automatically since result in pending.")
        }

        if (mActivity.isVisible() && finishedSession !== mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true)
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession.exitStatus == 0 || finishedSession.exitStatus == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!mActivity.isVisible()) return

        ShareUtils.copyTextToClipboard(mActivity, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (!mActivity.isVisible()) return

        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        val currentSession = mActivity.getCurrentSession()
        if (text != null && currentSession != null)
            currentSession.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        if (!mActivity.isVisible()) return

        when (mActivity.getSettings().bellBehaviour) {
            BellBehaviour.VIBRATE -> BellHandler.getInstance(mActivity).doBell()
            BellBehaviour.BEEP -> {
                loadBellSoundPool()
                mBellSoundPool?.play(mBellSoundId, 1f, 1f, 1, 0, 1f)
            }
            BellBehaviour.IGNORE -> {
                // Ignore the bell character.
            }
        }
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        if (mActivity.getCurrentSession() === changedSession)
            updateBackgroundColor()
    }

    override fun onTerminalCursorStateChange(enabled: Boolean) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible")
            return
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.setTerminalCursorBlinkerState(enabled, false)
    }

    override fun setTerminalShellPid(terminalSession: TerminalSession, pid: Int) {
        val service = mActivity.getTermuxService() ?: return

        val termuxSession = service.getTermuxSessionForTerminalSession(terminalSession)
        if (termuxSession != null)
            termuxSession.executionCommand.mPid = pid
    }

    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    fun onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.setTerminalCursorBlinkerState(true, true)
    }

    override fun getTerminalCursorStyle(): Int = mActivity.getSettings().terminalCursorStyle.terminalValue

    fun finishActivityIfNotFinishing() {
        mActivity.finishActivityIfNotFinishing()
    }

    fun isSessionFocused(session: TerminalSession?): Boolean {
        if (session == null) return false
        if (!mActivity.isVisible()) return false
        if (!mActivity.hasWindowFocus()) return false
        return session === mActivity.getCurrentSession()
    }

    /** Load mBellSoundPool */
    @Synchronized
    private fun loadBellSoundPool() {
        if (mBellSoundPool == null) {
            val soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()
            mBellSoundPool = soundPool

            try {
                mBellSoundId = soundPool.load(mActivity, com.termux.shared.R.raw.bell, 1)
            } catch (e: Exception) {
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e)
            }
        }
    }

    /** Release mBellSoundPool resources */
    @Synchronized
    private fun releaseBellSoundPool() {
        mBellSoundPool?.release()
        mBellSoundPool = null
    }

    /** Try switching to session. */
    fun setCurrentSession(session: TerminalSession?) {
        if (session == null) return

        if (mActivity.getCurrentSession() !== session) {
            var backend = mSessionBackends[session.mHandle]
            if (backend == null) {
                backend = TerminalSessionBackend(session)
                mSessionBackends[session.mHandle] = backend
            }
            mActivity.getTerminalView().setBackend(backend)
            mActivity.setCurrentSession(session)
            // notify about switched session if not already displaying the session
            notifyOfSessionChange()
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session)
        updateBackgroundColor()

        // Update tab strip selection (O(1), not full rebuild)
        mActivity.onCurrentSessionChanged()
    }

    private fun notifyOfSessionChange() {
        if (!mActivity.isVisible()) return

        if (!mActivity.getSettings().disableTerminalSessionChangeToast) {
            val session = mActivity.getCurrentSession()
            mActivity.showToast(toToastTitle(session), false)
        }
    }

    fun switchToSession(forward: Boolean) {
        val service = mActivity.getTermuxService() ?: return

        val currentTerminalSession = mActivity.getCurrentSession()
        var index = service.getIndexOfSession(currentTerminalSession)
        val size = service.getTermuxSessionsSize()
        if (forward) {
            if (++index >= size) index = 0
        } else {
            if (--index < 0) index = size - 1
        }

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null)
            setCurrentSession(termuxSession.terminalSession)
    }

    fun switchToSession(index: Int) {
        val service = mActivity.getTermuxService() ?: return

        val termuxSession = service.getTermuxSession(index)
        if (termuxSession != null)
            setCurrentSession(termuxSession.terminalSession)
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: TerminalSession?) {
        if (sessionToRename == null) return

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, { text ->
            renameSession(sessionToRename, text)
            termuxSessionListNotifyUpdated()
        }, -1, null, -1, null, null)
    }

    private fun renameSession(sessionToRename: TerminalSession?, text: String?) {
        if (sessionToRename == null) return
        sessionToRename.mSessionName = text
        val service = mActivity.getTermuxService()
        if (service != null) {
            val termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename)
            if (termuxSession != null)
                termuxSession.executionCommand.shellName = text
        }
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mActivity.getTermuxService() ?: return

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show()
        } else {
            val currentSession = mActivity.getCurrentSession()

            val workingDirectory = if (currentSession == null) {
                mActivity.getSettings().resolveDefaultWorkingDirectory()
            } else {
                currentSession.cwd
            }

            val newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName) ?: return

            val newTerminalSession = newTermuxSession.terminalSession
            setCurrentSession(newTerminalSession)

            mActivity.getScreenState().closeDrawer()
        }
    }

    fun setCurrentStoredSession() {
        val currentSession = mActivity.getCurrentSession()
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle)
        else
            mActivity.getPreferences().setCurrentSession(null)
    }

    /** The current session as stored or the last one if that does not exist. */
    fun getCurrentStoredSessionOrLast(): TerminalSession? {
        val stored = getCurrentStoredSession()

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored
        } else {
            // Else return the last session currently running
            val service = mActivity.getTermuxService() ?: return null

            val termuxSession = service.getLastTermuxSession()
            return if (termuxSession != null) termuxSession.terminalSession else null
        }
    }

    private fun getCurrentStoredSession(): TerminalSession? {
        val sessionHandle = mActivity.getPreferences().getCurrentSession()

        // If no session is stored in shared preferences
        if (sessionHandle == null) return null

        // Check if the session handle found matches one of the currently running sessions
        val service = mActivity.getTermuxService() ?: return null

        return service.getTerminalSessionForHandle(sessionHandle)
    }

    open fun removeFinishedSession(finishedSession: TerminalSession?) {
        if (finishedSession == null) return

        // Capture host state before disposal: only the displayed session may hand
        // focus to a neighbour. A background session finishing must be invisible.
        val wasCurrentSession = mActivity.getCurrentSession() === finishedSession

        val backend = mSessionBackends.remove(finishedSession.mHandle)
        backend?.release()

        val service = mActivity.getTermuxService()
        if (service == null) {
            if (wasCurrentSession) clearCurrentSession()
            finishedSession.close()
            return
        }

        val removedIndex = service.removeTermuxSession(finishedSession)
        finishedSession.close()

        if (service.getIndexOfSession(finishedSession) >= 0) {
            // The service kept the session (see docs/fork/issues/004). Leave the
            // host untouched instead of pointing it at an arbitrary neighbour.
            Logger.logWarn(LOG_TAG, "Session ${finishedSession.mHandle} is still owned by the service after removal")
            return
        }

        if (!wasCurrentSession) return

        val replacementIndex = resolveReplacementIndex(removedIndex, service.getTermuxSessionsSize())
        if (replacementIndex < 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val termuxSession = service.getTermuxSession(replacementIndex)
        if (termuxSession != null) {
            setCurrentSession(termuxSession.terminalSession)
        } else {
            clearCurrentSession()
        }
    }

    /** Releases every session backend this host owns. Call when the activity is destroyed. */
    fun onDestroy() {
        for (backend in mSessionBackends.values) {
            backend.release()
        }
        mSessionBackends.clear()
        mActivity.getTerminalView().setBackend(null)
        mActivity.setCurrentSession(null)
    }

    /** Drops the displayed session and its backend instead of leaving a removed one installed. */
    private fun clearCurrentSession() {
        mActivity.getTerminalView().setBackend(null)
        mActivity.setCurrentSession(null)
        updateBackgroundColor()
        mActivity.onCurrentSessionChanged()
        termuxSessionListNotifyUpdated()
    }

    fun termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated()
    }

    fun checkAndScrollToSession(session: TerminalSession?) {
        if (!mActivity.isVisible()) return
        val service = mActivity.getTermuxService() ?: return

        if (service.getIndexOfSession(session) < 0) return
        mActivity.getSessionsUiState().refresh(service.getTermuxSessions(), session)
        mActivity.getSessionsUiState().requestScrollToCurrent()
    }

    private fun toToastTitle(session: TerminalSession?): String? {
        if (session == null) return null
        val service = mActivity.getTermuxService() ?: return null

        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return null
        val toastTitle = StringBuilder("[${indexOfSession + 1}]")
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName)
        }
        val title = session.title
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(if (session.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    fun checkForFontAndColors() {
        val s = mActivity.getSettings()
        TermuxTerminalStyling.applyTerminalStyling(
            mActivity, TermuxThemeUtils.isMaterialYouThemeEnabled(s.materialYouTheme.value),
            mActivity.getTerminalView(), mActivity.getCurrentSession())
    }

    fun updateBackgroundColor() {
        if (!mActivity.isVisible()) return

        val backgroundColor = TermuxTerminalStyling.resolveBackgroundColor(mActivity.getCurrentSession())
        mActivity.getWindow().decorView.setBackgroundColor(backgroundColor)
        mActivity.getTerminalToolbarState().backgroundColor = backgroundColor
        mActivity.getSessionsUiState().backgroundColor = backgroundColor

        val insetsController = WindowInsetsControllerCompat(mActivity.getWindow(), mActivity.getWindow().decorView)
        val lightSystemBars = ColorUtils.calculateLuminance(backgroundColor) > 0.5
        insetsController.setAppearanceLightStatusBars(lightSystemBars)
        insetsController.setAppearanceLightNavigationBars(lightSystemBars)
    }

    companion object {
        private const val MAX_SESSIONS = TermuxConstants.TERMUX_APP_MAX_TERMINAL_SESSIONS

        private const val LOG_TAG = "TermuxTerminalSessionActivityClient"

        /**
         * Index of the session to display after the session at [removedIndex] left a list that
         * now holds [remainingSize] sessions.
         *
         * @return -1 when nothing is left to display.
         */
        @JvmStatic
        fun resolveReplacementIndex(removedIndex: Int, remainingSize: Int): Int {
            if (remainingSize <= 0 || removedIndex < 0) return -1
            return minOf(removedIndex, remainingSize - 1)
        }
    }
}
