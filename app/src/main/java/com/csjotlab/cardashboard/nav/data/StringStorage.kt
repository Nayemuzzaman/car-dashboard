package com.csjotlab.cardashboard.nav.data

import android.content.SharedPreferences

/** One persisted string slot. SharedPreferences in the app; an in-memory value in tests. */
interface StringStorage {
    fun read(): String?
    fun write(value: String)
}

class SharedPreferencesStringStorage(
    private val prefs: SharedPreferences,
    private val key: String,
) : StringStorage {
    override fun read(): String? = prefs.getString(key, null)
    override fun write(value: String) = prefs.edit().putString(key, value).apply()
}
