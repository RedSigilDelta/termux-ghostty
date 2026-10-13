package com.termux.shared.termux.terminal;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.theme.MaterialYouTerminalColors;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.terminal.compose.TerminalComposeView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Terminal appearance shared by every host that renders a {@link TerminalComposeView}.
 *
 * <p>Hosts resolve the session background colour here and paint their own surfaces with it.
 */
public final class TermuxTerminalStyling {

    private static final String LOG_TAG = "TermuxTerminalStyling";

    private TermuxTerminalStyling() {
    }

    /**
     * Resolves the background colour of the displayed session.
     *
     * <p>Falls back to the global colour scheme when there is no live session, so a
     * host never reads a background colour from a session whose backend is gone.
     */
    public static int resolveBackgroundColor(@Nullable TerminalSession session) {
        if (session != null && session.hasActiveTerminalBackend()) return session.getBackgroundColor();
        return TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

    /**
     * Loads the colour scheme and terminal typeface, then applies both.
     *
     * <p>Callers own error reporting so a host can keep its own log context.
     *
     * @return true when the colour scheme and typeface were applied.
     */
    public static boolean applyTerminalStyling(@NonNull Context context,
                                               boolean isMaterialYou,
                                               @NonNull TerminalComposeView terminalView,
                                               @Nullable TerminalSession session) {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            Properties colorProperties = new Properties();
            if (isMaterialYou) {
                colorProperties.putAll(MaterialYouTerminalColors.generate(context));
            } else if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    colorProperties.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(colorProperties);
            if (session != null) session.reloadColorScheme();

            terminalView.setTypeface(
                fontFile.exists() && fontFile.length() > 0 ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply terminal styling", e);
            return false;
        }
    }
}
