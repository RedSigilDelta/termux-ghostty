package com.mrndtvndv.term.workspace

import com.termux.terminal.ShellEnd
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAttachmentEndTest {
    @Test
    fun realExitCodeIsReported() {
        assertEquals("Attachment ended with code 1", remoteAttachmentEndText(ShellEnd.exited(1)))
    }

    @Test
    fun zeroExitIsNotReportedAsSuccess() {
        // A zero status with no signal is ENDED_UNKNOWN in the native contract, so it stays generic.
        assertEquals("Attachment ended", remoteAttachmentEndText(ShellEnd.exited(0)))
    }

    @Test
    fun signalIsReportedByName() {
        assertEquals("Attachment ended by signal HUP", remoteAttachmentEndText(ShellEnd.signaled("HUP")))
    }

    @Test
    fun transportLossIsDistinctFromARemoteEnd() {
        assertEquals("Connection to the server was lost", remoteAttachmentEndText(ShellEnd.transportLost()))
    }

    @Test
    fun localDetachIsDetached() {
        assertEquals("Detached", remoteAttachmentEndText(ShellEnd.localDetach()))
    }

    @Test
    fun unknownOrMissingEndStaysGeneric() {
        assertEquals("Attachment ended", remoteAttachmentEndText(ShellEnd.endedUnknown()))
        assertEquals("Attachment ended", remoteAttachmentEndText(null))
    }
}
