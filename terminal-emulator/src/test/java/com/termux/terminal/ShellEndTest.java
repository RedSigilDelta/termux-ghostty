package com.termux.terminal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Contract tests for remote shell ends. They need no native library: native facts arrive as plain ints. */
public class ShellEndTest {

    @Test
    public void nonzeroExitStatusIsAnExitWithItsCode() {
        ShellEnd end = ShellEnd.fromNative(1, 7, null);
        assertEquals(ShellEnd.Kind.EXITED, end.getKind());
        assertEquals(7, end.getExitCode());
        assertNull(end.getSignalName());
    }

    @Test
    public void zeroStatusWithoutEvidenceIsUnknownNeverSuccess() {
        // libssh2 cannot tell an unreported status from 0, so a clean zero must not print or report success.
        ShellEnd end = ShellEnd.fromNative(1, 0, null);
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, end.getKind());
        assertEquals(ShellEnd.EXIT_STATUS_UNKNOWN, end.getExitCode());
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(3, 0, null).getKind());
    }

    @Test
    public void signalNameIsTheExitAndNeverHasACode() {
        ShellEnd end = ShellEnd.fromNative(2, 0, "HUP");
        assertEquals(ShellEnd.Kind.SIGNALED, end.getKind());
        assertEquals("HUP", end.getSignalName());
        assertEquals(ShellEnd.EXIT_STATUS_UNKNOWN, end.getExitCode());
    }

    @Test
    public void signalWithoutANameIsUnknownNotASignal() {
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(2, 0, null).getKind());
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(2, 0, "").getKind());
    }

    @Test
    public void transportLossIsDisconnectNotAnExit() {
        ShellEnd end = ShellEnd.fromNative(4, 0, null);
        assertEquals(ShellEnd.Kind.TRANSPORT_LOST, end.getKind());
        assertEquals(ShellEnd.EXIT_STATUS_UNKNOWN, end.getExitCode());
        assertEquals("\r\n[Disconnected]", end.describe());
    }

    @Test
    public void runningOrInvalidNativeStateIsUnknownNotAnExit() {
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(0, 0, null).getKind());
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(-1, 9, null).getKind());
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.fromNative(99, 9, null).getKind());
    }

    @Test
    public void nonzeroExitPrintsTheSameLineAsALocalProcessExit() {
        // Local PTY exits print "[Process completed (code N) - press Enter]". Remote exits match it exactly.
        assertEquals("\r\n[Process completed (code 3) - press Enter]", ShellEnd.exited(3).describe());
        assertEquals("\r\n[Process completed (signal HUP) - press Enter]", ShellEnd.signaled("HUP").describe());
        assertEquals("\r\n[Remote shell ended; exit status unknown]", ShellEnd.endedUnknown().describe());
    }

    @Test
    public void localDetachPrintsNothing() {
        ShellEnd detach = ShellEnd.localDetach();
        assertEquals(ShellEnd.Kind.LOCAL_DETACH, detach.getKind());
        assertNull(detach.describe());
    }

    @Test
    public void gateAdmitsOnlyTheFirstEnd() {
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertNull(gate.get());
        assertTrue(gate.tryAdmit(ShellEnd.transportLost()));
        assertFalse(gate.tryAdmit(ShellEnd.exited(5)));
        assertFalse(gate.tryAdmit(ShellEnd.localDetach()));
        assertEquals(ShellEnd.Kind.TRANSPORT_LOST, gate.get().getKind());
    }

    @Test
    public void duplicateCallbacksFromTheSameShellAdmitOneEnd() {
        // onNativeSshClosed and finishIfSshStopped both report the same closed shell. Only one may print.
        ShellEnd.Gate gate = new ShellEnd.Gate();
        List<ShellEnd> delivered = new ArrayList<>();
        for (int call = 0; call < 3; call++) {
            ShellEnd end = ShellEnd.fromNative(1, 2, null);
            if (gate.tryAdmit(end)) {
                delivered.add(end);
            }
        }
        assertEquals(1, delivered.size());
        assertEquals(2, delivered.get(0).getExitCode());
    }

    @Test
    public void disconnectThenLocalDetachKeepsTheDisconnectAndAddsNoLine() {
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.transportLost()));
        assertFalse(gate.tryAdmit(ShellEnd.localDetach()));
        assertEquals("\r\n[Disconnected]", gate.get().describe());
    }

    @Test
    public void localDetachThenLateRemoteEndIsSilent() {
        // The user detached locally; a later exit or disconnect callback must not add a line.
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.localDetach()));
        assertFalse(gate.tryAdmit(ShellEnd.fromNative(1, 4, null)));
        assertNull(gate.get().describe());
    }

    @Test
    public void unknownEndThenExitKeepsTheUnknownEnd() {
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.endedUnknown()));
        assertFalse(gate.tryAdmit(ShellEnd.fromNative(1, 1, null)));
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, gate.get().getKind());
    }

    @Test
    public void raceAcrossThreadsAdmitsExactlyOneEnd() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            ShellEnd.Gate gate = new ShellEnd.Gate();
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            List<Thread> workers = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                final ShellEnd end = index % 2 == 0 ? ShellEnd.transportLost() : ShellEnd.localDetach();
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (gate.tryAdmit(end)) {
                        admitted.incrementAndGet();
                    }
                });
                workers.add(worker);
                worker.start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join();
            }
            assertEquals("round " + round, 1, admitted.get());
            assertNotNull(gate.get());
        }
    }
    @Test
    public void nativeRunningOrInvalidHandleIsNotAnEnd() {
        // A running shell reports state 0. It must read as "nothing recorded", never as an unknown end.
        assertNull(ShellEnd.fromNativeIfEnded(0, 0, null));
        assertNull(ShellEnd.fromNativeIfEnded(-1, 0, null));
        assertEquals(ShellEnd.Kind.EXITED, ShellEnd.fromNativeIfEnded(1, 2, null).getKind());
        assertEquals(ShellEnd.Kind.TRANSPORT_LOST, ShellEnd.fromNativeIfEnded(4, 0, null).getKind());
    }

    @Test
    public void localDetachKeepsAKnownRemoteExit() {
        // The exit was recorded natively before the detach ran. The detach must not hide it.
        ShellEnd known = ShellEnd.fromNative(1, 3, null);
        ShellEnd resolved = ShellEnd.forLocalDetach(known);
        assertEquals(ShellEnd.Kind.EXITED, resolved.getKind());
        assertEquals(3, resolved.getExitCode());
        assertEquals("\r\n[Process completed (code 3) - press Enter]", resolved.describe());
    }

    @Test
    public void localDetachKeepsAKnownSignalAndUnknownEnd() {
        assertEquals("HUP", ShellEnd.forLocalDetach(ShellEnd.signaled("HUP")).getSignalName());
        assertEquals(ShellEnd.Kind.ENDED_UNKNOWN, ShellEnd.forLocalDetach(ShellEnd.endedUnknown()).getKind());
    }

    @Test
    public void localDetachIsSilentWhenNothingRemoteIsKnownOrOnlyTransportLoss() {
        assertEquals(ShellEnd.Kind.LOCAL_DETACH, ShellEnd.forLocalDetach(null).getKind());
        assertNull(ShellEnd.forLocalDetach(null).describe());
        // Transport loss is not a remote exit, so the detach stays silent.
        assertEquals(ShellEnd.Kind.LOCAL_DETACH, ShellEnd.forLocalDetach(ShellEnd.transportLost()).getKind());
    }

    @Test
    public void knownExitBeforeDetachIsAdmittedAndTheDetachIsRejected() {
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.forLocalDetach(ShellEnd.fromNative(1, 3, null))));
        assertFalse(gate.tryAdmit(ShellEnd.localDetach()));
        assertEquals(ShellEnd.Kind.EXITED, gate.get().getKind());
    }

    @Test
    public void lateSetSshShellAfterDetachAddsNoLine() {
        // Detach first, then the shell attaches late and reports an exit. The detach already ended the session.
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.localDetach()));
        assertFalse(gate.tryAdmit(ShellEnd.fromNative(1, 9, null)));
        assertNull(gate.get().describe());
    }

    @Test
    public void disconnectThenLateExitAddsNoSecondLine() {
        ShellEnd.Gate gate = new ShellEnd.Gate();
        assertTrue(gate.tryAdmit(ShellEnd.transportLost()));
        assertFalse(gate.tryAdmit(ShellEnd.fromNative(1, 9, null)));
        assertEquals("\r\n[Disconnected]", gate.get().describe());
    }

    @Test
    public void knownRemoteExitRecordedBeforeTheDetachPeekIsNeverMasked() throws InterruptedException {
        // The remote side records its end, then the detach reads native state. The read must see that end.
        for (int round = 0; round < 500; round++) {
            AtomicReference<ShellEnd> nativeState = new AtomicReference<>(null);
            ShellEnd.Gate gate = new ShellEnd.Gate();
            CountDownLatch recorded = new CountDownLatch(1);
            Thread remote = new Thread(() -> {
                nativeState.set(ShellEnd.fromNative(1, 4, null));
                recorded.countDown();
                gate.tryAdmit(nativeState.get());
            });
            final AtomicReference<ShellEnd> admitted = new AtomicReference<>(null);
            Thread detach = new Thread(() -> {
                try {
                    recorded.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                ShellEnd known = nativeState.get();
                if (gate.tryAdmit(ShellEnd.forLocalDetach(known))) {
                    admitted.set(gate.get());
                }
            });
            remote.start();
            detach.start();
            remote.join();
            detach.join();
            assertNotNull("round " + round, gate.get());
            assertEquals("round " + round, ShellEnd.Kind.EXITED, gate.get().getKind());
        }
    }

    @Test
    public void detachBeforeTheRemoteRecordsMayWinAndTheLateExitIsSilent() throws InterruptedException {
        // The exit is recorded after the detach read native state. The detach wins, and no line is printed.
        ShellEnd.Gate gate = new ShellEnd.Gate();
        ShellEnd known = null;
        assertTrue(gate.tryAdmit(ShellEnd.forLocalDetach(known)));
        assertFalse(gate.tryAdmit(ShellEnd.fromNative(1, 4, null)));
        assertEquals(ShellEnd.Kind.LOCAL_DETACH, gate.get().getKind());
        assertNotEquals(ShellEnd.Kind.EXITED, gate.get().getKind());
    }
}
