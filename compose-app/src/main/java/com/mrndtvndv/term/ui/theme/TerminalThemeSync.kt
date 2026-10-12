package com.mrndtvndv.term.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.termux.shared.termux.theme.MaterialYouTerminalColors
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession

@Composable
fun TerminalThemeSync(
    termSession: TerminalSession?,
    appTheme: String
) {
    val currentThemeScheme = MaterialTheme.colorScheme
    val isThemeDark = !appTheme.equals("Light", ignoreCase = true)

    LaunchedEffect(currentThemeScheme, termSession) {
        val properties = MaterialYouTerminalColors.toTerminalProperties(
            isThemeDark,
            currentThemeScheme.surface.toArgb(),
            currentThemeScheme.onSurface.toArgb(),
            currentThemeScheme.onSurfaceVariant.toArgb(),
            currentThemeScheme.outline.toArgb(),
            currentThemeScheme.surfaceContainerHighest.toArgb(),
            currentThemeScheme.error.toArgb(),
            currentThemeScheme.errorContainer.toArgb(),
            currentThemeScheme.primary.toArgb(),
            currentThemeScheme.primaryContainer.toArgb(),
            currentThemeScheme.secondary.toArgb(),
            currentThemeScheme.secondaryContainer.toArgb(),
            currentThemeScheme.tertiary.toArgb(),
            currentThemeScheme.tertiaryContainer.toArgb()
        )

        TerminalColors.COLOR_SCHEME.updateWith(properties)
        termSession?.reloadColorScheme()
    }
}
