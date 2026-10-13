package com.termux.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.settings.TabBarAlign
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession

data class SessionItem(
    val session: TerminalSession,
    val name: String?,
    val title: String?,
    val running: Boolean,
    val exitStatus: Int,
)

fun interface SessionListener {
    fun onSessionEvent(session: TerminalSession, longPress: Boolean)
}

fun interface NewSessionListener {
    fun onNewSession(failsafe: Boolean)
}

class SessionsUiState {
    var sessions by mutableStateOf<List<SessionItem>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(-1)
        private set
    var backgroundColor by mutableIntStateOf(0)
    var scrollRequest by mutableIntStateOf(0)
        private set

    fun refresh(sessions: List<TermuxSession>, current: TerminalSession?) {
        this.sessions = sessions.mapNotNull { termuxSession ->
            termuxSession.terminalSession?.let {
                SessionItem(it, it.mSessionName, it.title, it.isRunning, it.exitStatus)
            }
        }
        currentIndex = sessions.indexOfFirst { current != null && it.terminalSession == current }
    }

    fun requestScrollToCurrent() {
        scrollRequest++
    }
}

@Composable
fun SessionDrawerList(state: SessionsUiState, listener: SessionListener) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.scrollRequest) {
        if (state.currentIndex in state.sessions.indices) listState.animateScrollToItem(state.currentIndex)
    }
    LazyColumn(state = listState) {
        itemsIndexed(state.sessions) { index, item ->
            SessionDrawerRow(index, item, index == state.currentIndex, listener)
        }
    }
}

@Composable
private fun SessionDrawerRow(index: Int, item: SessionItem, selected: Boolean, listener: SessionListener) {
    val colorScheme = MaterialTheme.colorScheme
    val name = item.name.orEmpty()
    val subtitle = item.title
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("[${index + 1}] $name") }
        if (!subtitle.isNullOrEmpty()) {
            if (name.isNotEmpty()) append("\n")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(subtitle) }
        }
    }
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { listener.onSessionEvent(item.session, false) },
                onLongClick = { listener.onSessionEvent(item.session, true) },
            )
            .padding(6.dp),
        color = if (!item.running && item.exitStatus != 0) colorScheme.error else colorScheme.onSurface,
        fontSize = 14.sp,
        textDecoration = if (item.running) null else TextDecoration.LineThrough,
    )
}

@Composable
fun SessionTabStrip(
    state: SessionsUiState,
    align: TabBarAlign,
    listener: SessionListener,
    newListener: NewSessionListener,
) {
    val indices = state.sessions.indices.toList()
    val tabs = if (align == TabBarAlign.RIGHT) indices.reversed() else indices
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        val position = tabs.indexOf(state.currentIndex)
        if (position >= 0) listState.animateScrollToItem(position)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Color(state.backgroundColor)),
        horizontalArrangement = when (align) {
            TabBarAlign.LEFT -> Arrangement.Start
            TabBarAlign.CENTER -> Arrangement.Center
            TabBarAlign.RIGHT -> Arrangement.End
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs) { index ->
            SessionTab(index, state.sessions[index], index == state.currentIndex, listener)
        }
        item {
            NewSessionTab(newListener)
        }
    }
}

@Composable
private fun SessionTab(index: Int, item: SessionItem, selected: Boolean, listener: SessionListener) {
    val colorScheme = MaterialTheme.colorScheme
    val name = item.name
    val label = if (name.isNullOrEmpty()) "[${index + 1}]" else "[${index + 1}] $name"
    StripTab(
        text = label,
        color = when {
            selected -> colorScheme.onPrimaryContainer
            !item.running && item.exitStatus != 0 -> colorScheme.error
            else -> colorScheme.onSurface
        },
        background = if (selected) colorScheme.primaryContainer else Color.Transparent,
        bold = selected,
        strikeThrough = !item.running,
        onClick = { listener.onSessionEvent(item.session, false) },
        onLongClick = { listener.onSessionEvent(item.session, true) },
    )
}

@Composable
private fun NewSessionTab(listener: NewSessionListener) {
    StripTab(
        text = "+",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        background = Color.Transparent,
        bold = false,
        strikeThrough = false,
        fontSize = 15.sp,
        onClick = { listener.onNewSession(false) },
        onLongClick = { listener.onNewSession(true) },
    )
}

@Composable
private fun StripTab(
    text: String,
    color: Color,
    background: Color,
    bold: Boolean,
    strikeThrough: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    fontSize: TextUnit = 13.sp,
) {
    Text(
        text = text,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = color,
        fontSize = fontSize,
        fontWeight = if (bold) FontWeight.Bold else null,
        textDecoration = if (strikeThrough) TextDecoration.LineThrough else null,
        maxLines = 1,
    )
}
