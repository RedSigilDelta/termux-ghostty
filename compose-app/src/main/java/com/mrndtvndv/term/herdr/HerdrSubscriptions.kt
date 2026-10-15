package com.mrndtvndv.term.herdr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Topology kinds the cache cannot work without. Upstream rejecting one of these makes the client
 * Unsupported. Each name is in `src/api/schema/events.rs` at both FETCH_HEAD and checked-out 0.9.1.
 */
internal val ESSENTIAL_TOPOLOGY_EVENTS = listOf(
    "workspace.created",
    "workspace.updated",
    "workspace.renamed",
    "workspace.moved",
    "workspace.closed",
    "workspace.focused",
    "tab.created",
    "tab.closed",
    "tab.focused",
    "tab.renamed",
    "tab.moved",
    "pane.created",
    "pane.closed",
    "pane.updated",
    "pane.focused",
    "pane.moved",
    "pane.exited",
    "pane.agent_detected",
    "layout.updated",
)

/**
 * Additions that refine the cache. A server that rejects one is retried without it, and the name
 * is reported in [HerdrState.optionalEventsUnsupported].
 */
internal val OPTIONAL_TOPOLOGY_EVENTS = listOf(
    "workspace.metadata_updated",
    "workspace.reordered",
)

/**
 * Pane-scoped status kind. The v1 client does not subscribe to it: each scoped subscription polls its
 * pane about every 100 ms upstream, so status is sampled with `session.snapshot` instead. The fake
 * server still models it so the reject-path behaviour stays covered.
 */
internal const val AgentStatusEvent: String = "pane.agent_status_changed"

internal fun topologySubscription(type: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type)))

/**
 * The name from an upstream `unknown variant` error, or null when the message has none. Serde
 * formats it as "unknown variant `name`, expected one of ...".
 */
internal fun unknownVariantName(message: String): String? {
    val prefix = "unknown variant `"
    val start = message.indexOf(prefix)
    if (start < 0) return null
    val nameStart = start + prefix.length
    val nameEnd = message.indexOf('`', nameStart)
    return if (nameEnd > nameStart) message.substring(nameStart, nameEnd) else null
}
