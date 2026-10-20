package com.mrndtvndv.term.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable
enum class SettingsCategory(val title: String, val summary: String, val icon: ImageVector) {
    APPEARANCE("Appearance", "Theme, AMOLED black, animations, frame rate", Icons.Outlined.Palette),
    WALLPAPER("Wallpaper", "Background image, opacity, scaling", Icons.Outlined.Wallpaper),
    TERMINAL("Terminal", "Font size, custom font, cursor trail, resize debounce", Icons.Outlined.Terminal),
    KEYBOARD("Keyboard", "Soft keyboard behaviour and key bar", Icons.Outlined.Keyboard),
    DEBUGGING("Debugging", "Performance HUD and native crash logs", Icons.Outlined.BugReport),
}
