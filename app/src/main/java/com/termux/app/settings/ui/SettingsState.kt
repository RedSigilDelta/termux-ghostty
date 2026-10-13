package com.termux.app.settings.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.termux.R
import com.termux.app.settings.EffectiveSettings
import com.termux.app.settings.TermuxFiles
import com.termux.app.settings.TermuxSettings
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.shared.android.PackageUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Availability(
    val api: TermuxAPIAppSharedPreferences?,
    val float: TermuxFloatAppSharedPreferences?,
    val tasker: TermuxTaskerAppSharedPreferences?,
    val widget: TermuxWidgetAppSharedPreferences?,
    val donate: Boolean,
)

class SettingsState(
    val context: Context,
    val repository: TermuxSettingsRepository,
    val effective: EffectiveSettings,
    val appPrefs: TermuxAppSharedPreferences?,
    val availability: Availability?,
    val wallpaperSet: Boolean,
    val fontSet: Boolean,
    val fileRevision: Int,
    val pickWallpaper: () -> Unit,
    val pickFont: () -> Unit,
    val removeWallpaper: () -> Unit,
    val resetFont: () -> Unit,
) {
    val settings: TermuxSettings = effective.settings

    fun isLocked(key: String) = key in effective.fileKeys

    fun save(transform: (TermuxSettings) -> TermuxSettings) {
        try {
            repository.update(transform)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, context.getString(R.string.settings_invalid_value, e.message), Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun rememberSettingsState(
    fileRevision: Int,
    pickWallpaper: () -> Unit,
    pickFont: () -> Unit,
    removeWallpaper: () -> Unit,
    resetFont: () -> Unit,
): SettingsState {
    val context = LocalContext.current
    val repository = TermuxSettingsRepository.get()
    val effective by repository.effective.collectAsState()
    val appPrefs: TermuxAppSharedPreferences? = remember { TermuxAppSharedPreferences.build(context, true) }
    val availability by produceState<Availability?>(null) {
        value = withContext(Dispatchers.IO) { loadAvailability(context) }
    }
    val wallpaperSet = remember(fileRevision) { TermuxFiles.TERMUX_WALLPAPER_FILE.isFile }
    val fontSet = remember(fileRevision) { TermuxFiles.fontFile.isFile }

    return SettingsState(
        context = context,
        repository = repository,
        effective = effective,
        appPrefs = appPrefs,
        availability = availability,
        wallpaperSet = wallpaperSet,
        fontSet = fontSet,
        fileRevision = fileRevision,
        pickWallpaper = pickWallpaper,
        pickFont = pickFont,
        removeWallpaper = removeWallpaper,
        resetFont = resetFont,
    )
}

private fun loadAvailability(context: Context) = Availability(
    api = TermuxAPIAppSharedPreferences.build(context, false),
    float = TermuxFloatAppSharedPreferences.build(context, false),
    tasker = TermuxTaskerAppSharedPreferences.build(context, false),
    widget = TermuxWidgetAppSharedPreferences.build(context, false),
    donate = isDonateVisible(context),
)

private fun isDonateVisible(context: Context): Boolean {
    val digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context) ?: return false
    // Play Store builds hide the donation link, per the Play Store donation policy.
    val apkRelease = TermuxUtils.getAPKRelease(digest)
    return apkRelease != null && apkRelease != TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST
}
