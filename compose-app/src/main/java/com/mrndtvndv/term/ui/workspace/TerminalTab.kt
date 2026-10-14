@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.workspace.Tab
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.first

/**
 * A terminal tab. Opens the tab's shell when it becomes the active page and no terminal is
 * running for it. A finished terminal keeps its pane with a Restart button.
 */
@Suppress("LongParameterList")
@Composable
fun TerminalTab(
    serverId: String,
    tab: Tab.Terminal,
    entry: TerminalEntry?,
    isActive: Boolean,
    appViewModel: AppViewModel,
    workspaceViewModel: WorkspaceViewModel,
    settings: AppSettings,
    appearance: TerminalAppearance,
    paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks,
    tabsButton: (@Composable () -> Unit)?,
) {
    val opening = entry == null
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    val currentIsActive by rememberUpdatedState(isActive)

    // Waits for the page to become active so off-screen neighbours do not start shells.
    LaunchedEffect(tab.id, opening, attempt) {
        if (!opening) return@LaunchedEffect
        snapshotFlow { currentIsActive }.first { it }
        failure = null
        appViewModel.openTerminal(serverId, tab).onFailure { error ->
            failure = error.message ?: "Couldn't open a terminal"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (entry == null) {
            OpeningTerminal(failure = failure, onRetry = { attempt++ })
        } else {
            TerminalSessionPane(
                session = entry.session,
                isActive = isActive,
                settings = settings,
                appearance = appearance,
                workspaceViewModel = workspaceViewModel,
                tabsButton = tabsButton,
                callbacks = paneCallbacks(entry.session),
            )
            if (!entry.running) {
                Button(
                    onClick = { appViewModel.restartTerminal(serverId, tab) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Text("Restart")
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun TerminalSessionPane(
    session: TerminalSession,
    isActive: Boolean,
    settings: AppSettings,
    appearance: TerminalAppearance,
    workspaceViewModel: WorkspaceViewModel,
    tabsButton: (@Composable () -> Unit)?,
    callbacks: TerminalPaneCallbacks,
) {
    val progress by workspaceViewModel.observeTerminalProgress(session).collectAsStateWithLifecycle()
    val programStatus by workspaceViewModel.observeProgramStatus(session).collectAsStateWithLifecycle()
    TerminalPane(
        session = session,
        progress = progress,
        settings = settings,
        appearance = appearance,
        isActive = isActive,
        programStatus = programStatus,
        onProgramStatusOpened = { workspaceViewModel.markProgramStatusSeen(session) },
        tabsButton = tabsButton,
        callbacks = callbacks,
    )
}

@Composable
private fun OpeningTerminal(failure: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (failure == null) {
            LoadingIndicator()
        } else {
            Text(
                text = failure,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
