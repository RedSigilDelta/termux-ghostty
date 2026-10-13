package com.termux.shared.termux.theme;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.theme.NightMode;

public class TermuxThemeUtils {

    public static final String MATERIAL_YOU_THEME_DISABLED = "disabled";
    public static final String MATERIAL_YOU_THEME_LIGHT = "light";
    public static final String MATERIAL_YOU_THEME_DARK = "dark";
    public static final String MATERIAL_YOU_THEME_BLACK = "black";
    public static final String MATERIAL_YOU_THEME_SYSTEM = "system";

    /** Set name as app wide night mode value. */
    public static void setAppNightMode(@Nullable String name) {
        NightMode.setAppNightMode(name);
    }

    public static boolean isMaterialYouThemeEnabled(@Nullable String materialYouTheme) {
        if (materialYouTheme == null) return false;
        return !MATERIAL_YOU_THEME_DISABLED.equals(materialYouTheme);
    }

    @NonNull
    public static String getAppNightMode(@Nullable String nightMode, @Nullable String materialYouTheme) {
        if (!isMaterialYouThemeEnabled(materialYouTheme))
            return NightMode.modeOf(nightMode, NightMode.SYSTEM).getName();

        if (MATERIAL_YOU_THEME_LIGHT.equals(materialYouTheme))
            return NightMode.FALSE.getName();
        if (MATERIAL_YOU_THEME_DARK.equals(materialYouTheme))
            return NightMode.TRUE.getName();
        if (MATERIAL_YOU_THEME_BLACK.equals(materialYouTheme))
            return NightMode.TRUE.getName();
        if (MATERIAL_YOU_THEME_SYSTEM.equals(materialYouTheme))
            return NightMode.SYSTEM.getName();

        return NightMode.modeOf(nightMode, NightMode.SYSTEM).getName();
    }

}
