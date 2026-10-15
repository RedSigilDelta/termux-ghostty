package com.termux.terminal;

import androidx.annotation.Nullable;

/**
 * How a remote SSH shell ended. Built only from native facts (see GhosttyNative.nativeSshShellExitState),
 * never from text on the terminal. A session ends once: {@link Gate} admits the first end and rejects the rest.
 *
 * <p>Evidence rules: a nonzero exit status and a signal name are real exits. A zero status with no signal is
 * {@link Kind#ENDED_UNKNOWN}, because libssh2 1.11.1 cannot tell an unreported status from 0. Transport loss
 * is {@link Kind#TRANSPORT_LOST}. A local detach is {@link Kind#LOCAL_DETACH} and prints nothing.
 */
public final class ShellEnd {

    public enum Kind { EXITED, SIGNALED, ENDED_UNKNOWN, TRANSPORT_LOST, LOCAL_DETACH }

    /** What {@link TerminalSession#getExitStatus()} reports for a remote end with no exit code. */
    public static final int EXIT_STATUS_UNKNOWN = Integer.MIN_VALUE;

    /** Native shell states, mirrored from ssh_shell_exit.State in Zig. */
    static final int NATIVE_RUNNING = 0;
    static final int NATIVE_EXITED = 1;
    static final int NATIVE_SIGNALED = 2;
    static final int NATIVE_ENDED_UNKNOWN = 3;
    static final int NATIVE_TRANSPORT_LOST = 4;

    private final Kind mKind;
    private final int mExitCode;
    @Nullable
    private final String mSignalName;

    private ShellEnd(Kind kind, int exitCode, @Nullable String signalName) {
        mKind = kind;
        mExitCode = exitCode;
        mSignalName = signalName;
    }

    public static ShellEnd exited(int exitCode) {
        return exitCode == 0 ? endedUnknown() : new ShellEnd(Kind.EXITED, exitCode, null);
    }

    public static ShellEnd signaled(String signalName) {
        return signalName == null || signalName.isEmpty()
            ? endedUnknown()
            : new ShellEnd(Kind.SIGNALED, EXIT_STATUS_UNKNOWN, signalName);
    }

    public static ShellEnd endedUnknown() {
        return new ShellEnd(Kind.ENDED_UNKNOWN, EXIT_STATUS_UNKNOWN, null);
    }

    public static ShellEnd transportLost() {
        return new ShellEnd(Kind.TRANSPORT_LOST, EXIT_STATUS_UNKNOWN, null);
    }

    public static ShellEnd localDetach() {
        return new ShellEnd(Kind.LOCAL_DETACH, EXIT_STATUS_UNKNOWN, null);
    }

    /**
     * Maps the native facts. Any state other than an exit, a signal, or a transport loss, including a
     * running state or a handle that was already freed, is ENDED_UNKNOWN. It is never a success.
     */
    public static ShellEnd fromNative(int nativeState, int exitCode, @Nullable String signalName) {
        switch (nativeState) {
            case NATIVE_EXITED:
                return exited(exitCode);
            case NATIVE_SIGNALED:
                return signaled(signalName);
            case NATIVE_TRANSPORT_LOST:
                return transportLost();
            case NATIVE_RUNNING:
            case NATIVE_ENDED_UNKNOWN:
            default:
                return endedUnknown();
        }
    }

    /**
     * Like {@link #fromNative}, but null while the shell is still running or the handle is invalid, so a
     * running shell is never mistaken for an ended one.
     */
    @Nullable
    public static ShellEnd fromNativeIfEnded(int nativeState, int exitCode, @Nullable String signalName) {
        if (nativeState == NATIVE_RUNNING || nativeState < 0) {
            return null;
        }
        return fromNative(nativeState, exitCode, signalName);
    }

    /**
     * True for ends that describe the remote shell itself: an exit, a signal, or a close with no exit evidence.
     * Transport loss is not a remote exit.
     */
    public boolean isRemoteExit() {
        return mKind == Kind.EXITED || mKind == Kind.SIGNALED || mKind == Kind.ENDED_UNKNOWN;
    }

    /**
     * The end to record when the local side detaches. A remote exit that native state already holds is kept, so a
     * detach cannot mask it. Otherwise the detach is recorded and prints nothing.
     */
    public static ShellEnd forLocalDetach(@Nullable ShellEnd knownRemoteEnd) {
        return knownRemoteEnd != null && knownRemoteEnd.isRemoteExit() ? knownRemoteEnd : localDetach();
    }

    public Kind getKind() {
        return mKind;
    }

    /** The exit code for {@link Kind#EXITED}. {@link #EXIT_STATUS_UNKNOWN} for every other kind. */
    public int getExitCode() {
        return mExitCode;
    }

    /** The signal name without SIG, for {@link Kind#SIGNALED}. Null for every other kind. */
    @Nullable
    public String getSignalName() {
        return mSignalName;
    }

    /** The line printed into the terminal, or null when nothing should be printed (local detach). */
    @Nullable
    public String describe() {
        switch (mKind) {
            case EXITED:
                return "\r\n[Process completed (code " + mExitCode + ") - press Enter]";
            case SIGNALED:
                return "\r\n[Process completed (signal " + mSignalName + ") - press Enter]";
            case ENDED_UNKNOWN:
                return "\r\n[Remote shell ended; exit status unknown]";
            case TRANSPORT_LOST:
                return "\r\n[Disconnected]";
            case LOCAL_DETACH:
            default:
                return null;
        }
    }

    @Override
    public String toString() {
        return "ShellEnd{" + mKind + (mKind == Kind.EXITED ? " code=" + mExitCode : "")
            + (mKind == Kind.SIGNALED ? " signal=" + mSignalName : "") + "}";
    }

    /** Admits the first end of a session and rejects every later one. Thread-safe. */
    public static final class Gate {
        @Nullable
        private ShellEnd mEnd;

        /** Returns true only for the first end. Later calls, from any thread, return false. */
        public synchronized boolean tryAdmit(ShellEnd end) {
            if (mEnd != null) {
                return false;
            }
            mEnd = end;
            return true;
        }

        @Nullable
        public synchronized ShellEnd get() {
            return mEnd;
        }
    }
}
