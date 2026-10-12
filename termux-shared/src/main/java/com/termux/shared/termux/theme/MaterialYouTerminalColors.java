package com.termux.shared.termux.theme;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.Contrast;
import com.google.android.material.color.utilities.Hct;
import com.termux.shared.theme.ThemeUtils;

import java.util.Locale;
import java.util.Properties;

/** Generates terminal colors from the active Material 3 theme palette. */
public final class MaterialYouTerminalColors {

    /** WCAG AA for body text, which is how terminal glyphs are read. */
    private static final double MIN_TEXT_CONTRAST = 4.5;
    /** Normal-intensity ink tone on light surfaces, where the container roles are near-white. */
    private static final double LIGHT_INK_TONE = 40;
    /** Secondary text on light surfaces. Herdr also writes it on the slot 8 row background. */
    private static final double LIGHT_SECONDARY_TONE = 24;
    /**
     * Herdr paints active rows with slot 8 and writes default-foreground text on them. This tone keeps
     * that text at 4.5:1 while still reading as muted text on the surface.
     */
    private static final double LIGHT_BRIGHT_BLACK_TONE = 56;

    private static final int DEFAULT_LIGHT_SURFACE = 0xFFFFFBFE;
    private static final int DEFAULT_DARK_SURFACE = 0xFF1C1B1F;
    private static final int DEFAULT_LIGHT_ON_SURFACE = 0xFF1C1B1F;
    private static final int DEFAULT_DARK_ON_SURFACE = 0xFFE6E1E5;
    private static final int DEFAULT_LIGHT_ERROR = 0xFFB3261E;
    private static final int DEFAULT_DARK_ERROR = 0xFFF2B8B5;
    private static final int DEFAULT_LIGHT_PRIMARY = 0xFF6750A4;
    private static final int DEFAULT_DARK_PRIMARY = 0xFFD0BCFF;
    private static final int DEFAULT_LIGHT_SECONDARY = 0xFF625B71;
    private static final int DEFAULT_DARK_SECONDARY = 0xFFCCC2DC;
    private static final int DEFAULT_LIGHT_TERTIARY = 0xFF7D5260;
    private static final int DEFAULT_DARK_TERTIARY = 0xFFEFB8C8;

    private MaterialYouTerminalColors() {}

