package com.mrndtvndv.term.server

import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrWorkspaceNode

/**
 * Last herdr workspace tree per server, kept for the life of the process. A reopened sheet
 * paints this immediately while the refresh runs. Lives outside the ViewModel so it survives
 * activity close; it is not written to disk, because a tree from a dead process would mislead.
 */
class HerdrWorkspaceCache {
    private val workspacesByServer = mutableMapOf<String, List<HerdrWorkspaceNode>>()

    fun get(serverId: String): List<HerdrWorkspaceNode> = workspacesByServer[serverId].orEmpty()

    fun put(serverId: String, workspaces: List<HerdrWorkspaceNode>) {
        workspacesByServer[serverId] = workspaces
    }
}
