package com.mrndtvndv.term.herdr

/** Connection and cache state of one Herdr control client. Immutable snapshot published via StateFlow. */
data class HerdrState(
    val phase: HerdrPhase = HerdrPhase.Idle,
    /** From `ping`. Null when ping is unsupported or not answered yet. */
    val server: HerdrServerInfo? = null,
    /** Last successful `session.snapshot`. May be stale; see [snapshotFresh]. */
    val snapshot: HerdrSnapshot? = null,
    /**
     * True only while the topology subscription is live and no invalidation is pending. It does not cover agent
     * status; see [agentStatusFresh].
     */
    val snapshotFresh: Boolean = false,
    /** Latest failure. Null while [HerdrPhase.Live]. */
    val error: HerdrError? = null,
    /** Consecutive failed attempts since the last Live state. */
    val attempt: Int = 0,
    /**
     * Wall-clock millis when the snapshot that supplied the statuses in [snapshot] completed. Null before the
     * first snapshot. Kept as last-known across disconnects, so a UI can show "as of" time.
     */
    val agentStatusSampledAtMillis: Long? = null,
    /**
     * True only while Live with at least one completed snapshot on this connection. Statuses are SAMPLED
     * (one `session.snapshot` per [HerdrControlClient.STATUS_SAMPLE_INTERVAL_MILLIS] while idle), not pushed.
     * A status change can therefore be up to one interval plus one request late. False means last-known.
     */
    val agentStatusFresh: Boolean = false,
    /** Optional topology kinds the server rejected. Those kinds are not streamed. */
    val optionalEventsUnsupported: Set<String> = emptySet(),
    /**
     * Invalidation generation the client had reached when it requested [snapshot]. A snapshot whose generation is at
     * least a caller's [HerdrControlClient.awaitFreshSnapshot] token was fetched after that caller's invalidation.
     */
    val snapshotGeneration: Long = 0,
)

enum class HerdrPhase { Idle, Connecting, Live, Reconnecting, Unsupported, Failed, Closed }

enum class HerdrErrorKind { Transport, Protocol, Disconnected, EventsLost, Timeout, Rejected, Unsupported }

data class HerdrError(
    val kind: HerdrErrorKind,
    val message: String,
    /** Upstream error code when the server answered with an error body. */
    val serverCode: String? = null,
)

data class HerdrServerInfo(
    val version: String,
    val protocol: Long,
    val capabilities: HerdrCapabilities?,
)

data class HerdrCapabilities(
    val liveHandoff: Boolean,
    val detachedServerDaemon: Boolean,
    val endpointProtocolGeneration: Long?,
    val surfaceInterest: Boolean,
    val healthCheck: Boolean,
    val sshAgentRegistration: Boolean,
)

data class HerdrSnapshot(
    val version: String,
    val protocol: Long,
    /** Shared snapshot focus; not an identity-bound focus for this app's attached UI client. */
    val focusedWorkspaceId: String?,
    val focusedTabId: String?,
    val focusedPaneId: String?,
    val workspaces: List<HerdrWorkspace>,
    val tabs: List<HerdrTab>,
    val panes: List<HerdrPane>,
    val layouts: List<HerdrLayout>,
    val agents: List<HerdrAgent>,
)

data class HerdrWorkspace(
    val id: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val paneCount: Int,
    val tabCount: Int,
    val activeTabId: String,
    val agentStatus: HerdrAgentStatus,
    val tokens: Map<String, String>,
    val worktree: HerdrWorktree?,
)

data class HerdrWorktree(
    val repoKey: String,
    val repoName: String,
    val repoRoot: String,
    val checkoutPath: String,
    val isLinkedWorktree: Boolean,
)

data class HerdrTab(
    val id: String,
    val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val paneCount: Int,
    val agentStatus: HerdrAgentStatus,
)

data class HerdrPane(
    /** Public pane ID. Can change when the pane moves. */
    val id: String,
    /** Stable terminal identity. Prefer this for matching and attachment. */
    val terminalId: String,
    val workspaceId: String,
    val tabId: String,
    val focused: Boolean,
    val cwd: String?,
    val foregroundCwd: String?,
    val restoreError: String?,
    val label: String?,
    val agent: String?,
    val title: String?,
    val terminalTitle: String?,
    val displayAgent: String?,
    val agentStatus: HerdrAgentStatus,
    val stateLabels: Map<String, String>,
    val tokens: Map<String, String>,
    val scroll: HerdrScroll?,
    val revision: Long,
)

data class HerdrAgent(
    val terminalId: String,
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val focused: Boolean,
    val name: String?,
    val agent: String?,
    val title: String?,
    val displayAgent: String?,
    val agentStatus: HerdrAgentStatus,
    val cwd: String?,
    val foregroundCwd: String?,
    val revision: Long,
)

data class HerdrLayout(
    val workspaceId: String,
    val tabId: String,
    val zoomed: Boolean,
    val focusedPaneId: String,
    val area: HerdrRect,
    val panes: List<HerdrLayoutPane>,
    val splits: List<HerdrLayoutSplit>,
)

data class HerdrLayoutPane(
    val paneId: String,
    val focused: Boolean,
    val rect: HerdrRect,
)

data class HerdrLayoutSplit(
    val id: String,
    val direction: HerdrSplitDirection,
    val ratio: Float,
    val rect: HerdrRect,
)

data class HerdrRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class HerdrScroll(
    val offsetFromBottom: Long,
    val maxOffsetFromBottom: Long,
    val viewportRows: Long,
)

/** Unknown wire values map to [Unknown] instead of failing the snapshot. */
enum class HerdrAgentStatus { Idle, Working, Blocked, Done, Unknown }

enum class HerdrSplitDirection { Right, Down, Unknown }

/**
 * Derives [HerdrState.agentStatusFresh] from the phase and the sample time. Applied on every publish,
 * so the flag cannot drift from the facts it summarizes.
 */
internal fun HerdrState.withFreshness(): HerdrState =
    copy(agentStatusFresh = phase == HerdrPhase.Live && agentStatusSampledAtMillis != null)

/** Outcome of [HerdrControlClient.awaitFreshSnapshot]. */
sealed interface HerdrFreshSnapshot {
    /** [snapshot] was fetched after the caller's invalidation. [state] is the published state that carried it. */
    data class Ready(val state: HerdrState, val snapshot: HerdrSnapshot) : HerdrFreshSnapshot

    data class Unavailable(val error: HerdrError) : HerdrFreshSnapshot
}
