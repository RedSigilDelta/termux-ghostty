package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.settings.categories.appearanceSettings
import com.mrndtvndv.term.ui.settings.categories.debuggingSettings
import com.mrndtvndv.term.ui.settings.categories.keyboardSettings
import com.mrndtvndv.term.ui.settings.categories.terminalSettings
import com.mrndtvndv.term.ui.settings.categories.wallpaperSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(category: SettingsCategory, state: SettingsState, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(category.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            when (category) {
                SettingsCategory.APPEARANCE -> appearanceSettings(state)
                SettingsCategory.WALLPAPER -> wallpaperSettings(state)
                SettingsCategory.TERMINAL -> terminalSettings(state)
                SettingsCategory.KEYBOARD -> keyboardSettings(state)
                SettingsCategory.DEBUGGING -> debuggingSettings(state)
            }
        }
    }
}
