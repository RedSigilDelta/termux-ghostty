package com.termux.terminal;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Parses the working directory a shell reports with OSC 7. */
final class ReportedCwd {

    private ReportedCwd() {}

    /** Returns the absolute path of a {@code file://} or {@code kitty-shell-cwd://} URL, or null. */
    @Nullable
    static String parse(@Nullable String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("/")) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        String scheme = url.substring(0, schemeEnd);
        if (!"file".equals(scheme) && !"kitty-shell-cwd".equals(scheme)) {
            return null;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return null;
        }
        return percentDecode(url.substring(pathStart));
    }

    @Nullable
    private static String percentDecode(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '%') {
                if (i + 2 >= text.length()) {
                    return null;
                }
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes.write((high << 4) | low);
                i += 3;
            } else {
                int end = i;
                while (end < text.length() && text.charAt(end) != '%') {
                    end++;
                }
                byte[] raw = text.substring(i, end).getBytes(StandardCharsets.UTF_8);
                bytes.write(raw, 0, raw.length);
                i = end;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
