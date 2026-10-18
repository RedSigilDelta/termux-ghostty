package com.mrndtvndv.term.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.components.FloatingPill
import com.mrndtvndv.term.ui.components.PillActions
import com.mrndtvndv.term.ui.components.PillDivider
import com.mrndtvndv.term.ui.components.PillIconButton
import com.mrndtvndv.term.ui.components.PillSearchInput

@Suppress("LongParameterList")
@Composable
internal fun DiffPane(
    state: ReviewScreenState,
    search: DiffSearch,
    showFullFileToggle: Boolean,
    onToggleFullFileMode: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleWordDiff: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tabsButton: (@Composable () -> Unit)? = null
) {
    BackHandler(enabled = search.isVisible) { onCloseSearch() }
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // No Haze source here: recording the WebView into a blur layer makes it flash.
        DiffViewer(
            hasSelection = state.selectedFile != null || state.selectedCommit != null,
            diffContent = state.diffContent,
            showLineNumbers = state.showLineNumbers,
            isWordDiffEnabled = state.isWordDiffEnabled,
            search = search
        )
        DiffActionPill(
            state = state,
            search = search,
            showFullFileToggle = showFullFileToggle,
            onToggleFullFileMode = onToggleFullFileMode,
            onToggleLineNumbers = onToggleLineNumbers,
            onToggleWordDiff = onToggleWordDiff,
            onCloseSearch = onCloseSearch,
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            trailing = tabsButton
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun DiffActionPill(
    state: ReviewScreenState,
    search: DiffSearch,
    showFullFileToggle: Boolean,
    onToggleFullFileMode: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleWordDiff: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    FloatingPill(
        state = search.isVisible,
        hazeState = null,
        modifier = modifier,
        label = "DiffActionPill",
        trailing = trailing
    ) { active ->
        if (active) {
            DiffSearchRow(search = search, onClose = onCloseSearch)
        } else {
            DiffActions(
                state = state,
                showFullFileToggle = showFullFileToggle,
                onBack = onBack,
                onOpenSearch = search::open,
                onToggleWordDiff = onToggleWordDiff,
                onToggleLineNumbers = onToggleLineNumbers,
                onToggleFullFileMode = onToggleFullFileMode
            )
        }
    }
}

@Composable
private fun DiffActions(
    state: ReviewScreenState,
    showFullFileToggle: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleWordDiff: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleFullFileMode: () -> Unit
) {
    PillActions {
        PillIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        PillDivider()
        PillIconButton(Icons.Default.Search, "Search diff", onOpenSearch)
        PillIconButton(
            Icons.AutoMirrored.Filled.CompareArrows,
            if (state.isWordDiffEnabled) "Disable word-level diff" else "Enable word-level diff",
            onToggleWordDiff,
            tint = if (state.isWordDiffEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        PillIconButton(
            Icons.Default.FormatListNumbered,
            "Toggle line numbers",
            onToggleLineNumbers,
            tint = if (state.showLineNumbers) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (showFullFileToggle) {
            PillIconButton(
                if (state.isFullFileMode) Icons.Default.Visibility else Icons.Default.UnfoldMore,
                "Toggle full file mode",
                onToggleFullFileMode,
                tint = if (state.isFullFileMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun DiffSearchRow(search: DiffSearch, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Close search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PillSearchInput(
            query = search.query,
            onQueryChange = search::updateQuery,
            placeholder = "Search diff…",
            onSearch = { search.moveMatch(1) },
            modifier = Modifier.weight(1f)
        )

        DiffMatchControls(search)
    }
}

@Composable
private fun DiffMatchControls(search: DiffSearch) {
    val matchLabel = when {
        search.query.isEmpty() -> null
        search.matchCount == 0 -> "No matches"
        else -> "${search.matchIndex + 1}/${search.matchCount}"
    }

    matchLabel?.let { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    IconButton(
        onClick = { search.moveMatch(-1) },
        enabled = search.matchCount > 0,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Previous match",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    IconButton(
        onClick = { search.moveMatch(1) },
        enabled = search.matchCount > 0,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "Next match",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
