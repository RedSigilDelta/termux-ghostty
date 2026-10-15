package com.mrndtvndv.term.herdr

/** Tabs of one remote workspace in remote presentation order. */
fun HerdrSnapshot.tabsIn(workspaceId: String): List<HerdrTab> =
    tabs.filter { it.workspaceId == workspaceId }.sortedBy { it.number }

/** Panes of one remote tab in wire order. */
fun HerdrSnapshot.panesIn(tabId: String): List<HerdrPane> =
    panes.filter { it.tabId == tabId }

/** Pane bound to a stable terminal identity. Prefer this over the public pane ID across moves. */
fun HerdrSnapshot.paneForTerminal(terminalId: String): HerdrPane? =
    panes.firstOrNull { it.terminalId == terminalId }

fun HerdrSnapshot.layoutFor(tabId: String): HerdrLayout? =
    layouts.firstOrNull { it.tabId == tabId }

fun HerdrSnapshot.workspaceById(id: String): HerdrWorkspace? =
    workspaces.firstOrNull { it.id == id }
