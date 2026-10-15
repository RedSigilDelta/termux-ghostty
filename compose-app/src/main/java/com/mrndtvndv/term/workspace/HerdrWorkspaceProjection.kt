package com.mrndtvndv.term.workspace

import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrPhase
import com.mrndtvndv.term.herdr.HerdrSnapshot
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.HerdrTab
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.herdr.tabsIn
import com.mrndtvndv.term.herdr.workspaceById
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State for a server without a control client, so composition never needs a nullable flow. */
val idleHerdrState: StateFlow<HerdrState> = MutableStateFlow(HerdrState()).asStateFlow()

/** Prefix of the in-memory tab id of a remote card. The rest of the id is the attached terminal's identity. */
const val RemoteCardPrefix = "herdr-terminal:"

/** Card id for a remote terminal. Keyed by terminal identity, because public tab numbers can be reused. */
fun remoteCardId(terminalId: String): String = RemoteCardPrefix + terminalId

/** Terminal id behind a remote card id, or null for a local tab id. */
fun remoteTerminalId(tabId: String): String? =
    if (tabId.startsWith(RemoteCardPrefix)) tabId.removePrefix(RemoteCardPrefix) else null

/**
 * One Herdr tab shown as a grouped card. [terminalId] is the pane the card attaches to; [paneTerminalIds] lists
 * every pane of the tab in wire order, for the local pane picker.
 */
data class RemoteCard(
    val tabId: String,
    val label: String,
    val terminalId: String,
    val paneTerminalIds: List<String>,
    val cwd: String?,
    val agentStatus: HerdrAgentStatus,
    /** False when [agentStatus] is last known: the control connection is not Live or statuses are not sampled. */
    val statusFresh: Boolean,
    /** When the status was sampled. Statuses are sampled, not pushed, so this is the honest "as of" time. */
    val statusSampledAtMillis: Long? = null,
)

sealed interface RemoteWorkspaceView {
    /** No cache snapshot yet, or the control connection never produced one. */
    data class Unavailable(val phase: HerdrPhase, val message: String?) : RemoteWorkspaceView

    /** The bound workspace is absent from a fresh, live snapshot. */
    data object Missing : RemoteWorkspaceView

    /** Cards from the last known snapshot. [stale] is true whenever the control connection is not Live. */
    data class Bound(val cards: List<RemoteCard>, val stale: Boolean) : RemoteWorkspaceView
}

/**
 * Cards for a bound workspace, derived from the control cache. Pure and never persisted.
 * [selectedTerminals] maps a tab id to the pane the user picked; the first pane is used otherwise.
 */
fun projectRemoteWorkspace(
    state: HerdrState,
    binding: HerdrWorkspaceBinding,
    selectedTerminals: Map<String, String> = emptyMap(),
): RemoteWorkspaceView {
    val snapshot = state.snapshot ?: return RemoteWorkspaceView.Unavailable(state.phase, state.error?.message)
    val live = state.phase == HerdrPhase.Live
    if (snapshot.workspaceById(binding.workspaceId) == null) {
        // Absence only counts as missing when the snapshot is authoritative. Otherwise keep the last known view.
        return if (live && state.snapshotFresh) {
            RemoteWorkspaceView.Missing
        } else {
            RemoteWorkspaceView.Bound(emptyList(), stale = true)
        }
    }
    val cards = snapshot.tabsIn(binding.workspaceId)
        .mapNotNull { tab ->
            cardFor(
                snapshot,
                tab,
                selectedTerminals,
                statusFresh = state.agentStatusFresh,
                statusSampledAtMillis = state.agentStatusSampledAtMillis,
            )
        }
        .filterNot { card -> card.paneTerminalIds.any { it in binding.hiddenTerminalIds } }
    return RemoteWorkspaceView.Bound(cards, stale = !live)
}

/**
 * Terminal ids whose local attachment must end. Only a fresh, live snapshot can end a terminal: a transport loss
 * keeps last-known topology as stale instead of ended.
 */
fun endedRemoteTerminals(attached: Set<String>, state: HerdrState): Set<String> {
    if (state.phase != HerdrPhase.Live || !state.snapshotFresh) return emptySet()
    val snapshot = state.snapshot ?: return emptySet()
    val live = snapshot.panes.mapTo(hashSetOf()) { it.terminalId }
    return attached - live
}

/**
 * Short status for a card subtitle. Idle and unknown add nothing, so the card stays quiet. A status that is not
 * fresh is labelled as last known, so a sampled value is never presented as live.
 */
fun remoteStatusLabel(status: HerdrAgentStatus, fresh: Boolean, sampledAtMillis: Long? = null): String? {
    val label = when (status) {
        HerdrAgentStatus.Working -> "Working"
        HerdrAgentStatus.Blocked -> "Needs input"
        HerdrAgentStatus.Done -> "Done"
        HerdrAgentStatus.Idle, HerdrAgentStatus.Unknown -> return null
    }
    if (fresh) return label
    val asOf = sampledAtMillis?.let { " · as of ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" }
    return "Last known: $label" + asOf.orEmpty()
}

/**
 * Herdr workspaces that are not yet bound to a local workspace, for the workspace menu. Snapshot facts only.
 */
fun unboundHerdrWorkspaces(state: HerdrState, boundIds: Set<String>): List<Pair<String, String>> =
    state.snapshot?.workspaces.orEmpty()
        .filter { it.id !in boundIds }
        .map { it.id to it.label.ifBlank { it.id } }

/**
 * Herdr control socket path for a server. Uses the configured absolute path when set. Otherwise it uses herdr's
 * default API socket under the server home, `~/.config/herdr/herdr.sock`. XDG_CONFIG_HOME on the server is not read,
 * so set the override when the server uses it.
 */
fun herdrControlSocketPath(override: String?, homeDir: String): String =
    override?.takeIf { it.startsWith("/") } ?: "${homeDir.trimEnd('/')}/.config/herdr/herdr.sock"

private fun cardFor(
    snapshot: HerdrSnapshot,
    tab: HerdrTab,
    selectedTerminals: Map<String, String>,
    statusFresh: Boolean,
    statusSampledAtMillis: Long?,
): RemoteCard? {
    val panes = snapshot.panesIn(tab.id)
    if (panes.isEmpty()) return null
    val selected = panes.firstOrNull { it.terminalId == selectedTerminals[tab.id] } ?: panes.first()
    return RemoteCard(
        tabId = tab.id,
        label = tab.label.ifBlank { "Tab ${tab.number}" },
        terminalId = selected.terminalId,
        paneTerminalIds = panes.map { it.terminalId },
        cwd = selected.cwd ?: selected.foregroundCwd,
        agentStatus = selected.agentStatus,
        statusFresh = statusFresh,
        statusSampledAtMillis = statusSampledAtMillis,
    )
}
