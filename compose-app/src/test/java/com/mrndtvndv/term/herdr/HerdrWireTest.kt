package com.mrndtvndv.term.herdr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrWireTest {
    @Test
    fun `decodes snapshot facts and keeps identities`() {
        val snapshot = HerdrWire.decodeSnapshot(snapshotResult("r1"))

        assertEquals("p-1", snapshot.focusedPaneId)
        assertEquals(mapOf("branch" to "main"), snapshot.workspaces.single().tokens)
        val pane = snapshot.panes.first()
        assertEquals("t-1", pane.terminalId)
        assertEquals("/home/u/work", pane.cwd)
        assertEquals("é🚀", pane.label)
        assertEquals(HerdrAgentStatus.Working, pane.agentStatus)
        assertEquals(10L, pane.scroll?.maxOffsetFromBottom)
        assertEquals(5L, pane.revision)
        assertEquals(HerdrSplitDirection.Right, snapshot.layouts.single().splits.single().direction)
        assertEquals(0.5f, snapshot.layouts.single().splits.single().ratio, 0f)
        assertEquals("t-1", snapshot.agents.single().terminalId)
    }

    @Test
    fun `maps unknown agent states to Unknown instead of failing`() {
        val snapshot = HerdrWire.decodeSnapshot(snapshotResult("r1"))

        assertEquals(HerdrAgentStatus.Unknown, snapshot.panes.last().agentStatus)
    }

    @Test
    fun `decodes pong and tolerates missing capabilities`() {
        val result = HerdrWire.resultOf(
            HerdrWire.parseLine("""{"id":"p","result":{"type":"pong","version":"1.0.0","protocol":9}}"""),
            "p",
        )

        val info = HerdrWire.decodePong(result)

        assertEquals("1.0.0", info.version)
        assertEquals(9L, info.protocol)
        assertNull(info.capabilities)
    }

    @Test
    fun `wrong result type is a protocol error`() {
        val pong = HerdrWire.resultOf(HerdrWire.parseLine(pongReply("r")), "r")

        val error = expectWireError { HerdrWire.decodeSnapshot(pong) }

        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }

    @Test
    fun `classifies unknown variants and events_lost`() {
        val unsupported = HerdrWire.errorOf(HerdrWire.parseLine(unknownVariantReply("r", "session.snapshot")))
        assertEquals(HerdrErrorKind.Unsupported, unsupported?.kind)

        val lost = HerdrWire.errorOf(HerdrWire.parseLine(errorReply("s", "events_lost", "behind")))
        assertEquals(HerdrErrorKind.EventsLost, lost?.kind)

        val rejected = HerdrWire.errorOf(HerdrWire.parseLine(errorReply("r", "pane_not_found", "no pane")))
        assertEquals(HerdrErrorKind.Rejected, rejected?.kind)
        assertEquals("pane_not_found", rejected?.serverCode)
    }

    @Test
    fun `response with another id is a protocol error`() {
        val line = HerdrWire.parseLine(pongReply("other"))

        val error = expectWireError { HerdrWire.resultOf(line, "r") }

        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }

    @Test
    fun `malformed JSON is a protocol error`() {
        val error = expectWireError { HerdrWire.parseLine("{not json") }

        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }

    @Test
    fun `encodes one request object with method and params`() {
        val line = HerdrWire.encodeRequest("ecto-1", "pane.get", JsonObject(mapOf("pane_id" to JsonPrimitive("p-1"))))

        val parsed = HerdrWire.parseLine(line)

        assertEquals("ecto-1", parsed["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("pane.get", parsed["method"]?.jsonPrimitive?.contentOrNull)
        assertTrue((parsed["params"] as JsonObject).containsKey("pane_id"))
    }

    private fun snapshotResult(id: String) = HerdrWire.resultOf(HerdrWire.parseLine(snapshotReply(id)), id)

    private fun expectWireError(block: () -> Unit): HerdrError {
        try {
            block()
        } catch (error: HerdrException) {
            return error.error
        }
        throw AssertionError("expected HerdrException")
    }
}
