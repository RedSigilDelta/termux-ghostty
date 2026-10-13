package com.termux.app.terminal.io;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;
import com.termux.app.settings.TermuxSettings;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.compose.TerminalComposeView;

import org.json.JSONException;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";

    private static final String DEFAULT_EXTRA_KEYS_STYLE = "default";

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalComposeView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        reloadExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    public void reloadExtraKeys() {
        mExtraKeysInfo = loadExtraKeysInfo(mActivity, mActivity.getSettings());
    }

    public static ExtraKeysInfo loadExtraKeysInfo(@NonNull Context context, @NonNull TermuxSettings settings) {
        try {
            String extraKeys = settings.getExtraKeys().toString();
            String extraKeysStyle = settings.getExtraKeysStyle();

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !DEFAULT_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"extra-keys-style\" is invalid. Using default style instead.");
                extraKeysStyle = DEFAULT_EXTRA_KEYS_STYLE;
            }

            return new ExtraKeysInfo(extraKeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.showToast(context, "Could not load and set the \"extra-keys\" property from termux.toml: " + e, true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"extra-keys\" property from termux.toml: ", e);

            try {
                return new ExtraKeysInfo(new TermuxSettings().getExtraKeys().toString(),
                    DEFAULT_EXTRA_KEYS_STYLE,
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException defaultExtraKeysException) {
                Logger.showToast(context, "Can't create default extra keys", true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", defaultExtraKeysException);
                return null;
            }
        }
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @Override
    public void onTerminalExtraKeyButtonClick(String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            mActivity.getScreenState().toggleDrawer();
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            mActivity.getTerminalView().toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
