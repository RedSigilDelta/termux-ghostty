package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.app.settings.TermuxSettings;
import com.termux.app.settings.TermuxSettingsRepository;

import java.util.Locale;

@Keep
public class GhosttyFeaturesPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(GhosttyFeaturesPreferencesDataStore.getInstance());

        setPreferencesFromResource(R.xml.termux_ghostty_features_preferences, rootKey);

        setupRestartPromptListeners();
    }

    private void setupRestartPromptListeners() {
        String[] restartKeys = {
            "use-session-tabs",
            "session-tab-bar-position",
            "session-tab-bar-align",
            "remember-soft-keyboard-state",
            "unconditional-soft-keyboard-on-tap",
            "terminal-onclick-url-open",
            "terminal-onclick-url-open-when-mouse-tracking-active",
            "material-you-theme"
        };

        for (String key : restartKeys) {
            androidx.preference.Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    showRestartDialog();
                    return true;
                });
            }
        }
    }

    private void showRestartDialog() {
        Context context = getContext();
        if (context == null) return;

        new androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Restart Required")
            .setMessage("This settings change requires a restart of Termux to take effect.")
            .setPositiveButton("Restart Now", (dialog, which) -> {
                restartTermux(context);
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void restartTermux(@NonNull Context context) {
        // Cleanly stop the service
        Intent stopServiceIntent = new Intent(context, com.termux.app.TermuxService.class);
        stopServiceIntent.setAction(com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
        context.startService(stopServiceIntent);

        // Relaunch the app
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launchIntent);

            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.finishAffinity();
            }

            System.exit(0);
        }
    }

}

class GhosttyFeaturesPreferencesDataStore extends PreferenceDataStore {

    private static GhosttyFeaturesPreferencesDataStore mInstance;

    public static synchronized GhosttyFeaturesPreferencesDataStore getInstance() {
        if (mInstance == null) {
            mInstance = new GhosttyFeaturesPreferencesDataStore();
        }
        return mInstance;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (key == null) return;

        TermuxSettingsRepository.get().set(key, value);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (key == null) return defValue;

        TermuxSettings s = TermuxSettingsRepository.get().current();
        switch (key) {
            case "use-session-tabs":
                return s.getUseSessionTabs();
            case "remember-soft-keyboard-state":
                return s.getRememberSoftKeyboardState();
            case "terminal-onclick-url-open":
                return s.getTerminalOnclickUrlOpen();
            case "terminal-onclick-url-open-when-mouse-tracking-active":
                return s.getTerminalOnclickUrlOpenWhenMouseTrackingActive();
            case "unconditional-soft-keyboard-on-tap":
                return s.getUnconditionalSoftKeyboardOnTap();
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (key == null) return;

        TermuxSettingsRepository.get().set(key, value != null ? value : "");
    }

    @Override
    public String getString(String key, String defValue) {
        if (key == null) return defValue;

        TermuxSettings s = TermuxSettingsRepository.get().current();
        switch (key) {
            case "session-tab-bar-position":
                return s.getSessionTabBarPosition().name().toLowerCase(Locale.ROOT);
            case "session-tab-bar-align":
                return s.getSessionTabBarAlign().name().toLowerCase(Locale.ROOT);
            case "material-you-theme":
                return s.getMaterialYouTheme().getValue();
            default:
                return defValue;
        }
    }

}
