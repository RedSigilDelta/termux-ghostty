package com.termux.app.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.settings.ui.categories.aboutSettings
import com.termux.app.settings.ui.categories.appearanceSettings
import com.termux.app.settings.ui.categories.debuggingSettings
import com.termux.app.settings.ui.categories.integrationsSettings
import com.termux.app.settings.ui.categories.keyboardSettings
import com.termux.app.settings.ui.categories.linksSettings
import com.termux.app.settings.ui.categories.sessionsSettings
import com.termux.app.settings.ui.categories.shortcutsSettings
import com.termux.app.settings.ui.categories.terminalSettings
import com.termux.app.settings.ui.categories.wallpaperSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(category: SettingsCategory, state: SettingsState, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(category.title)) },
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
            when (category) {
                SettingsCategory.APPEARANCE -> appearanceSettings(state)
                SettingsCategory.WALLPAPER -> wallpaperSettings(state)
                SettingsCategory.TERMINAL -> terminalSettings(state)
                SettingsCategory.SESSIONS -> sessionsSettings(state)
                SettingsCategory.KEYBOARD -> keyboardSettings(state)
                SettingsCategory.SHORTCUTS -> shortcutsSettings(state)
                SettingsCategory.LINKS -> linksSettings(state)
                SettingsCategory.INTEGRATIONS -> integrationsSettings(state)
                SettingsCategory.DEBUGGING -> debuggingSettings(state)
                SettingsCategory.ABOUT -> aboutSettings(state)
            }
        }
    }
}
