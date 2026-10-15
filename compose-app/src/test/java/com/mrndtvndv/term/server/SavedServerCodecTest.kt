package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavedServerCodecTest {
    private val alpha = ServerConfig(
        id = "a",
        label = "Box",
        host = "h",
        username = "u",
        herdrEnabled = true,
        herdrSocketPath = "/run/herdr.sock",
    )

    private val alphaJson =
        """{"id":"a","label":"Box","host":"h","username":"u",""" +
            """"herdrEnabled":true,"herdrSocketPath":"/run/herdr.sock"}"""
    private val moshJson = """{"id":"b","label":"Old","host":"h","username":"u","persistence":"Mosh"}"""

    private fun unreadableMessage(raw: String): String = try {
        SavedServerCodec.decode(raw)
        fail("expected the payload to be unreadable")
        ""
    } catch (error: SavedServerCodec.UnreadableException) {
        error.message.orEmpty()
    }

    /** Decodes a list holding one entry, for the conversion cases. */
    private fun decodeOne(entry: String): ServerConfig = SavedServerCodec.decode("[$entry]").single()

    @Test
    fun knownListRoundTrips() {
        val text = SavedServerCodec.encode(listOf(alpha))

        assertEquals(listOf(alpha), SavedServerCodec.decode(text))
    }

    @Test
    fun oneUnreadableEntryMakesTheWholeListUnreadableAndNamesIt() {
        assertTrue(unreadableMessage("[$alphaJson,$moshJson]").contains("entry 2"))
    }

    @Test
    fun textThatIsNotAListIsUnreadable() {
        assertTrue(unreadableMessage("""{"oops":true}""").contains("not a list"))
    }

    @Test
    fun garbageIsUnreadable() {
        assertTrue(unreadableMessage("not json").contains("not valid JSON"))
    }

    @Test
    fun blankTextIsUnreadableNotEmpty() {
        assertTrue(unreadableMessage("").contains("empty"))
        assertTrue(unreadableMessage("   ").contains("empty"))
    }

    @Test
    fun legacyHerdrBecomesHerdrEnabledAndTheNextWriteDropsTheOldKey() {
        val server = decodeOne("""{"id":"a","label":"Box","host":"h","username":"u","persistence":"Herdr"}""")

        assertTrue(server.herdrEnabled)
        assertFalse(SavedServerCodec.encode(listOf(server)).contains("persistence"))
    }

    @Test
    fun legacyDtachAndNoneBecomeHerdrDisabled() {
        for (legacy in listOf("Dtach", "None")) {
            val server = decodeOne("""{"id":"a","label":"Box","host":"h","username":"u","persistence":"$legacy"}""")

            assertFalse("$legacy must not enable herdr", server.herdrEnabled)
        }
    }

    @Test
    fun entryWithoutAnyPersistenceKeyIsHerdrOff() {
        assertFalse(decodeOne("""{"id":"a","label":"Box","host":"h","username":"u"}""").herdrEnabled)
    }

    @Test
    fun explicitToggleWinsOverTheLegacyValue() {
        assertTrue(decodeOne("""{"id":"a","label":"B","persistence":"Dtach","herdrEnabled":true}""").herdrEnabled)
        assertFalse(decodeOne("""{"id":"a","label":"B","persistence":"Herdr","herdrEnabled":false}""").herdrEnabled)
    }

    @Test
    fun unknownLegacyValueIsUnreadable() {
        assertTrue(unreadableMessage("[$moshJson]").contains("entry 1"))
    }

    @Test
    fun legacyValueThatIsNotAStringIsUnreadable() {
        assertTrue(unreadableMessage("""[{"id":"a","label":"B","persistence":7}]""").contains("entry 1"))
        assertTrue(unreadableMessage("""[{"id":"a","label":"B","persistence":null}]""").contains("entry 1"))
    }

    @Test
    fun nonBooleanToggleIsUnreadable() {
        assertTrue(unreadableMessage("""[{"id":"a","label":"B","herdrEnabled":"yes"}]""").contains("entry 1"))
    }
}
