package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One OSC 7501 program status record. Built on the worker thread from the native snapshot and
 * published as an immutable list. The id is empty for the root record.
 */
public final class ProgramStatusRecord {

    private static final String RECORD_SEPARATOR = "\u001e";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final int FIELD_COUNT = 8;

    public final String id;
    public final String state;
    public final String kind;
    public final int progress;
    public final long updated;
    public final String app;
    public final String title;
    public final String msg;

    private ProgramStatusRecord(String id, String state, String kind, int progress, long updated, String app, String title, String msg) {
        this.id = id;
        this.state = state;
        this.kind = kind;
        this.progress = progress;
        this.updated = updated;
        this.app = app;
        this.title = title;
        this.msg = msg;
    }

    /** Parses the native snapshot: state, kind, progress, updated, id, app, title, msg per record. */
    static List<ProgramStatusRecord> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProgramStatusRecord> records = new ArrayList<>();
        for (String entry : snapshot.split(RECORD_SEPARATOR, -1)) {
            String[] fields = entry.split(FIELD_SEPARATOR, -1);
            if (fields.length != FIELD_COUNT) {
                continue;
            }
            records.add(new ProgramStatusRecord(
                fields[4],
                fields[0],
                emptyToNull(fields[1]),
                parseProgress(fields[2]),
                parseUpdated(fields[3]),
                emptyToNull(fields[5]),
                emptyToNull(fields[6]),
                emptyToNull(fields[7])));
        }
        return Collections.unmodifiableList(records);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static int parseProgress(String value) {
        if (value.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static long parseUpdated(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }
}
