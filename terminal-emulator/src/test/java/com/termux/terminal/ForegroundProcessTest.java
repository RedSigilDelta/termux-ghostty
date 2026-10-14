package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ForegroundProcessTest {

    @Test
    public void readsTpgidAfterComm() {
        assertEquals(4321, ForegroundProcess.foregroundGroup("1234 (bash) S 1000 1234 1234 34816 4321 4194560 0 0"));
    }

    @Test
    public void handlesSpacesAndParenthesesInComm() {
        assertEquals(77, ForegroundProcess.foregroundGroup("1234 (my (odd) cmd) S 1000 1234 1234 34816 77 0"));
    }

    @Test
    public void rejectsMalformedLines() {
        assertEquals(-1, ForegroundProcess.foregroundGroup("1234 bash S 1000"));
        assertEquals(-1, ForegroundProcess.foregroundGroup("1234 (bash) S 1000 1234"));
        assertEquals(-1, ForegroundProcess.foregroundGroup("1234 (bash) S 1000 1234 1234 34816 x"));
    }

    @Test
    public void commandNameUsesProgramBaseName() {
        assertEquals("nvim", ForegroundProcess.commandName("/data/data/com.termux/files/usr/bin/nvim\u0000file.txt"));
        assertEquals("bash", ForegroundProcess.commandName("-bash"));
    }

    @Test
    public void commandNameLooksThroughInterpreters() {
        assertEquals("claude", ForegroundProcess.commandName("node\u0000--no-warnings\u0000/usr/bin/claude\u0000--resume"));
        assertEquals("server", ForegroundProcess.commandName("python3\u0000-u\u0000server.py"));
        assertEquals("node", ForegroundProcess.commandName("node"));
    }

    @Test
    public void commandNameSkipsLoaderOptionValues() {
        assertEquals("claude", ForegroundProcess.commandName(
            "/usr/glibc/lib/ld-linux-aarch64.so.1\u0000--library-path\u0000/usr/glibc/lib\u0000/usr/bin/claude"));
    }

    @Test
    public void commandNameHandlesMissingCmdline() {
        assertNull(ForegroundProcess.commandName(null));
        assertNull(ForegroundProcess.commandName(""));
    }

    @Test
    public void commandNameSkipsVersionedFileNames() {
        assertEquals("claude", ForegroundProcess.commandName(
            "/data/data/com.termux/files/home/.local/share/claude/versions/2.1.296"));
        assertEquals("node", ForegroundProcess.commandName("/opt/node/v22.3.0/bin/node"));
    }
}
