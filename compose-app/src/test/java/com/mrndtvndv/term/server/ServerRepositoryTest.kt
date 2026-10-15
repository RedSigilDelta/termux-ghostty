package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real [ServerRepository] over a fake SharedPreferences, so storage contents are checked directly. */
class ServerRepositoryTest {
    private val key = ServerRepository.KEY_SAVED_SERVERS

    private val alpha = ServerConfig(
        id = "a",
        label = "Alpha",
        host = "a.example",
        username = "u",
        herdrEnabled = true,
        herdrSocketPath = "/run/a.sock",
    )
    private val beta = ServerConfig(id = "b", label = "Beta", host = "b.example", username = "u")

    private val alphaJson =
        """{"id":"a","label":"Alpha","host":"a.example","username":"u",""" +
            """"persistence":"Herdr","herdrSocketPath":"/run/a.sock"}"""
    private val dtachJson = """{"id":"d","label":"Dtach","host":"d.example","username":"u","persistence":"Dtach"}"""

    // An entry from a future or removed option. This build cannot decode it.
    private val moshJson = """{"id":"x","label":"Future","host":"x.example","username":"u","persistence":"Mosh"}"""

    private fun prefsWith(text: String?): FakeSharedPreferences =
        FakeSharedPreferences(text?.let { mapOf(key to it) } ?: emptyMap())

    @Test
    fun emptyStoreLoadsEmptyAndAcceptsAdd() {
        val repository = ServerRepository(prefsWith(null))

        assertEquals(SavedServersLoad.Loaded(emptyList()), repository.load())
        assertTrue(repository.add(alpha).isSuccess)
        assertEquals(alpha, repository.get("a"))
    }

    @Test
    fun addUpdateAndRemoveKeepEveryField() {
        val repository = ServerRepository(prefsWith(null))

        repository.add(alpha)
        repository.add(beta)
        repository.update(alpha.copy(label = "Alpha 2"))
        repository.remove("b")

        assertEquals(SavedServersLoad.Loaded(listOf(alpha.copy(label = "Alpha 2"))), repository.load())
    }

    @Test
    fun unreadableEntryFailsTheLoadAndBlocksEveryWriteWithStorageUntouched() {
        val text = "[$alphaJson,$moshJson]"
        val prefs = prefsWith(text)
        val repository = ServerRepository(prefs)

        val load = repository.load()

        assertTrue(load is SavedServersLoad.Unreadable)
        assertTrue((load as SavedServersLoad.Unreadable).message.contains("entry 2"))
        assertNull(repository.get("a"))

        val refusedAdd = repository.add(beta)
        val refusedUpdate = repository.update(alpha.copy(label = "changed"))
        val refusedRemove = repository.remove("a")

        assertTrue(refusedAdd.isFailure)
        assertTrue(refusedUpdate.isFailure)
        assertTrue(refusedRemove.isFailure)
        assertTrue(refusedAdd.exceptionOrNull()?.message.orEmpty().contains("entry 2"))
        assertEquals(text, prefs.stored(key))
        assertEquals(0, prefs.editCount)
    }

    @Test
    fun payloadThatIsNotAListFailsTheLoadAndBlocksWrites() {
        val text = """{"id":"a"}"""
        val prefs = prefsWith(text)
        val repository = ServerRepository(prefs)

        assertTrue((repository.load() as SavedServersLoad.Unreadable).message.contains("not a list"))
        assertTrue(repository.add(beta).isFailure)
        assertEquals(text, prefs.stored(key))
    }

    @Test
    fun garbageTextFailsTheLoadAndBlocksWrites() {
        val text = "not json"
        val prefs = prefsWith(text)
        val repository = ServerRepository(prefs)

        assertTrue((repository.load() as SavedServersLoad.Unreadable).message.contains("not valid JSON"))
        assertTrue(repository.add(beta).isFailure)
        assertEquals(text, prefs.stored(key))
    }

    @Test
    fun blankOrWhitespaceStoredValueIsUnreadableAndNeverEdited() {
        for (text in listOf("", "   ", "\n\t ")) {
            val prefs = prefsWith(text)
            val repository = ServerRepository(prefs)

            assertTrue(repository.load() is SavedServersLoad.Unreadable)
            assertTrue(repository.add(alpha).isFailure)
            assertTrue(repository.update(alpha).isFailure)
            assertTrue(repository.remove("a").isFailure)
            assertEquals(text, prefs.stored(key))
            assertEquals(0, prefs.editCount)
        }
    }

    @Test
    fun acceptedWritesUseApplyNotCommitAndTheReadBackSeesThem() {
        val prefs = prefsWith("[$alphaJson]")
        val repository = ServerRepository(prefs)

        assertTrue(repository.add(beta).isSuccess)

        assertEquals(1, prefs.applyCount)
        assertEquals(0, prefs.commitCount)
        assertEquals(SavedServersLoad.Loaded(listOf(alpha, beta)), repository.load())
    }

    @Test
    fun legacyPersistenceLoadsAsHerdrEnabledAndTheNextWriteDropsTheOldKey() {
        val prefs = prefsWith("[$dtachJson,$alphaJson]")
        val repository = ServerRepository(prefs)

        val loaded = repository.load() as SavedServersLoad.Loaded
        assertEquals(listOf(false, true), loaded.servers.map { it.herdrEnabled })

        assertTrue(repository.add(beta).isSuccess)

        val stored = prefs.stored(key).orEmpty()
        assertFalse(stored.contains("persistence"))
        assertEquals(
            listOf(false, true, false),
            (repository.load() as SavedServersLoad.Loaded).servers.map { it.herdrEnabled },
        )
    }
}
