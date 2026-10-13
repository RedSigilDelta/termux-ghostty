package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;

import org.junit.Assert;
import org.junit.Test;

/**
 * Backend activation is lazy, so the readiness signal is how a host learns that a
 * session can blink a cursor. The dispatcher is the only path a session uses to
 * reach the activity host.
 */
public class TermuxTerminalSessionClientDispatcherTest {

    @Test
    public void terminalReadyIsForwardedToRegisteredClients() {
        TermuxTerminalSessionClientDispatcher dispatcher = new TermuxTerminalSessionClientDispatcher(
            new TermuxTerminalSessionServiceClient(null));
        RecordingClient client = new RecordingClient();
        dispatcher.registerClient(client);

        TerminalSession session = newSession();
        dispatcher.onTerminalReady(session);

        Assert.assertSame(session, client.readySession);
    }

    @Test
    public void terminalReadyIsNotForwardedAfterUnregister() {
        TermuxTerminalSessionClientDispatcher dispatcher = new TermuxTerminalSessionClientDispatcher(
            new TermuxTerminalSessionServiceClient(null));
        RecordingClient client = new RecordingClient();
        dispatcher.registerClient(client);
        dispatcher.unregisterClient(client);

        dispatcher.onTerminalReady(newSession());

        Assert.assertNull(client.readySession);
    }

    private TerminalSession newSession() {
        return new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
    }

    private static final class RecordingClient extends TermuxTerminalSessionClientBase {
        private TerminalSession readySession;

        @Override
        public void onTerminalReady(@NonNull TerminalSession readySession) {
            this.readySession = readySession;
        }
    }
}
