package com.termux.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.shared.termux.theme.TermuxThemeUtils

@Composable
fun TermuxTheme(content: @Composable () -> Unit) {
    val effective by TermuxSettingsRepository.get().effective.collectAsState()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val materialYou = TermuxThemeUtils.isMaterialYouThemeEnabled(effective.settings.materialYouTheme.value)
    val colorScheme = when {
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
