package com.termux.terminal;

import java.util.Objects;

/**
 * One item of ordered keyboard input. Literal text is written as given. A special key is encoded by the Ghostty
 * worker from the terminal's live cursor, keypad and Kitty modes, after output already queued for parsing.
 */
public final class TerminalInputItem {

    final String text;
    final int keyCode;
    final int keyModifiers;

    private TerminalInputItem(String text, int keyCode, int keyModifiers) {
        this.text = text;
        this.keyCode = keyCode;
        this.keyModifiers = keyModifiers;
    }

    /** Literal text, written as UTF-8 with no encoding applied. */
    public static TerminalInputItem text(String text) {
        return new TerminalInputItem(Objects.requireNonNull(text), 0, 0);
    }

    /** A special key from {@link KeyHandler}. {@code keyModifiers} takes the {@code KeyHandler.KEYMOD_*} bits. */
    public static TerminalInputItem key(int keyCode, int keyModifiers) {
        return new TerminalInputItem(null, keyCode, keyModifiers);
    }

    boolean isKey() {
        return text == null;
    }
}