    @NonNull
    public static Properties generate(@NonNull Context context) {
        boolean isDarkTheme = ThemeUtils.isNightModeEnabled(context);

        int surface = resolveColor(context, com.google.android.material.R.attr.colorSurface,
            isDarkTheme ? DEFAULT_DARK_SURFACE : DEFAULT_LIGHT_SURFACE);
        int onSurface = resolveColor(context, com.google.android.material.R.attr.colorOnSurface,
            isDarkTheme ? DEFAULT_DARK_ON_SURFACE : DEFAULT_LIGHT_ON_SURFACE);
        int onSurfaceVariant = resolveColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant,
            shiftTone(onSurface, isDarkTheme ? 80 : 35));
        int outline = resolveColor(context, com.google.android.material.R.attr.colorOutline,
            shiftTone(onSurface, isDarkTheme ? 60 : 55));
        int surfaceContainerHighest = resolveColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest,
            shiftTone(surface, isDarkTheme ? 24 : 88));

        int error = resolveColor(context, com.google.android.material.R.attr.colorError,
            isDarkTheme ? DEFAULT_DARK_ERROR : DEFAULT_LIGHT_ERROR);
        int errorContainer = resolveColor(context, com.google.android.material.R.attr.colorErrorContainer,
            shiftTone(error, isDarkTheme ? 30 : 90));

        int primary = resolveColor(context, com.google.android.material.R.attr.colorPrimary,
            isDarkTheme ? DEFAULT_DARK_PRIMARY : DEFAULT_LIGHT_PRIMARY);
        int primaryContainer = resolveColor(context, com.google.android.material.R.attr.colorPrimaryContainer,
            shiftTone(primary, isDarkTheme ? 30 : 90));

        int secondary = resolveColor(context, com.google.android.material.R.attr.colorSecondary,
            isDarkTheme ? DEFAULT_DARK_SECONDARY : DEFAULT_LIGHT_SECONDARY);
        int secondaryContainer = resolveColor(context, com.google.android.material.R.attr.colorSecondaryContainer,
            shiftTone(secondary, isDarkTheme ? 30 : 90));

        int tertiary = resolveColor(context, com.google.android.material.R.attr.colorTertiary,
            isDarkTheme ? DEFAULT_DARK_TERTIARY : DEFAULT_LIGHT_TERTIARY);
        int tertiaryContainer = resolveColor(context, com.google.android.material.R.attr.colorTertiaryContainer,
            shiftTone(tertiary, isDarkTheme ? 30 : 90));

        return toTerminalProperties(isDarkTheme, surface, onSurface, onSurfaceVariant, outline,
            surfaceContainerHighest, error, errorContainer, primary, primaryContainer,
            secondary, secondaryContainer, tertiary, tertiaryContainer);
    }

    /**
     * Maps Material roles onto the 16 ANSI slots. On light surfaces the chromatic slots are rebuilt
     * so each one clears {@link #MIN_TEXT_CONTRAST} against the background. Slots 7 and 8 are darkened
     * for secondary text and highlight rows. Slots 0 and 15 are unchanged.
     */
    @SuppressLint("RestrictedApi")
    @NonNull
    public static Properties toTerminalProperties(boolean isDarkTheme, int surface, int onSurface,
                                                  int onSurfaceVariant, int outline, int surfaceContainerHighest,
                                                  int error, int errorContainer, int primary, int primaryContainer,
                                                  int secondary, int secondaryContainer, int tertiary,
                                                  int tertiaryContainer) {
        // Lightest tone that still clears the minimum contrast on this surface.
        double brightInkTone = Contrast.darker(Hct.fromInt(surface).getTone(), MIN_TEXT_CONTRAST);

        Properties properties = new Properties();
        putColor(properties, "foreground", onSurface);
        putColor(properties, "background", surface);
        putColor(properties, "cursor", primary);
        putColor(properties, "color0", surfaceContainerHighest);
        putColor(properties, "color1", error);
        putColor(properties, "color2", tertiary);
        putColor(properties, "color3", isDarkTheme ? primaryContainer : shiftTone(primaryContainer, LIGHT_INK_TONE));
        putColor(properties, "color4", primary);
        putColor(properties, "color5", secondary);
        putColor(properties, "color6", isDarkTheme ? tertiaryContainer : shiftTone(tertiaryContainer, LIGHT_INK_TONE));
        putColor(properties, "color7", isDarkTheme ? onSurfaceVariant : shiftTone(onSurfaceVariant, LIGHT_SECONDARY_TONE));
        putColor(properties, "color8", isDarkTheme ? outline : shiftTone(outline, LIGHT_BRIGHT_BLACK_TONE));
        putColor(properties, "color9", isDarkTheme ? errorContainer : shiftTone(error, brightInkTone));
        putColor(properties, "color10", shiftTone(tertiary, isDarkTheme ? 88 : 28));
        putColor(properties, "color11", shiftTone(primary, isDarkTheme ? 88 : 28));
        putColor(properties, "color12", isDarkTheme ? primaryContainer : shiftTone(primary, brightInkTone));
        putColor(properties, "color13", isDarkTheme ? secondaryContainer : shiftTone(secondary, brightInkTone));
        putColor(properties, "color14", shiftTone(tertiaryContainer, isDarkTheme ? 92 : 24));
        putColor(properties, "color15", onSurface);
        return properties;
    }

    private static int resolveColor(@NonNull Context context, int attr, int fallback) {
        return ThemeUtils.getSystemAttrColor(context, attr, fallback);
    }

    @SuppressLint("RestrictedApi")
    private static int shiftTone(int color, double tone) {
        Hct hct = Hct.fromInt(color);
        hct.setTone(tone);
        return hct.toInt();
    }

    private static void putColor(@NonNull Properties properties, @NonNull String key, int color) {
        properties.setProperty(key, toTerminalColor(color));
    }

    @NonNull
    private static String toTerminalColor(int color) {
        return String.format(Locale.US, "#%02x%02x%02x", (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
    }

}
