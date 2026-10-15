package com.termux.terminal;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

public final class GhosttyNative {

    static final int APPEND_RESULT_SCREEN_CHANGED = 1;
    static final int APPEND_RESULT_CURSOR_CHANGED = 1 << 1;
    static final int APPEND_RESULT_TITLE_CHANGED = 1 << 2;
    static final int APPEND_RESULT_BELL = 1 << 3;
    static final int APPEND_RESULT_CLIPBOARD_COPY = 1 << 4;
    static final int APPEND_RESULT_COLORS_CHANGED = 1 << 5;
    static final int APPEND_RESULT_REPLY_BYTES_AVAILABLE = 1 << 6;
    static final int APPEND_RESULT_DESKTOP_NOTIFICATION = 1 << 7;
    static final int APPEND_RESULT_PROGRESS = 1 << 8;
    static final int APPEND_RESULT_PROGRAM_STATUS = 1 << 9;
    static final int APPEND_RESULT_PWD_CHANGED = 1 << 10;

    static final int MODE_CURSOR_KEYS_APPLICATION = 1;
    static final int MODE_KEYPAD_APPLICATION = 1 << 1;
    static final int MODE_MOUSE_TRACKING = 1 << 2;
    static final int MODE_BRACKETED_PASTE = 1 << 3;
    static final int MODE_MOUSE_PROTOCOL_SGR = 1 << 4;
    static final int MODE_ALTERNATE_BUFFER = 1 << 5;

    static final int PROGRESS_STATE_NONE = 0;
    static final int PROGRESS_STATE_SET = 1;
    static final int PROGRESS_STATE_ERROR = 2;
    static final int PROGRESS_STATE_INDETERMINATE = 3;
    static final int PROGRESS_STATE_PAUSE = 4;

    static final int TRANSCRIPT_FLAG_JOIN_LINES = 1;
    static final int TRANSCRIPT_FLAG_TRIM = 1 << 1;

