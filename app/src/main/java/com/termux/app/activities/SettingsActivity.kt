package com.termux.app.activities

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.app.settings.ui.SettingsScreen
import com.termux.app.ui.TermuxTheme
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.termux.shell.am.TermuxAmSocketServer
import com.termux.shared.theme.NightMode

class SettingsActivity : AppCompatActivity() {

    private var runTermuxAmSocketServerOnCreate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true)

        runTermuxAmSocketServerOnCreate = TermuxSettingsRepository.get().current().runTermuxAmSocketServer

        enableEdgeToEdge()
        setContentView(ComposeView(this).apply {
            setContent { TermuxTheme { SettingsScreen(onBack = ::finish) } }
        })
    }

    override fun onStop() {
        super.onStop()

        if (TermuxSettingsRepository.get().current().runTermuxAmSocketServer != runTermuxAmSocketServerOnCreate) {
            TermuxAmSocketServer.updateState(this)
        }
    }
}
