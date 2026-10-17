package com.mrndtvndv.term.ui.settings

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.defaultKeyBarLayout
import com.mrndtvndv.term.navigation.KeyBarItemStart
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun KeyBarEditorRoute(
    settingsViewModel: SettingsViewModel,
    onCreateCustom: (KeyBarItemStart) -> Unit,
    onEditItem: (String) -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun showSnackbar(message: String, onUndo: (() -> Unit)? = null) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (onUndo == null) null else "Undo",
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }

    KeyBarEditorScreen(
        items = settings.keyBarLayout.items,
        snackbarHostState = snackbarHostState,
        callbacks = KeyBarEditorCallbacks(
            onEdit = onEditItem,
            onCreateCustom = onCreateCustom,
            onMove = { from, to ->
                settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withItemMoved(from, to)) }
            },
            onDelete = { item, index ->
                settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withoutItem(item.id)) }
                showSnackbar("Removed ${item.label}") {
                    settingsViewModel.update {
                        it.copy(keyBarLayout = it.keyBarLayout.withItemInserted(index, item))
                    }
                }
            },
            onReset = {
                val previousLayout = settingsViewModel.settings.value.keyBarLayout
                settingsViewModel.update { it.copy(keyBarLayout = defaultKeyBarLayout()) }
                showSnackbar("Key bar reset") {
                    settingsViewModel.update { it.copy(keyBarLayout = previousLayout) }
                }
            },
            onAddAction = { action ->
                val item = KeyBarItem(
                    id = UUID.randomUUID().toString(),
                    label = action.defaultLabel()!!,
                    tap = action,
                )
                val currentLayout = settingsViewModel.settings.value.keyBarLayout
                if (currentLayout.items.size >= KeyBarLimits.MaxItems) {
                    null
                } else {
                    settingsViewModel.update {
                        it.copy(keyBarLayout = it.keyBarLayout.withItemSaved(item))
                    }
                    AddedKeyBarItem(item.id, item.label)
                }
            },
            onUndoAdded = { id ->
                settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withoutItem(id)) }
            },
            onFull = { showSnackbar("The key bar holds up to ${KeyBarLimits.MaxItems} keys") },
            onBack = onBack,
        ),
    )
}