    static final int COMPRESSION_RESULT_UNSUPPORTED = 0;
    static final int COMPRESSION_RESULT_PENDING = 1;
    static final int COMPRESSION_RESULT_COMPLETE = 2;

    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("termux-ghostty");
            loaded = true;
            GhosttyLog.info("Loaded libtermux-ghostty.so");
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
            GhosttyLog.warn("Failed to load libtermux-ghostty.so", error);
        }
        LIBRARY_LOADED = loaded;
    }

    private GhosttyNative() {
    }

    static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    static native long nativeCreate(int columns, int rows, int transcriptRows, int cellWidthPixels, int cellHeightPixels);

    static native void nativeDestroy(long nativeHandle);

    static native void nativeReset(long nativeHandle);

    static native int nativeSetColorScheme(long nativeHandle, int[] colors);

    static native int nativeResize(long nativeHandle, int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    static native int nativeQueueMouseEvent(
        long nativeHandle,
        int action,
        int button,
        int modifiers,
        float surfaceX,
        float surfaceY,
        int screenWidthPx,
        int screenHeightPx,
        int cellWidthPx,
        int cellHeightPx,
        int paddingTopPx,
        int paddingRightPx,
        int paddingBottomPx,
        int paddingLeftPx
    );

    /** Report window focus to the terminal core; mode 1004 gates CSI I/O emission. */
    static native int nativeSetFocus(long nativeHandle, boolean focused);

    static native int nativeAppend(long nativeHandle, byte[] data, int offset, int length);

    static native long nativeGetCompressionActivity(long nativeHandle);

    static native int nativeCompressScrollback(long nativeHandle);

    @Nullable
    static native byte[] nativeCaptureStateSnapshot(long nativeHandle);

    static native int nativeRestoreStateSnapshot(long nativeHandle, byte[] snapshot);

    static native int nativeDrainPendingOutput(long nativeHandle, byte[] buffer, int offset, int length);

    static native int nativeSetViewportTopRow(long nativeHandle, int topRow);

    static native void nativeRequestFullSnapshotRefresh(long nativeHandle);

    static native int nativeFillSnapshotCurrentViewport(long nativeHandle, ByteBuffer buffer, int capacity);

    static native int nativeFillViewportLinks(long nativeHandle, ByteBuffer buffer, int capacity);

    static native int nativeFillSnapshot(long nativeHandle, int topRow, ByteBuffer buffer, int capacity);

    @Nullable
    static native String nativeConsumeTitle(long nativeHandle);

    @Nullable
    static native String nativeConsumeClipboardText(long nativeHandle);

    @Nullable
    static native String nativeConsumeReportedPwd(long nativeHandle);

    @Nullable
    static native String nativeConsumeNotificationTitle(long nativeHandle);

    @Nullable
    static native String nativeConsumeNotificationBody(long nativeHandle);

    static native int nativeGetProgressState(long nativeHandle);

    static native int nativeGetProgressValue(long nativeHandle);

    static native long nativeGetProgressGeneration(long nativeHandle);

    static native void nativeClearProgress(long nativeHandle);

    static native String nativeProgramStatusSnapshot(long nativeHandle);

    static native void nativeProgramStatusProcessEnded(long nativeHandle);

    static native int nativeGetColumns(long nativeHandle);

    static native int nativeGetRows(long nativeHandle);

    static native int nativeGetActiveRows(long nativeHandle);

    static native int nativeGetActiveTranscriptRows(long nativeHandle);

    static native int nativeGetModeBits(long nativeHandle);

    static native int nativeGetCursorRow(long nativeHandle);

    static native int nativeGetCursorCol(long nativeHandle);

    static native int nativeGetCursorStyle(long nativeHandle);

    static native boolean nativeIsCursorVisible(long nativeHandle);

    static native boolean nativeIsReverseVideo(long nativeHandle);

    static native boolean nativeIsAlternateBufferActive(long nativeHandle);

    @Nullable
    static native String nativeGetSelectedText(long nativeHandle, int startColumn, int startRow, int endColumn, int endRow, int flags);

    @Nullable
    static native String nativeGetWordAtLocation(long nativeHandle, int column, int row);

    @Nullable
    static native String nativeGetTranscriptText(long nativeHandle, int flags);

    public static native long nativeSshInit(int socketFd, String username, String passwordOrKey, boolean isPassword);
    public static native void nativeSshStart(long sessionHandle);
    public static native void nativeSshDeinit(long sessionHandle);
    /** False once the SSH connection has died or was torn down. */
    public static native boolean nativeSshIsConnected(long sessionHandle);
    /**
     * Starts a PTY channel running {@code command}, or the login shell when null. Returns an op handle, 0 when
     * the session is not running, or -1 when too many ops are pending (retryable). The loop opens the channel
     * step by step: poll {@link #nativeSshOpAwait}, then {@link #nativeSshOpTakeShell}, then
     * {@link #nativeSshOpRelease}.
     */
    public static native long nativeSshStartShell(long sessionHandle, String termType, int cols, int rows, String command);
    /** Closes and frees the shell; the handle must not be used afterwards. */
    public static native void nativeSshCloseShell(long sessionHandle, long shellHandle);
    /**
     * How the remote shell ended, from ShellEnd's native states: 0 running, 1 exited (see the code), 2 signaled
     * (see the signal name), 3 ended with no exit evidence, 4 transport lost, -1 when the handle is invalid.
     * Valid only while the shell handle is live, so query before {@link #nativeSshCloseShell}.
     */
    public static native int nativeSshShellExitState(long shellHandle);
    /** The server's exit status. Meaningful only when {@link #nativeSshShellExitState} is 1. */
    public static native int nativeSshShellExitCode(long shellHandle);
    /** The signal name without SIG, when the state is 2. Null otherwise. */
    public static native String nativeSshShellExitSignal(long shellHandle);
    public static native void nativeSshWrite(long shellHandle, byte[] data, int offset, int length);
    public static native void nativeSshResize(long shellHandle, int cols, int rows);
    public static native void nativeSshSetOutputCallback(long shellHandle, Object callback);
    public static native int nativeGetKittyKeyboardFlags(long terminalHandle);
    public static native boolean nativeSshIsRunning(long shellHandle);

    /**
     * Links a direct Unix-socket channel to {@code socketPath} (UTF-8, absolute, NUL-free). Returns the stream
     * handle, 0 when the session is no longer running or the path is invalid, or -1 when too many opens are
     * pending (retryable). The open completes on the native loop; poll {@link #nativeSshStreamAwaitOpen}.
     */
    public static native long nativeSshOpenStreamLocal(long sessionHandle, byte[] socketPath);
    /** Waits up to {@code timeoutMs}. Returns 1 when open, 0 while still opening, -1 when closed, -2 when failed. */
    public static native int nativeSshStreamAwaitOpen(long streamHandle, int timeoutMs);
    /**
     * Copies up to {@code length} inbound bytes into {@code buffer}. Returns the count, 0 when nothing
     * arrived within {@code timeoutMs}, -1 at EOF or after close, or -2 when the stream failed.
     */
    public static native int nativeSshStreamRead(long streamHandle, byte[] buffer, int offset, int length, int timeoutMs);
    /**
     * Queues up to {@code length} bytes in order. Returns the count accepted, 0 when no room opened up
     * within {@code timeoutMs}, -1 after close, or -2 when the stream failed or the peer closed it.
     * Accepted bytes are not yet written: call {@link #nativeSshStreamFlush} before treating them as sent.
     */
    public static native int nativeSshStreamWrite(long streamHandle, byte[] data, int offset, int length, int timeoutMs);
    /**
     * Waits up to {@code timeoutMs} until every byte already written has gone out through libssh2.
     * Returns 1 when drained, 0 on timeout, -1 when closed, -2 when failed or the peer closed first.
     */
    public static native int nativeSshStreamFlush(long streamHandle, int timeoutMs);
    /** Requests close. Wakes blocked readers and writers and never blocks on the native loop. Idempotent. */
    public static native void nativeSshStreamClose(long streamHandle);
    /** Drops the JVM's reference. Call once after {@link #nativeSshStreamClose}, with no call in flight. */
    public static native void nativeSshStreamRelease(long streamHandle);
    /** Failure reason after a -2 status: 1 denied, 2 unreachable path, 3 disconnected, 4 other. 0 when none. */
    public static native int nativeSshStreamFailure(long streamHandle);
    /** libssh2's message for the failure, or an empty string. */
    public static native String nativeSshStreamDetail(long streamHandle);

    /**
     * Starts {@code command} on a new exec channel. Returns an op handle, 0 when the session is not running,
     * or -1 when too many ops are pending (retryable).
     */
    public static native long nativeSshStartExec(long sessionHandle, String command);
    /**
     * Starts the SFTP subsystem. Returns an op handle, 0 when the session is not running, or -1 when too many
     * ops are pending (retryable).
     */
    public static native long nativeSshStartSftp(long sessionHandle);
    /**
     * Waits up to {@code timeoutMs}. Returns 1 when the op is done, -2 when it failed, and 0 while it is still
     * waiting. When {@code untilDone} is false, returns 2 once an exec command is running, so the caller can stop
     * bounding the wait by the open deadline and wait for the output with {@code untilDone} true.
     */
    public static native int nativeSshOpAwait(long opHandle, int timeoutMs, boolean untilDone);
    /** Moves the shell to the caller, who owns it from then on. Returns 0 when none is ready or it was taken. */
    public static native long nativeSshOpTakeShell(long opHandle);
    /** Moves the SFTP handle to the caller. Returns 0 when none is ready or it was taken. */
    public static native long nativeSshOpTakeSftp(long opHandle);
    /** Exit status of a finished exec op. */
    public static native int nativeSshOpExitStatus(long opHandle);
    /** Stdout collected by an exec op, as UTF-8. */
    public static native String nativeSshOpOutput(long opHandle);
    /** Stderr collected by an exec op, as UTF-8. */
    public static native String nativeSshOpErrorOutput(long opHandle);
    /** libssh2's message for a failed op, or an empty string. */
    public static native String nativeSshOpDetail(long opHandle);
    /**
     * Drops the caller's reference. Call once the op is done with, including after cancellation. Any shell or
     * SFTP handle that was never taken is closed by the loop. The handle must not be used afterwards.
     */
    public static native void nativeSshOpRelease(long opHandle);
    public static native void nativeSftpClose(long sessionHandle, long sftpHandle);
    public static native String nativeSftpListFiles(long sessionHandle, long sftpHandle, String path);
    public static native boolean nativeSftpMkdir(long sessionHandle, long sftpHandle, String path, int permissions);
    public static native boolean nativeSftpDelete(long sessionHandle, long sftpHandle, String path);
    public static native boolean nativeSftpRename(long sessionHandle, long sftpHandle, String oldPath, String newPath);
    public static native long nativeSftpFileOpen(long sessionHandle, long sftpHandle, String path, int flags, int mode);
    public static native void nativeSftpFileClose(long sessionHandle, long fileHandle);
    public static native int nativeSftpFileRead(long sessionHandle, long fileHandle, byte[] buffer, int offset, int length);
    public static native int nativeSftpFileWrite(long sessionHandle, long fileHandle, byte[] buffer, int offset, int length);
}
