package com.termux.terminal;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads which process group owns a pty from /proc. Does blocking file IO, so call it off the main thread. */
final class ForegroundProcess {

    private static final Set<String> INTERPRETERS = new HashSet<>(Arrays.asList(
        "node", "nodejs", "python", "python3", "ruby", "perl"));
    private static final Set<String> LOADER_OPTIONS_WITH_VALUE = new HashSet<>(Arrays.asList(
        "--library-path", "--preload", "--audit", "--argv0"));

    private static final Set<String> GENERIC_DIRS = new HashSet<>(Arrays.asList(
        "versions", "version", "bin", "current", "latest"));
    private static final Pattern VERSION = Pattern.compile("v?\\d+(\\.\\d+)*([-+.][\\w.]+)?");

    private ForegroundProcess() {}

    /**
     * The command name of the terminal's foreground process group leader, or the shell's own name
     * when the shell is in the foreground. Null if /proc can't be read.
     */
    @Nullable
    static String name(int shellPid) {
        String stat = read("/proc/" + shellPid + "/stat");
        if (stat == null) return null;
        int foregroundGroup = foregroundGroup(stat);
        int pid = foregroundGroup > 0 ? foregroundGroup : shellPid;
        String command = commandName(read("/proc/" + pid + "/cmdline"));
        if (command != null) return command;
        String comm = read("/proc/" + pid + "/comm");
        return comm != null ? comm : read("/proc/" + shellPid + "/comm");
    }

    /** The tpgid field of a /proc/<pid>/stat line, or -1 if it can't be parsed. */
    static int foregroundGroup(String stat) {
        // The comm field is parenthesised and may contain spaces, so parse after the last ')'.
        // Remaining fields: state ppid pgrp session tty_nr tpgid ...
        int commEnd = stat.lastIndexOf(')');
        if (commEnd < 0) return -1;
        String[] fields = stat.substring(commEnd + 1).trim().split(" ");
        if (fields.length < 6) return -1;
        try {
            return Integer.parseInt(fields[5]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The program a /proc/<pid>/cmdline runs. For interpreters and the dynamic loader it is the
     * script or binary they were given, so "node /usr/bin/claude" is "claude".
     */
    @Nullable
    static String commandName(@Nullable String cmdline) {
        if (cmdline == null) return null;
        String[] argv = cmdline.split("\u0000");
        String program = baseName(argv[0]);
        if (program.isEmpty()) return null;
        boolean loader = program.startsWith("ld-") || program.startsWith("ld.so");
        if (!loader && !INTERPRETERS.contains(program)) return program;
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.startsWith("-")) {
                if (loader && LOADER_OPTIONS_WITH_VALUE.contains(arg)) i++;
                continue;
            }
            String script = baseName(arg);
            int extension = script.lastIndexOf('.');
            return extension > 0 ? script.substring(0, extension) : script;
        }
        return program;
    }

    private static String baseName(String path) {
        String[] parts = path.split("/");
        // Versioned installs run a file named after the version, like claude/versions/2.1.296.
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (part.isEmpty() || VERSION.matcher(part).matches() || GENERIC_DIRS.contains(part)) continue;
            // Login shells are started as "-bash".
            return part.startsWith("-") ? part.substring(1) : part;
        }
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    @Nullable
    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
