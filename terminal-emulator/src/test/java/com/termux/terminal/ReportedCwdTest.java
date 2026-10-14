package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReportedCwdTest {

    @Test
    public void fileUrlWithHostAndEncodedSpace() {
        assertEquals("/home/u/my dir", ReportedCwd.parse("file://host/home/u/my%20dir"));
    }

    @Test
    public void fileUrlWithEmptyHost() {
        assertEquals("/tmp", ReportedCwd.parse("file:///tmp"));
    }

    @Test
    public void percentEncodedUtf8IsDecoded() {
        assertEquals("/home/u/caf\u00e9", ReportedCwd.parse("file:///home/u/caf%C3%A9"));
    }

    @Test
    public void literalPlusIsPreserved() {
        assertEquals("/a+b", ReportedCwd.parse("file:///a+b"));
    }

    @Test
    public void kittyShellCwdScheme() {
        assertEquals("/var/log", ReportedCwd.parse("kitty-shell-cwd://host/var/log"));
    }

    @Test
    public void bareAbsolutePathIsReturnedUnchanged() {
        assertEquals("/usr/bin", ReportedCwd.parse("/usr/bin"));
    }

    @Test
    public void nullAndEmptyAreRejected() {
        assertNull(ReportedCwd.parse(null));
        assertNull(ReportedCwd.parse(""));
    }

    @Test
    public void unsupportedSchemeIsRejected() {
        assertNull(ReportedCwd.parse("http://x/y"));
    }

    @Test
    public void hostWithoutPathIsRejected() {
        assertNull(ReportedCwd.parse("file://host"));
    }

    @Test
    public void malformedEscapeIsRejected() {
        assertNull(ReportedCwd.parse("file:///x%zz"));
    }
}
