package com.github.stevermeister.vertrek.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private const val DATASTORE_NAME = "vertrek_trips"

val Context.tripsDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

private object PreferenceKeys {
    val DIRECTION_OVERRIDE = stringPreferencesKey("direction_override")

    fun cache(direction: Direction) = stringPreferencesKey("cache_${direction.paramValue}")
}

/**
 * Reads/writes never throw on malformed or missing data — a corrupt or
 * absent cache entry is just treated as "not there" (null), never a crash.
 */
class TripsRepository(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getDirectionOverride(): Direction? {
        val raw = dataStore.data.first()[PreferenceKeys.DIRECTION_OVERRIDE] ?: return null
        return Direction.fromParam(raw)
    }

    suspend fun setDirectionOverride(direction: Direction?) {
        dataStore.edit { prefs ->
            if (direction == null) {
                prefs.remove(PreferenceKeys.DIRECTION_OVERRIDE)
            } else {
                prefs[PreferenceKeys.DIRECTION_OVERRIDE] = direction.paramValue
            }
        }
    }

    suspend fun getCached(direction: Direction): CachedTripsData? {
        val raw = dataStore.data.first()[PreferenceKeys.cache(direction)] ?: return null
        return runCatching { json.decodeFromString<CachedTripsData>(raw) }.getOrNull()
    }

    suspend fun saveCached(direction: Direction, data: CachedTripsData) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.cache(direction)] = json.encodeToString(data)
        }
    }
}
