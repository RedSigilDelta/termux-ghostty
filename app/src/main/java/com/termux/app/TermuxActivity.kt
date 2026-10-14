package com.termux.app

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.api.file.FileReceiverActivity
import com.termux.app.settings.TermuxFiles
import com.termux.app.settings.TermuxSettings
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.app.terminal.AgentsUiState
import com.termux.app.terminal.TabThumbnails
import com.termux.app.terminal.TabsUiState
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.app.terminal.io.ModifierKeys
import com.termux.app.workspace.Tab
import com.termux.app.workspace.newId
import com.termux.app.workspace.ui.ViewerKind
import com.termux.app.workspace.ui.looksBinary
import com.termux.app.workspace.ui.readHead
import com.termux.app.workspace.ui.viewerKindFor
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.PermissionUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.crash.TermuxCrashUtils
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.theme.TermuxThemeUtils
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalComposeView
import com.termux.terminal.compose.TerminalDiagnostic
import com.termux.terminal.compose.TerminalWallpaper
import com.termux.terminal.compose.session.TerminalSessionBackend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
class TermuxActivity : AppCompatActivity(), ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    private var mTermuxService: TermuxService? = null

    /**
     * The Compose-backed terminal shown in {@link TermuxActivity}.
     */
    private lateinit var mTerminalView: TerminalComposeView

    /**
     * The app policy object for the Compose-backed terminal.
     */
    private lateinit var mTermuxTerminalViewClient: TermuxTerminalViewClient

    private var mCurrentSession: TerminalSession? = null
    private lateinit var mTerminalCursorBlinker: TerminalCursorBlinker

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    private lateinit var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient

    /**
     * Termux app shared preferences manager.
     */
    private lateinit var mPreferences: TermuxAppSharedPreferences

    /**
     * The Ctrl and Alt state shared by the key bar and the terminal view.
     */
    private val mModifierKeys = ModifierKeys()

    /**
     * The state of the tabs grid and the key bar tab button.
     */
    private lateinit var mTabsUiState: TabsUiState

    /**
     * The agent records shown by the key bar agents button and the agents sheet.
     */
    private lateinit var mAgentsUiState: AgentsUiState

    /**
     * The live frames shown on the tab cards while the tabs grid is open.
     */
    private val mTabThumbnails = TabThumbnails()

    /**
     * The state of the Compose root: tabs grid visibility and terminal margins.
     */
    private lateinit var mScreenState: TermuxScreenState

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private val mTermuxActivityBroadcastReceiver = TermuxActivityBroadcastReceiver()

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    private var mLastToast: Toast? = null

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private var mIsVisible = false

    /**
     * If onResume() was called after onCreate().
     */
    private var mIsOnResumeAfterOnCreate = false

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private var mIsActivityRecreated = false

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private var mIsInvalidState = false

    private var mLastMaterialYouWallpaperId = 0

    private var mAppliedSettings: TermuxSettings? = null

    private var mAppliedFileStamps: List<Long>? = null

    private var mWallpaperJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        mIsOnResumeAfterOnCreate = true

        if (savedInstanceState != null)
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false)

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        // Load app settings from DataStore and termux.toml
        reloadProperties()
        mLastMaterialYouWallpaperId = getMaterialYouWallpaperId()

        setActivityTheme()
        applyMaterialYouThemeOverlay()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(this, true)
        if (preferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true
            return
        }
        mPreferences = preferences

        if (getSettings().fullscreen) {
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        setTermuxTerminalViewAndClients()

        mTabsUiState = TabsUiState()
        mAgentsUiState = AgentsUiState()

        val settings = getSettings()
        mScreenState = TermuxScreenState(settings.terminalMarginHorizontal, settings.terminalMarginVertical, settings.arrowJoystick)
        mScreenState.fontStamp = TermuxFiles.fontFile.lastModified()

        val root = ComposeView(this)
        setContentView(root)
        bindTermuxScreen(root, mScreenState, mTerminalView, mModifierKeys, mTabsUiState, mTabThumbnails, mAgentsUiState, ScreenActions())

        registerForContextMenu(mTerminalView)

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            val serviceIntent = Intent(this, TermuxService::class.java)
            startService(serviceIntent)

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw RuntimeException("bindService() failed")
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "TermuxActivity failed to start TermuxService", e)
            Logger.showToast(this,
                getString(if (e.message?.contains("app is in background") == true) R.string.error_termux_service_start_failed_bg else R.string.error_termux_service_start_failed_general),
                true)
            mIsInvalidState = true
            return
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this)

        applyTerminalCustomizations()
        mAppliedSettings = getSettings()
        mAppliedFileStamps = getFileStamps()
    }

    override fun onStart() {
        super.onStart()

        Logger.logDebug(LOG_TAG, "onStart")

        if (mIsInvalidState) return

        val appliedSettings = mAppliedSettings
        val settings = getSettings()
        if (appliedSettings != null && (settings != appliedSettings || getFileStamps() != mAppliedFileStamps)) {
            reloadActivityStyling(settings.nightMode != appliedSettings.nightMode ||
                settings.materialYouTheme != appliedSettings.materialYouTheme)
        }

        mIsVisible = true

        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onStart()

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onStart()

        registerTermuxActivityBroadcastReceiver()
    }

    override fun onResume() {
        super.onResume()

        Logger.logVerbose(LOG_TAG, "onResume")

        if (mIsInvalidState) return

        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onResume()

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onResume()

        reloadMaterialYouThemeIfNeeded()

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG)

        mIsOnResumeAfterOnCreate = false
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        Logger.logDebug(LOG_TAG, "onNewIntent")
        setIntent(intent)

        if (mIsInvalidState) return
        val service = mTermuxService ?: return

        if (handleLaunchIntent(intent, true)) return
        if (service.isTermuxSessionsEmpty()) return

        mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast())
    }

    protected override fun onStop() {
        super.onStop()

        Logger.logDebug(LOG_TAG, "onStop")

        if (mIsInvalidState) return

        mIsVisible = false
        mTabThumbnails.stop()

        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onStop()

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onStop()

        unregisterTermuxActivityBroadcastReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()

        Logger.logDebug(LOG_TAG, "onDestroy")

        // Release every session backend this host owns before dropping the service
        // reference. Otherwise a debounced resize from this destroyed activity can
        // still resize a session that a recreated activity now owns.
        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onDestroy()

        if (mIsInvalidState) return

        val service = mTermuxService
        if (service != null) {
            // Do not leave service and session clients with references to activity.
            service.unsetTermuxTerminalSessionClient()
            mTermuxService = null
        }

        try {
            unbindService(this)
        } catch (e: Exception) {
            // ignore.
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState")

        super.onSaveInstanceState(outState)
        outState.putBoolean(ARG_ACTIVITY_RECREATED, true)
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    override fun onServiceConnected(componentName: ComponentName?, service: IBinder?) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        val termuxService = (service as TermuxService.LocalBinder).service
        mTermuxService = termuxService

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                termuxService.getWorkspaces().state.collect { refreshTabsUi() }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(termuxService.getWorkspaces().state, termuxService.programStatus.all) { _, statuses -> statuses }
                    .collect { statuses -> mAgentsUiState.refresh(termuxService.getWorkspaces().liveTerminals(), statuses) }
            }
        }

        val intent = getIntent()
        setIntent(null)

        if (termuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(this) {
                    if (mTermuxService == null) return@setupBootstrapIfNeeded // Activity might have been destroyed.
                    try {
                        mTermuxTerminalSessionActivityClient.restoreWorkspaceTabs()

                        var launchFailsafe = false
                        val extras = intent?.extras
                        if (extras != null) {
                            launchFailsafe = extras.getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                        }
                        if (launchFailsafe) {
                            mTermuxTerminalSessionActivityClient.addNewSession(true, null)
                        } else {
                            mTermuxTerminalSessionActivityClient.showActiveWorkspace()
                        }
                    } catch (e: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                }
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing()
            }
        } else {
            mTermuxTerminalSessionActivityClient.restoreWorkspaceTabs()
            if (!handleLaunchIntent(intent, !mIsActivityRecreated)) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast())
            }
        }

        // Update the {@link TerminalSession} clients.
        termuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing()
    }

    private fun handleLaunchIntent(intent: Intent?, allowNewSessionShortcut: Boolean): Boolean {
        if (intent == null) return false
        val service = mTermuxService ?: return false
        if (!::mTermuxTerminalSessionActivityClient.isInitialized) return false

        val requestedSessionHandle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE)
        if (requestedSessionHandle != null) {
            val requestedSession = service.getTerminalSessionForHandle(requestedSessionHandle)
            if (requestedSession != null) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(requestedSession)
                return true
            }
        }

        if (!allowNewSessionShortcut) return false
        if (Intent.ACTION_RUN != intent.action) return false

        val isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null)
        return true
    }

    private fun reloadProperties() {
        TermuxSettingsRepository.get().reload()

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onReloadProperties()
    }

    private fun setActivityTheme() {
        val appNightMode = TermuxThemeUtils.getAppNightMode(
            getSettings().nightMode.value, getSettings().materialYouTheme.value)
        TermuxThemeUtils.setAppNightMode(appNightMode)
        AppCompatActivityUtils.setNightMode(this, appNightMode, true)
    }

    private fun applyMaterialYouThemeOverlay() {
        val materialYouTheme = getSettings().materialYouTheme.value
        if (!TermuxThemeUtils.isMaterialYouThemeEnabled(materialYouTheme)) return

        if (TermuxThemeUtils.MATERIAL_YOU_THEME_LIGHT == materialYouTheme) {
            setTheme(R.style.Theme_TermuxActivity_M3_Light_NoActionBar)
        } else if (TermuxThemeUtils.MATERIAL_YOU_THEME_BLACK == materialYouTheme) {
            setTheme(R.style.Theme_TermuxActivity_M3_Black_NoActionBar)
        } else {
            setTheme(R.style.Theme_TermuxActivity_M3_DayNight_NoActionBar)
        }

        DynamicColors.applyToActivityIfAvailable(this)

        if (TermuxThemeUtils.MATERIAL_YOU_THEME_BLACK == materialYouTheme) {
            theme.applyStyle(R.style.ThemeOverlay_TermuxActivity_M3_Black, true)
        }
    }

    private fun setMargins() {
        mScreenState.marginHorizontal = getSettings().terminalMarginHorizontal
        mScreenState.marginVertical = getSettings().terminalMarginVertical
        mScreenState.joystickMode = getSettings().arrowJoystick
    }

    private fun setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = TermuxTerminalSessionActivityClient(this)
        mTermuxTerminalViewClient = TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient)

        // Set termux terminal view
        mTerminalView = TerminalComposeView(this)
        mTerminalView.setFocusableInTouchMode(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mTerminalView.setDefaultFocusHighlightEnabled(false)
        mTerminalView.listener = object : TerminalComposeView.Listener {
            override fun onSelectionChanged(selectedText: String?, selecting: Boolean) {
                mTermuxTerminalViewClient.copyModeChanged(selecting)
            }

            override fun onSingleTap(event: MotionEvent): Boolean {
                return mTermuxTerminalViewClient.onSingleTapUp(event)
            }

            override fun onCopyRequest(selectedText: String) {
                val session = getCurrentSession()
                if (::mTermuxTerminalSessionActivityClient.isInitialized && session != null)
                    mTermuxTerminalSessionActivityClient.onCopyTextToClipboard(session, selectedText)
            }

            override fun onPasteRequest() {
                if (::mTermuxTerminalSessionActivityClient.isInitialized)
                    mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(getCurrentSession())
            }

            override fun onMoreSelectionRequest(selectedText: String) {
                mTerminalView.showContextMenu()
            }

            override fun shouldShowMoreSelectionAction(): Boolean {
                return true
            }

            override fun onFontSizeChanged(fontSize: Int) {
                mPreferences.setFontSize(fontSize)
            }

            override fun onKeyDown(event: KeyEvent): Boolean {
                val session = getCurrentSession() ?: return true
                if (mTermuxTerminalViewClient.onKeyDown(event.keyCode, event, session)) return true
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    mTermuxTerminalViewClient.shouldBackButtonBeMappedToEscape()) {
                    return mTerminalView.submitText("\u001b")
                }
                return false
            }

            override fun onKeyUp(event: KeyEvent): Boolean {
                return mTermuxTerminalViewClient.onKeyUp(event.keyCode, event)
            }

            override fun onCodePoint(codePoint: Int, controlDown: Boolean, altDown: Boolean): Boolean {
                val session = getCurrentSession()
                return session != null && session.hasActiveTerminalBackend() &&
                    mTermuxTerminalViewClient.onCodePoint(codePoint, controlDown, session)
            }

            override fun onImeSessionClosed() {
                mTermuxTerminalViewClient.onSoftKeyboardDismissed()
            }

            override fun onImeVisibilityChanged(isVisible: Boolean) {
                mScreenState.imeVisible = isVisible
                mTermuxTerminalViewClient.onSoftKeyboardVisibilityChanged(isVisible)
            }

            override fun onDiagnostics(diagnostic: TerminalDiagnostic) {
                Logger.logWarn(LOG_TAG, "Terminal Compose diagnostic: $diagnostic")
            }
        }
        mTerminalView.setModifierKeyReader(object : ModifierKeyReader {
            override fun readControl(): Boolean {
                return mTermuxTerminalViewClient.readControlKey()
            }

            override fun readAlt(): Boolean {
                return mTermuxTerminalViewClient.readAltKey()
            }

            override fun readShift(): Boolean {
                return mTermuxTerminalViewClient.readShiftKey()
            }

            override fun readFn(): Boolean {
                return mTermuxTerminalViewClient.readFnKey()
            }
        })
        mTerminalView.setUnconditionalKeyboardOnTap(
            getSettings().unconditionalSoftKeyboardOnTap)
        mTerminalCursorBlinker = TerminalCursorBlinker(mTerminalView::onFrameAvailable)
        mTerminalView.setFontSizeBounds(mPreferences.getMinFontSize(), mPreferences.getMaxFontSize())
        mTerminalView.setFontSize(mPreferences.getFontSize())

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onCreate()

        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onCreate()
    }

    private inner class ScreenActions : TermuxScreenActions {

        override fun onKey(keyCode: Int) {
            mTerminalView.submitKey(keyCode, mModifierKeys.metaState())
        }

        override fun openTabs() {
            showTabsGrid()
        }

        override fun openAgents() {
            mTerminalView.hideSoftKeyboard()
            mTermuxService?.programStatus?.let { store ->
                mAgentsUiState.entries.map { it.sessionHandle }.distinct().forEach(store::markSeen)
            }
            mAgentsUiState.sheetVisible = true
        }

        override fun onSelectAgent(workspaceId: String, tabId: String) {
            val workspaces = mTermuxService?.getWorkspaces() ?: return
            if (workspaces.state.value.activeWorkspace?.id != workspaceId) {
                workspaces.activateWorkspace(workspaceId)
                mTermuxTerminalSessionActivityClient.showActiveWorkspace()
            }
            workspaces.sessionFor(tabId)?.let(mTermuxTerminalSessionActivityClient::setCurrentSession)
            mAgentsUiState.sheetVisible = false
            mScreenState.tabsGridVisible = false
        }

        override fun onSelectTab(id: String) {
            val workspaces = mTermuxService?.getWorkspaces()
            val session = workspaces?.sessionFor(id)
            if (session != null) {
                mTermuxTerminalSessionActivityClient.setCurrentSession(session)
            } else {
                workspaces?.activateTab(id)
                leaveTerminal()
            }
            mScreenState.tabsGridVisible = false
        }

        override fun onCloseTab(id: String) {
            mTermuxTerminalSessionActivityClient.closeTab(id)
        }

        override fun onRenameTab(id: String, name: String) {
            mTermuxService?.getWorkspaces()?.sessionFor(id)?.let { mTermuxTerminalSessionActivityClient.renameSession(it, name) }
        }

        override fun onNewTerminal() {
            mTermuxTerminalSessionActivityClient.addNewSession(false, null)
            mScreenState.tabsGridVisible = false
        }

        override fun onNewFiles() {
            val workspaces = mTermuxService?.getWorkspaces() ?: return
            val session = getCurrentSession()?.takeIf { workspaces.isInActiveWorkspace(it) }
            val dir = session?.cwd ?: workspaces.state.value.activeWorkspace?.root ?: return
            workspaces.openTab(Tab.Files(newId(), dir))
            leaveTerminal()
            mScreenState.tabsGridVisible = false
        }

        override fun onSelectWorkspace(id: String) {
            mTermuxService?.getWorkspaces()?.activateWorkspace(id)
            mTermuxTerminalSessionActivityClient.showActiveWorkspace()
            mScreenState.tabsGridVisible = false
        }

        override fun onNewWorkspace(name: String, root: String) {
            if (!File(root).isDirectory) {
                showToast(getString(R.string.workspace_folder_missing, root), true)
                return
            }
            mTermuxService?.getWorkspaces()?.createWorkspace(name, root)
            mTermuxTerminalSessionActivityClient.showActiveWorkspace()
            mScreenState.tabsGridVisible = false
        }

        override fun onRenameWorkspace(id: String, name: String) {
            mTermuxService?.getWorkspaces()?.renameWorkspace(id, name)
        }

        override fun onDeleteWorkspace(id: String) {
            val service = mTermuxService ?: return
            val workspaces = service.getWorkspaces()
            val sessions = workspaces.workspaceSessions(id)
            workspaces.deleteWorkspace(id)
            // Switch away before killing the old shells.
            mTermuxTerminalSessionActivityClient.showActiveWorkspace()
            sessions.forEach { mTermuxTerminalSessionActivityClient.removeFinishedSession(it) }
        }

        override fun onOpenSettings() {
            ActivityUtils.startActivity(this@TermuxActivity, Intent(this@TermuxActivity, SettingsActivity::class.java))
        }

        override fun onDismiss() {
            mScreenState.tabsGridVisible = false
        }

        override fun backendFor(session: TerminalSession): TerminalSessionBackend =
            mTermuxTerminalSessionActivityClient.backendFor(session)

        override fun onFilesNavigate(tabId: String, dir: String) {
            mTermuxService?.getWorkspaces()?.setFilesDir(tabId, dir)
        }

        override fun onOpenFile(path: String) {
            val kind = viewerKindFor(File(path).name)
            when {
                kind == null -> onOpenExternal(path)
                kind != ViewerKind.TEXT -> openViewer(path)
                else -> lifecycleScope.launch {
                    val binary = withContext(Dispatchers.IO) {
                        runCatching { looksBinary(readHead(File(path))) }.getOrDefault(false)
                    }
                    if (binary) onOpenExternal(path) else openViewer(path)
                }
            }
        }

        override fun onOpenExternal(path: String) {
            val file = File(path)
            val contentType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            val uri = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, file.absolutePath)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, contentType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(intent, null))
            } catch (e: ActivityNotFoundException) {
                showToast(getString(R.string.viewer_no_app), false)
            }
        }

        override fun onOpenTerminal(dir: String) {
            mTermuxTerminalSessionActivityClient.addNewSession(false, null, dir)
        }

        private fun openViewer(path: String) {
            mTermuxService?.getWorkspaces()?.openTab(Tab.Viewer(newId(), path))
            leaveTerminal()
        }
    }

    override fun onBackPressed() {
        // The tabs grid registers a BackHandler while it is shown; let it handle back first.
        if (onBackPressedDispatcher.hasEnabledCallbacks()) super.onBackPressed() else finishActivityIfNotFinishing()
    }

    fun finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!isFinishing()) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    fun showToast(text: String?, longDuration: Boolean) {
        if (text == null || text.isEmpty()) return
        mLastToast?.cancel()
        val toast = Toast.makeText(this, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mLastToast = toast
        toast.setGravity(Gravity.TOP, 0, 0)
        toast.show()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View?, menuInfo: ContextMenuInfo?) {
        val currentSession = getCurrentSession() ?: return

        val autoFillEnabled = mTerminalView.isAutoFillEnabled()

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, resources.getString(R.string.action_kill_process, currentSession.pid)).setEnabled(currentSession.isRunning())
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn())
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    /** Hook system menu to show context menu instead. */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        mTerminalView.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session = getCurrentSession()

        return when (item.itemId) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                mTermuxTerminalViewClient.showUrlSelection()
                true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mTermuxTerminalViewClient.shareSessionTranscript()
                true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mTermuxTerminalViewClient.shareSelectedText()
                true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView.requestAutoFillUsername()
                true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView.requestAutoFillPassword()
                true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                true
            }
            CONTEXT_MENU_STYLING_ID -> {
                showStylingDialog()
                true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                true
            }
            CONTEXT_MENU_HELP_ID -> {
                ActivityUtils.startActivity(this, Intent(this, HelpActivity::class.java))
                true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                ActivityUtils.startActivity(this, Intent(this, SettingsActivity::class.java))
                true
            }
            CONTEXT_MENU_REPORT_ID -> {
                mTermuxTerminalViewClient.reportIssueFromTranscript()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        if (::mTerminalView.isInitialized) mTerminalView.unsetStoredSelectedText()
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) return

        val b = AlertDialog.Builder(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_kill_process)
        b.setPositiveButton(android.R.string.yes) { dialog, _ ->
            dialog.dismiss()
            session.finishIfRunning()
        }
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            showToast(resources.getString(R.string.msg_terminal_reset), true)

            if (::mTermuxTerminalSessionActivityClient.isInitialized)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession()
        }
    }

    private fun showStylingDialog() {
        val stylingIntent = Intent()
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME)

        fun showStylingNotInstalledDialog() {
            AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install) { _, _ ->
                    ActivityUtils.startActivity(this, Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)))
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }

        try {
            startActivity(stylingIntent)
        } catch (e: ActivityNotFoundException) {
            showStylingNotInstalledDialog()
        } catch (e: IllegalArgumentException) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            showStylingNotInstalledDialog()
        }
    }

    private fun toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false)
            mPreferences.setKeepScreenOn(false)
        } else {
            mTerminalView.setKeepScreenOn(true)
            mPreferences.setKeepScreenOn(true)
        }
    }

    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    fun requestStoragePermission(isPermissionCallback: Boolean) {
        Thread {
            // Do not ask for permission again
            val requestCode = if (isPermissionCallback) -1 else PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION

            // If permission is granted, then also setup storage symlinks.
            if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    this, requestCode, !isPermissionCallback)) {
                if (isPermissionCallback)
                    Logger.logInfoAndShowToast(this, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request))

                TermuxInstaller.setupStorageSymlinks(this)
            } else {
                if (isPermissionCallback)
                    Logger.logInfoAndShowToast(this, LOG_TAG,
                        getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request))
            }
        }.start()
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: $requestCode, resultCode: $resultCode, data: ${IntentUtils.getIntentString(data)}")
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: $requestCode, permissions: ${permissions.contentToString()}, grantResults: ${grantResults.contentToString()}")
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true)
        }
    }

    fun getScreenState(): TermuxScreenState {
        return mScreenState
    }

    fun getModifierKeys(): ModifierKeys {
        return mModifierKeys
    }

    fun showTabsGrid() {
        mTerminalView.hideSoftKeyboard()
        mScreenState.tabsGridVisible = true
    }

    /** Hides the keyboard and drops focus so typing cannot reach a terminal that is no longer shown. */
    private fun leaveTerminal() {
        mTerminalView.hideSoftKeyboard()
        mTerminalView.clearFocus()
    }

    fun refreshTabsUi() {
        val service = mTermuxService ?: return
        mTabsUiState.refresh(service.getWorkspaces().state.value, service.getWorkspaces()::sessionFor)
        mAgentsUiState.refresh(service.getWorkspaces().liveTerminals(), service.programStatus.all.value)
    }

    fun termuxSessionListNotifyUpdated() {
        refreshTabsUi()
    }

    /** Call when only the current session changes (no structural changes). */
    fun onCurrentSessionChanged() {
        refreshTabsUi()
    }

    fun isVisible(): Boolean {
        return mIsVisible
    }

    fun isOnResumeAfterOnCreate(): Boolean {
        return mIsOnResumeAfterOnCreate
    }

    fun isActivityRecreated(): Boolean {
        return mIsActivityRecreated
    }

    fun getTermuxService(): TermuxService? {
        return mTermuxService
    }

    fun setCurrentSession(session: TerminalSession?) {
        mCurrentSession = session
        if (::mTerminalCursorBlinker.isInitialized) mTerminalCursorBlinker.setSession(session)
        // setSession() stopped the previous blinker, so re-arm it for the new session.
        if (::mTermuxTerminalViewClient.isInitialized) mTermuxTerminalViewClient.onCurrentSessionDisplayed()
    }

    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        if (!::mTerminalCursorBlinker.isInitialized) return
        if (start && !mTerminalCursorBlinker.setRate(getSettings().terminalCursorBlinkRate)) {
            Logger.logError(LOG_TAG, "Failed to set terminal cursor blink rate")
            return
        }
        mTerminalCursorBlinker.setState(start, startOnlyIfCursorEnabled)
    }

    fun getTerminalView(): TerminalComposeView {
        return mTerminalView
    }

    fun getTermuxTerminalViewClient(): TermuxTerminalViewClient {
        return mTermuxTerminalViewClient
    }

    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionActivityClient {
        return mTermuxTerminalSessionActivityClient
    }

    fun getCurrentSession(): TerminalSession? {
        return mCurrentSession
    }

    fun getPreferences(): TermuxAppSharedPreferences {
        return mPreferences
    }

    fun getSettings(): TermuxSettings {
        return TermuxSettingsRepository.get().current()
    }

    private fun registerTermuxActivityBroadcastReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        intentFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED)

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter)
    }

    private fun unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver)
    }

    private fun fixTermuxActivityBroadcastReceiverIntent(intent: Intent?) {
        if (intent == null) return

        val extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if ("storage" == extraReloadStyle) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.action = TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS
        }
    }

    inner class TermuxActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent)

                when (intent.action) {
                    TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH -> {
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash")
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG)
                    }
                    TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true))
                    }
                    TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        requestStoragePermission(false)
                    }
                    Intent.ACTION_WALLPAPER_CHANGED -> {
                        Logger.logDebug(LOG_TAG, "Received intent for wallpaper changed")
                        reloadMaterialYouThemeIfNeeded()
                    }
                }
            }
        }
    }

    private fun reloadActivityStyling(recreateActivity: Boolean) {
        reloadProperties()
        mLastMaterialYouWallpaperId = getMaterialYouWallpaperId()

        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(TermuxThemeUtils.getAppNightMode(
            getSettings().nightMode.value, getSettings().materialYouTheme.value))

        setMargins()
        mScreenState.fontStamp = TermuxFiles.fontFile.lastModified()

        applyTerminalCustomizations()

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this)

        if (::mTermuxTerminalSessionActivityClient.isInitialized)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling()

        if (::mTermuxTerminalViewClient.isInitialized)
            mTermuxTerminalViewClient.onReloadActivityStyling()

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity")
            this@TermuxActivity.recreate()
        }

        mAppliedSettings = getSettings()
        mAppliedFileStamps = getFileStamps()
    }

    private fun applyTerminalCustomizations() {
        val settings = getSettings()
        mTerminalView.setCursorEffect(settings.cursorTrail.effect)
        mTerminalView.setPreferredFrameRate(settings.visualEffectFrameRate.framesPerSecond)
        mTerminalView.setWallpaperScaling(settings.wallpaperScaling.scaling)
        mTerminalView.setWallpaperBackgroundOpacity(settings.wallpaperBackgroundOpacity)
        applyWallpaper()
    }

    private fun applyWallpaper() {
        mWallpaperJob?.cancel()
        val file = TermuxFiles.TERMUX_WALLPAPER_FILE
        if (!getSettings().wallpaper || !file.isFile) {
            mTerminalView.setWallpaper(null)
            return
        }
        val id = file.lastModified()
        if (mTerminalView.getWallpaperConfig().wallpaper?.id == id) return
        mWallpaperJob = lifecycleScope.launch {
            val wallpaper = withContext(Dispatchers.IO) { decodeWallpaper(file, id) }
            mTerminalView.setWallpaper(wallpaper)
        }
    }

    private fun decodeWallpaper(file: File, id: Long): TerminalWallpaper? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, WALLPAPER_DECODE_MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return try {
            TerminalWallpaper.fromBitmap(id, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun getFileStamps(): List<Long> =
        listOf(TermuxFiles.TERMUX_WALLPAPER_FILE.lastModified(), TermuxFiles.fontFile.lastModified())

    private fun reloadMaterialYouThemeIfNeeded() {
        if (!shouldReloadMaterialYouThemeForWallpaperChanges()) return

        val wallpaperId = getMaterialYouWallpaperId()
        if (wallpaperId == 0) return
        if (mLastMaterialYouWallpaperId == 0) {
            mLastMaterialYouWallpaperId = wallpaperId
            return
        }
        if (mLastMaterialYouWallpaperId == wallpaperId) return

        Logger.logDebug(LOG_TAG, "Recreating activity for updated Material You wallpaper colors")
        mLastMaterialYouWallpaperId = wallpaperId
        reloadActivityStyling(true)
    }

    private fun shouldReloadMaterialYouThemeForWallpaperChanges(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return TermuxThemeUtils.isMaterialYouThemeEnabled(getSettings().materialYouTheme.value)
    }

    private fun getMaterialYouWallpaperId(): Int {
        if (!shouldReloadMaterialYouThemeForWallpaperChanges()) return 0
        return WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    }

    companion object {
        private const val CONTEXT_MENU_SELECT_URL_ID = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID = 4
        private const val CONTEXT_MENU_STYLING_ID = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6
        private const val CONTEXT_MENU_HELP_ID = 7
        private const val CONTEXT_MENU_SETTINGS_ID = 8
        private const val CONTEXT_MENU_REPORT_ID = 9

        private const val ARG_ACTIVITY_RECREATED = "activity_recreated"

        private const val WALLPAPER_DECODE_MAX_DIMENSION = 2048

        private const val LOG_TAG = "TermuxActivity"

        @JvmStatic
        fun updateTermuxActivityStyling(context: Context, recreateActivity: Boolean) {
            // Make sure that terminal styling is always applied.
            val stylingIntent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity)
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startTermuxActivity(context: Context) {
            ActivityUtils.startActivity(context, newInstance(context))
        }

        @JvmStatic
        fun startTermuxActivity(context: Context, sessionHandle: String?) {
            ActivityUtils.startActivity(context, newInstance(context, sessionHandle))
        }

        @JvmStatic
        fun newInstance(context: Context): Intent {
            return newInstance(context, null)
        }

        @JvmStatic
        fun newInstance(context: Context, sessionHandle: String?): Intent {
            val intent = Intent(context, TermuxActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sessionHandle != null)
                intent.putExtra(TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE, sessionHandle)
            return intent
        }
    }
}
