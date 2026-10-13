package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;

/** The {@link Class} that implements special buttons for the extra keys toolbar. */
public class SpecialButton {

    public static final SpecialButton CTRL = new SpecialButton("CTRL");
    public static final SpecialButton ALT = new SpecialButton("ALT");
    public static final SpecialButton SHIFT = new SpecialButton("SHIFT");
    public static final SpecialButton FN = new SpecialButton("FN");

    /** The special button key. */
    private final String key;

    /**
     * Initialize a {@link SpecialButton}.
     *
     * @param key The unique key name for the special button.
     */
    public SpecialButton(@NonNull final String key) {
        this.key = key;
    }

    @NonNull
    @Override
    public String toString() {
        return key;
    }

}
