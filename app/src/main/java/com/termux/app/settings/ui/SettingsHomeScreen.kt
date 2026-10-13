package com.termux.app.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.settings.ConfigErrorsDialog
import com.termux.shared.termux.TermuxConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(state: SettingsState, onBack: () -> Unit, onOpen: (SettingsCategory) -> Unit) {
    var showErrors by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_activity_termux_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
            ),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val problemsRow: SettingRowContent? = if (state.effective.errors.isNotEmpty()) {
                { shapes ->
                    ActionRow(
                        title = stringResource(R.string.settings_file_problems, state.effective.errors.size),
                        shapes = shapes,
                        onClick = { showErrors = true },
                    )
                }
            } else {
                null
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_config_file),
                    rows = listOfNotNull(
                        { shapes ->
                            ActionRow(
                                title = TermuxConstants.TERMUX_SETTINGS_FILE_PATH,
                                shapes = shapes,
                                summary = stringResource(R.string.settings_file_keys_count, state.effective.fileKeys.size),
                            )
                        },
                        problemsRow,
                        { shapes ->
                            ActionRow(
                                title = stringResource(R.string.settings_reload_config),
                                shapes = shapes,
                                onClick = { state.repository.reload() },
                            )
                        },
                    ),
                )
            }
            item {
                SettingsGroup(
                    rows = SettingsCategory.entries.map { category ->
                        { shapes -> CategoryRow(category, shapes, onOpen) }
                    },
                )
            }
        }

        if (showErrors) {
            ConfigErrorsDialog(state.effective.errors) { showErrors = false }
        }
    }
}

@Composable
private fun CategoryRow(category: SettingsCategory, shapes: ListItemShapes, onOpen: (SettingsCategory) -> Unit) {
    SegmentedListItem(
        onClick = { onOpen(category) },
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        supportingContent = { Text(stringResource(category.summary)) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
    ) {
        Text(stringResource(category.title))
    }
}
