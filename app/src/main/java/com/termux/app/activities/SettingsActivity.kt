package com.termux.app.activities

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.termux.R
import com.termux.app.settings.TermuxFiles
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.app.settings.ui.SettingsNavHost
import com.termux.app.settings.ui.rememberSettingsState
import com.termux.app.ui.TermuxTheme
import com.termux.shared.activity.media.AppCompatActivityUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.am.TermuxAmSocketServer
import com.termux.shared.theme.NightMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private var runTermuxAmSocketServerOnCreate = false

    private var fileRevision by mutableIntStateOf(0)

    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyPickedFile(it, TermuxFiles.TERMUX_WALLPAPER_FILE, R.string.settings_wallpaper_set_toast) }
    }

    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyPickedFile(it, TermuxFiles.fontFile, R.string.settings_font_set_toast) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true)

        val settings = TermuxSettingsRepository.get().current()
        runTermuxAmSocketServerOnCreate = settings.runTermuxAmSocketServer
        if (!settings.animations) overrideTransition(OVERRIDE_TRANSITION_OPEN)

        enableEdgeToEdge()
        // AppCompatActivity.setContentView skips this, and NavDisplay needs the NavigationEventDispatcherOwner it installs.
        initializeViewTreeOwners()
        setContent {
            val fontStamp = remember(fileRevision) { TermuxFiles.fontFile.lastModified() }
            TermuxTheme(fontStamp = fontStamp) {
                SettingsNavHost(
                    state = rememberSettingsState(
                        fileRevision = fileRevision,
                        pickWallpaper = { pickWallpaper.launch("image/*") },
                        pickFont = { pickFont.launch("*/*") },
                        removeWallpaper = ::removeWallpaper,
                        resetFont = ::resetFont,
                    ),
                    onExit = ::finish,
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        if (!TermuxSettingsRepository.get().current().animations) overrideTransition(OVERRIDE_TRANSITION_CLOSE)
    }

    private fun overrideTransition(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(type, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    private fun copyPickedFile(uri: Uri, target: File, successMessage: Int) {
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    TermuxConstants.TERMUX_DATA_HOME_DIR.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }.getOrNull() != null
            }
            fileRevision++
            Toast.makeText(
                this@SettingsActivity,
                if (copied) successMessage else R.string.settings_file_copy_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun removeWallpaper() = deleteFile(TermuxFiles.TERMUX_WALLPAPER_FILE)

    private fun resetFont() = deleteFile(TermuxFiles.fontFile)

    private fun deleteFile(file: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            fileRevision++
        }
    }

    override fun onStop() {
        super.onStop()

        if (TermuxSettingsRepository.get().current().runTermuxAmSocketServer != runTermuxAmSocketServerOnCreate) {
            TermuxAmSocketServer.updateState(this)
        }
    }
}
