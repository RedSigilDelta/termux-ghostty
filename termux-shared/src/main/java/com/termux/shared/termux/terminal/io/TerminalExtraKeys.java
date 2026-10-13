package com.termux.shared.termux.terminal.io;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.termux.terminal.compose.TerminalInputSink;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;


public class TerminalExtraKeys {

    private final TerminalInputSink mTerminalInputSink;

    public TerminalExtraKeys(@NonNull TerminalInputSink terminalInputSink) {
        mTerminalInputSink = terminalInputSink;
    }

    protected void onTerminalExtraKeyButtonClick(String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if (PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
            Integer keyCode = PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
            if (keyCode == null) return;
            int metaState = 0;
            if (ctrlDown) metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            if (altDown) metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            if (fnDown) metaState |= KeyEvent.META_FUNCTION_ON;

            mTerminalInputSink.submitKey(keyCode, metaState);
        } else {
            int codePoint = key.length() == 1 ? key.codePointAt(0) : -1;
            if (codePoint != -1) {
                int finalCodePoint = codePoint;
                boolean isUpperCase = finalCodePoint >= 'A' && finalCodePoint <= 'Z';

                if (shiftDown) {
                    if (finalCodePoint >= 'a' && finalCodePoint <= 'z') {
                        finalCodePoint -= 32;
                        isUpperCase = true;
                    }
                }

                if (ctrlDown && isUpperCase && shiftDown) {
                    // Kitty Keyboard Protocol for Ctrl+Shift+Letter
                    int modifier = altDown ? 14 : 6;
                    String sequence = "\u001b[" + finalCodePoint + ";" + modifier + "u";
                    mTerminalInputSink.submitText(sequence);
                    return;
                }

                mTerminalInputSink.submitCodePoint(finalCodePoint, ctrlDown, altDown);
            } else {
                if (key.length() > 0) mTerminalInputSink.submitText(key);
            }
        }
    }

}
