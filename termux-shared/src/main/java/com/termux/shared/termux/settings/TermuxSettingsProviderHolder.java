package com.termux.shared.termux.settings;

import androidx.annotation.Nullable;

/**
 * Holds the {@link TermuxSettingsProvider} for termux-shared.
 *
 * <p>The app installs it in Application.onCreate(). Callers must handle null.
 */
public final class TermuxSettingsProviderHolder {

    private static volatile TermuxSettingsProvider sProvider;

    private TermuxSettingsProviderHolder() {
    }

    public static void set(@Nullable TermuxSettingsProvider provider) {
        sProvider = provider;
    }

    @Nullable
    public static TermuxSettingsProvider get() {
        return sProvider;
    }

}
