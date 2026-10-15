package com.mrndtvndv.term.server

import com.termux.terminal.compose.session.ProgramStatusStore
import com.termux.terminal.compose.session.ProgramStatusState
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import com.mrndtvndv.term.R
import com.mrndtvndv.term.service.SshSessionService
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.create
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Process-scoped owner of all terminal/SSH connections.
 *
 * Lives for the whole process (created by TermApplication), so connections
 * survive the activity being destroyed — dismissing the app from recents no
 * longer kills sessions. The activity (via [SessionHost]) only renders and
 * forwards input; the manager keeps connections, service binding and
 * notifications independent of the UI.
 */
class AppSessionManager private constructor(context: Context) : AppSessionManagerAccess {

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE) }

    override val serverRepository by lazy { ServerRepository(prefs) }

    private val serverFactory by lazy {
        ServerFactory(
            context = appContext,
            onSessionClientCreated = { createDefaultSessionClient() },
            onServiceBind = { session, serverId -> bindTerminalSession(session, serverId) },
        )
    }
    private val serverManager by lazy { ServerManager(serverFactory::create) }
    override val coordinator by lazy {
        ServerCoordinator(
            serverManager = serverManager,
            serverRepository = serverRepository,
        )
    }

    private val finishedSessions = SessionFinishedEvents()
    /** Emits a server id whenever one of its sessions finished on its own. */
    override val sessionFinished = finishedSessions.flow
    private val terminalProgress = TerminalProgressStore()
    private val programStatus = ProgramStatusStore()

    private val appScope = MainScope()
    /** In-flight terminal opens by tab id. Main thread only. */
    private val pendingOpens = mutableMapOf<String, Deferred<Result<TerminalSession>>>()
    /** Tabs closed while their open was in flight, mapped to whether that close also terminates. */
    private val cancelledOpens = mutableMapOf<String, Boolean>()

    private var hostRef: WeakReference<SessionHost>? = null

    // ── Host (activity) ──────────────────────────────────────────────

    fun setHost(host: SessionHost?) {
        hostRef = host?.let { WeakReference(it) }
    }

    override fun observeTerminalProgress(session: TerminalSession): StateFlow<TerminalProgress?> =
        terminalProgress.observe(session)

    override fun observeProgramStatus(session: TerminalSession): StateFlow<ProgramStatusState> =
        programStatus.observe(session.mHandle)

    override fun markProgramStatusSeen(session: TerminalSession) {
        programStatus.markSeen(session.mHandle)
    }

    override fun locateSession(session: TerminalSession): SessionLocation? =
        serverManager.locate(session)

    override fun serverIdForSession(session: TerminalSession): String? =
        locateSession(session)?.serverId

    // ── Connection lifecycle ─────────────────────────────────────────

    override suspend fun connect(id: String): Result<Server> = coordinator.connect(id)

    /**
     * Disconnect one server and remove its terminals from every process-scoped
     * session registry. Manual close suppresses TerminalSession's finish
     * callback, so this cleanup cannot rely on onSessionFinished().
     */
    override fun disconnect(id: String) {
        val sessions = coordinator.getServer(id)?.terminals?.value?.values?.map { it.session }.orEmpty()
        sessions.forEach {
            terminalProgress.remove(it)
            programStatus.remove(it)
            unbindTerminalSession(it)
        }
        coordinator.disconnect(id)
    }

    fun disconnectAll() {
        val disconnectedIds = coordinator.activeIds.toList()
        val disconnectedSessions = disconnectedIds
            .mapNotNull { coordinator.getServer(it) }
            .flatMap { server -> server.terminals.value.values.map { it.session } }
        disconnectedSessions.forEach {
            terminalProgress.remove(it)
            programStatus.remove(it)
        }
        coordinator.disconnectAll()
        val service = sshService
        service?.disconnectAll()
        synchronized(this) {
            activeSessions.clear()
            pendingSessions.clear()
            connectionOfSession.clear()
            sshService?.stopIfIdle()
            unbindServiceIfNeeded()
        }
        // Explicit disconnects close the backend before the posted process-exit
        // event runs, which suppresses onSessionFinished. Emit explicitly so the
        // UI leaves the workspace instead of freezing on a dead session.
        disconnectedIds.forEach { finishedSessions.emit(it) }
    }

    // ── Terminals ────────────────────────────────────────────────────

    override suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession> {
        val server = coordinator.getServer(serverId)
            ?: return Result.failure(IllegalStateException("Server is not connected"))
        val existing = server.terminals.value[tab.id]
        if (existing?.running == true) return Result.success(existing.session)
        if (existing != null) closeTerminal(serverId, tab, terminate = false)

        // Registered before the body runs (Main dispatch), so later callers share this open.
        val open = pendingOpens[tab.id] ?: appScope.async {
            val opened: Result<TerminalSession> = runCatching {
                serverFactory.openTerminal(server, tab) { onTerminalFinished(serverId, it) }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    val shellFailure = !server.config.isLocal && error is IOException
                    val message = "Couldn't open a shell (the server may limit sessions per connection)"
                    Result.failure(if (shellFailure) IllegalStateException(message, error) else error)
                },
            )
            pendingOpens.remove(tab.id)
            val cancelledTerminate = cancelledOpens.remove(tab.id)
            when {
                coordinator.getServer(serverId) !== server -> {
                    // Disconnected mid-open: Server.disconnect already ran, so release the late terminal here.
                    server.removeTerminal(tab.id)?.let { (session, channel) ->
                        closeTerminalSession(session, channel)
                        terminalProgress.remove(session)
                        programStatus.remove(session)
                        unbindTerminalSession(session)
                    }
                    Result.failure(IllegalStateException("Server disconnected while opening"))
                }
                cancelledTerminate != null -> {
                    // Closed while opening: drop the new terminal instead of handing it out.
                    opened.onSuccess { closeTerminal(serverId, tab, terminate = cancelledTerminate) }
                    Result.failure(IllegalStateException("Terminal was closed while opening"))
                }
                else -> opened
            }
        }.also { pendingOpens[tab.id] = it }
        return open.await()
    }

    override fun closeTerminal(serverId: String, tab: Tab.Terminal, terminate: Boolean) {
        if (pendingOpens.containsKey(tab.id)) {
            cancelledOpens[tab.id] = terminate || cancelledOpens[tab.id] == true
        }
        val server = coordinator.getServer(serverId) ?: return
        server.removeTerminal(tab.id)?.let { (session, channel) ->
            closeTerminalSession(session, channel)
            terminalProgress.remove(session)
            programStatus.remove(session)
            unbindTerminalSession(session)
        }
        val ssh = server.sshSession
        if (terminate && ssh != null && server.isAlive) {
            val persistence = server.config.persistence.create()
            appScope.launch { persistence.terminate(tab) { ssh.execCommand(it) } }
        }
    }

    /** A shell or local process exited on its own. Manual closes never reach here. */
    private fun onTerminalFinished(serverId: String, session: TerminalSession) {
        val server = coordinator.getServer(serverId) ?: return
        val tabId = server.tabIdFor(session) ?: return
        if (server.isAlive) {
            server.markFinished(tabId, session)
            return
        }
        // The connection is gone, so the server's other terminals end with it.
        server.terminals.value.values.map { it.session }.filter { it !== session }.forEach {
            terminalProgress.remove(it)
            programStatus.remove(it)
            unbindTerminalSession(it)
        }
        coordinator.disconnect(serverId)
        finishedSessions.emit(serverId)
    }

    // ── Terminal protocol notifications ──────────────────────────────

    fun handleTerminalNotification(
        title: String?,
        body: String?,
        serverId: String? = null,
        tabId: String? = null,
    ) {
        val host = hostRef?.get()
        if (host != null && host.isAtLeast(Lifecycle.State.RESUMED)) {
            host.showInAppNotification(title, body, serverId, tabId)
        } else {
            showSystemNotification(title, body, serverId, tabId)
        }
    }

    private var nextNotificationId =
        TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATION_ID_BASE

    @Synchronized
    private fun getNextTerminalProtocolNotificationId(): Int {
        val id = nextNotificationId
        nextNotificationId++
        return id
    }

    private fun showSystemNotification(title: String?, body: String?, serverId: String?, tabId: String?) {
        val notificationManager =
            com.termux.shared.notification.NotificationUtils.getNotificationManager(appContext) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            com.termux.shared.notification.NotificationUtils.setupNotificationChannel(
                appContext,
                TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
                TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        }

        val normalizedTitle = title ?: "Terminal Notification"
        val normalizedBody = body ?: ""

        val intent = Intent(appContext, com.mrndtvndv.term.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (serverId != null) {
                putExtra(EXTRA_NOTIFICATION_SERVER_ID, serverId)
            }
            if (tabId != null) {
                putExtra(EXTRA_NOTIFICATION_TAB_ID, tabId)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(
            appContext,
            TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
            0, // Notification.PRIORITY_DEFAULT (deprecated in Java, inlined)
            normalizedTitle,
            normalizedBody,
            normalizedBody,
            contentIntent,
            null,
            com.termux.shared.notification.NotificationUtils.NOTIFICATION_MODE_ALL,
        ) ?: return

        builder.setSmallIcon(R.drawable.ic_notification)
        builder.setLargeIcon(
            BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
        )
        builder.setAutoCancel(true)

        notificationManager.notify(
            getNextTerminalProtocolNotificationId(),
            builder.build(),
        )
    }

    // ── Session service binding ──────────────────────────────────────

    private var sshService: SshSessionService? = null
    private val activeSessions = mutableSetOf<TerminalSession>()
    private val pendingSessions = mutableSetOf<TerminalSession>()
    /** SSH connection each terminal session runs on, so the notification can count connections. */
    private val connectionOfSession = mutableMapOf<TerminalSession, String>()
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SshSessionService.LocalBinder
            val s = binder.getService()
            synchronized(this@AppSessionManager) {
                sshService = s
                pendingSessions.forEach { s.addSession(it, connectionOfSession.getValue(it)) }
                pendingSessions.clear()
                if (activeSessions.isEmpty()) {
                    s.stopIfIdle()
                    unbindServiceIfNeeded()
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            synchronized(this@AppSessionManager) {
                sshService = null
                pendingSessions.addAll(activeSessions)
            }
        }
    }

    @Synchronized
    private fun bindTerminalSession(termSession: TerminalSession, serverId: String) {
        activeSessions.add(termSession)
        connectionOfSession[termSession] = serverId
        val intent = Intent(appContext, SshSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        val service = sshService
        if (service != null) {
            service.addSession(termSession, serverId)
        } else {
            pendingSessions.add(termSession)
        }
        if (!isBound) {
            isBound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    @Synchronized
    private fun unbindTerminalSession(termSession: TerminalSession) {
        activeSessions.remove(termSession)
        connectionOfSession.remove(termSession)
        pendingSessions.remove(termSession)
        sshService?.removeSession(termSession.mHandle)
        if (activeSessions.isEmpty()) {
            sshService?.stopIfIdle()
            unbindServiceIfNeeded()
        }
    }

    @Synchronized
    private fun unbindServiceIfNeeded() {
        if (!isBound) return
        appContext.unbindService(connection)
        isBound = false
        sshService = null
    }

    // ── Default session client ───────────────────────────────────────

    /**
     * Activity-agnostic session client: every callback is routed through the
     * current [SessionHost] (or dropped when the UI is gone), so one client
     * stays valid across activity recreations.
     */
    private fun createDefaultSessionClient(): TermuxTerminalSessionClientBase {
        return object : TermuxTerminalSessionClientBase() {
            override fun onFrameAvailable(changedSession: TerminalSession) {
                val host = hostRef?.get() ?: return
                if (!host.isAtLeast(Lifecycle.State.STARTED)) return
                host.onFrameAvailable(changedSession)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                hostRef?.get()?.copyToClipboard(text)
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val host = hostRef?.get() ?: return
                host.handlePaste(session)
            }

            override fun onTerminalProtocolNotification(
                session: TerminalSession,
                title: String?,
                body: String?,
            ) {
                val location = locateSession(session)
                handleTerminalNotification(title, body, location?.serverId, location?.tabId)
            }

            override fun onTerminalProgressChanged(session: TerminalSession) {
                terminalProgress.update(session)
            }

            override fun onTerminalProgramStatusChanged(session: TerminalSession) {
                programStatus.update(session)
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalProgress.remove(finishedSession)
                programStatus.remove(finishedSession)
                unbindTerminalSession(finishedSession)
            }
        }
    }

    companion object {
        /** Intent extra: originating server id for a tapped terminal notification. */
        const val EXTRA_NOTIFICATION_SERVER_ID = "termux.terminal.notification.server_id"
        /** Intent extra: originating tab id for a tapped terminal notification. */
        const val EXTRA_NOTIFICATION_TAB_ID = "termux.terminal.notification.tab_id"

        @Volatile
        private var instance: AppSessionManager? = null

        fun init(context: Context) {
            if (instance == null) {
                instance = AppSessionManager(context.applicationContext)
            }
        }

        val current: AppSessionManager?
            get() = instance
    }
}
