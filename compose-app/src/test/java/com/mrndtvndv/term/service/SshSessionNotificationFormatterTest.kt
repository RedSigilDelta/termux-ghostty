package com.mrndtvndv.term.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SshSessionNotificationFormatterTest {

    @Test
    fun noSessionsShowsIdleService() {
        assertEquals(
            Pair("Terminal Service", "No active sessions"),
            SshSessionNotificationFormatter.formatTitleAndText(0, 0, wakeLockHeld = false),
        )
    }

    @Test
    fun wakeLockWithNoSessionsSaysSo() {
        assertEquals(
            Pair("Wake Lock Active", "Wake lock held (no active sessions)"),
            SshSessionNotificationFormatter.formatTitleAndText(0, 0, wakeLockHeld = true),
        )
    }

    @Test
    fun oneSshConnectionIsNamedSingular() {
        assertEquals(
            Pair("SSH Connection Active", "1 active SSH connection"),
            SshSessionNotificationFormatter.formatTitleAndText(1, 0, wakeLockHeld = false),
        )
    }

    @Test
    fun manySshConnectionsAreCounted() {
        assertEquals(
            Pair("SSH Connections Active", "2 active SSH connections"),
            SshSessionNotificationFormatter.formatTitleAndText(2, 0, wakeLockHeld = false),
        )
    }

    @Test
    fun sshConnectionsWithWakeLockAppendsNote() {
        assertEquals(
            Pair("SSH Connection Active", "1 active SSH connection (wake lock held)"),
            SshSessionNotificationFormatter.formatTitleAndText(1, 0, wakeLockHeld = true),
        )
    }

    @Test
    fun localSessionsKeepTheirOwnWording() {
        assertEquals(
            Pair("Local Session Active", "Maintaining active local terminal session"),
            SshSessionNotificationFormatter.formatTitleAndText(0, 1, wakeLockHeld = false),
        )
        assertEquals(
            Pair("Local Sessions Active", "Maintaining 2 active local terminal sessions"),
            SshSessionNotificationFormatter.formatTitleAndText(0, 2, wakeLockHeld = false),
        )
    }

    @Test
    fun mixedSshAndLocalListsBoth() {
        assertEquals(
            Pair("SSH Connection Active", "1 active SSH connection and 1 local terminal session"),
            SshSessionNotificationFormatter.formatTitleAndText(1, 1, wakeLockHeld = false),
        )
    }
}
