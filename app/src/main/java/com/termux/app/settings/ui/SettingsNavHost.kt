package com.termux.app.settings.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import kotlinx.serialization.Serializable

@Serializable
sealed interface SettingsRoute : NavKey {
    @Serializable data object Home : SettingsRoute
    @Serializable data class Category(val category: SettingsCategory) : SettingsRoute
}

@Composable
fun SettingsNavHost(state: SettingsState, onExit: () -> Unit) {
    val backStack = rememberNavBackStack(SettingsRoute.Home)
    // NavDisplay keeps its entries for the life of the back stack, so entries read the latest state from here.
    val latestState by rememberUpdatedState(state)
    val animations = latestState.settings.animations
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else onExit() },
        transitionSpec = if (animations) defaultTransitionSpec() else { { EnterTransition.None togetherWith ExitTransition.None } },
        popTransitionSpec = if (animations) defaultPopTransitionSpec() else { { EnterTransition.None togetherWith ExitTransition.None } },
        predictivePopTransitionSpec = if (animations) defaultPredictivePopTransitionSpec() else { _ -> EnterTransition.None togetherWith ExitTransition.None },
        entryProvider = entryProvider<NavKey> {
            entry<SettingsRoute.Home> {
                SettingsHomeScreen(latestState, onBack = onExit, onOpen = { backStack.add(SettingsRoute.Category(it)) })
            }
            entry<SettingsRoute.Category> { key ->
                SettingsCategoryScreen(key.category, latestState, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}
