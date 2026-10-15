package com.mrndtvndv.term.server

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences for repository tests. Like Android, an edit changes the in-memory value as soon as
 * it is committed or applied. Only the counters below differ: they let tests prove which path a write took.
 */
class FakeSharedPreferences(initial: Map<String, String> = emptyMap()) : SharedPreferences {
    private val values: MutableMap<String, String?> = HashMap(initial)

    /** How many editors were opened. A refused write must leave this unchanged. */
    var editCount = 0
        private set

    /** How many edits were applied in the background. Writes in the saved-server store use this path. */
    var applyCount = 0
        private set

    /** How many edits were committed synchronously. The saved-server store never uses this path. */
    var commitCount = 0
        private set

    /** The stored value for [key], for asserting that storage was not touched. */
    fun stored(key: String): String? = values[key]

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key.orEmpty()] ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = values.containsKey(key.orEmpty())

    override fun edit(): SharedPreferences.Editor {
        editCount++
        return Editor()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removed = mutableSetOf<String>()
        private var clearFirst = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor {
            removed.add(key.orEmpty())
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return this
        }

        override fun commit(): Boolean {
            commitCount++
            flush()
            return true
        }

        override fun apply() {
            applyCount++
            flush()
        }

        private fun flush() {
            if (clearFirst) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
