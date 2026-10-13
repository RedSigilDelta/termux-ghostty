package com.termux.app.settings.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardCommandKey
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import com.termux.R
import kotlinx.serialization.Serializable

@Serializable
enum class SettingsCategory(@StringRes val title: Int, @StringRes val summary: Int, val icon: ImageVector) {
    APPEARANCE(R.string.settings_category_appearance, R.string.settings_category_appearance_summary, Icons.Outlined.Palette),
    WALLPAPER(R.string.settings_category_wallpaper, R.string.settings_category_wallpaper_summary, Icons.Outlined.Wallpaper),
    TERMINAL(R.string.settings_category_terminal, R.string.settings_category_terminal_summary, Icons.Outlined.Terminal),
    SESSIONS(R.string.settings_category_sessions, R.string.settings_category_sessions_summary, Icons.Outlined.Tab),
    KEYBOARD(R.string.settings_category_keyboard, R.string.settings_category_keyboard_summary, Icons.Outlined.Keyboard),
    SHORTCUTS(R.string.settings_category_shortcuts, R.string.settings_category_shortcuts_summary, Icons.Outlined.KeyboardCommandKey),
    LINKS(R.string.settings_category_links, R.string.settings_category_links_summary, Icons.Outlined.Link),
    INTEGRATIONS(R.string.settings_category_integrations, R.string.settings_category_integrations_summary, Icons.Outlined.Extension),
    DEBUGGING(R.string.settings_category_debugging, R.string.settings_category_debugging_summary, Icons.Outlined.BugReport),
    ABOUT(R.string.settings_category_about, R.string.settings_category_about_summary, Icons.Outlined.Info),
}
