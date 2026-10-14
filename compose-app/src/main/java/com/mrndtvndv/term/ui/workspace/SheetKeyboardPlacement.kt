package com.mrndtvndv.term.ui.workspace

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Keeps a bottom sheet above the IME, matching the Herdr sheet. ModalBottomSheet runs in its own
 * dialog window, so the window needs FLAG_ALT_FOCUSABLE_IM for the keyboard to stay visible
 * under the sheet instead of covering it.
 */
@Composable
internal fun KeepSheetAboveIme() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.findSheetDialogWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }
}

private fun View.findSheetDialogWindow(): Window? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent as? View
    }
    return null
}
