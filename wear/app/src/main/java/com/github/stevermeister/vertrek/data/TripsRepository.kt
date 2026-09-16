package com.github.stevermeister.vertrek.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val DATASTORE_NAME = "vertrek_trips"

val Context.tripsDataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

private object PreferenceKeys {
    val DIRECTION_OVERRIDE = stringPreferencesKey("direction_override")
    val DIRECTION_OVERRIDE_SET_AT = longPreferencesKey("direction_override_set_at_millis")

    fun cache(direction: Direction) = stringPreferencesKey("cache_${direction.paramValue}")

    fun lastFailure(direction: Direction) = stringPreferencesKey("last_failure_${direction.paramValue}")
}

/**
 * Reads/writes never throw on malformed or missing data — a corrupt or
 * absent cache entry is just treated as "not there" (null), never a crash.
 */
class TripsRepository(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Null if never set, or if it was set during a *different* natural
     * half-day period than [clock] currently reports — a manual swap
     * means "show me the other leg for the rest of right now", not "pin
     * this forever". Without this expiry, swapping in the evening (to
     * BA) would silently persist into the next morning, still overriding
     * what should naturally be AB again.
     */
    suspend fun getDirectionOverride(clock: Clock): Direction? = decodeOverride(dataStore.data.first(), clock)

    fun observeDirectionOverride(clock: Clock): Flow<Direction?> =
        dataStore.data.map { prefs -> decodeOverride(prefs, clock) }

    private fun decodeOverride(prefs: Preferences, clock: Clock): Direction? {
        val raw = prefs[PreferenceKeys.DIRECTION_OVERRIDE] ?: return null
        val direction = Direction.fromParam(raw) ?: return null
        val setAtMillis = prefs[PreferenceKeys.DIRECTION_OVERRIDE_SET_AT] ?: return null
        val setAtClock = Clock.fixed(Instant.ofEpochMilli(setAtMillis), clock.zone)
        return direction.takeIf { naturalDirection(setAtClock) == naturalDirection(clock) }
    }

    suspend fun setDirectionOverride(direction: Direction?, clock: Clock) {
        dataStore.edit { prefs ->
            if (direction == null) {
                prefs.remove(PreferenceKeys.DIRECTION_OVERRIDE)
                prefs.remove(PreferenceKeys.DIRECTION_OVERRIDE_SET_AT)
            } else {
                prefs[PreferenceKeys.DIRECTION_OVERRIDE] = direction.paramValue
                prefs[PreferenceKeys.DIRECTION_OVERRIDE_SET_AT] = clock.millis()
            }
        }
    }

    suspend fun getCached(direction: Direction): CachedTripsData? {
        val raw = dataStore.data.first()[PreferenceKeys.cache(direction)] ?: return null
        return decodeIfCurrentSchema(raw)
    }

    fun observeCached(direction: Direction): Flow<CachedTripsData?> =
        dataStore.data.map { prefs -> prefs[PreferenceKeys.cache(direction)]?.let { decodeIfCurrentSchema(it) } }

    /**
     * Decoding never throws even for a pre-versioning or otherwise
     * old-shape payload (TripDto/CachedTripsData's new fields all have
     * defaults), but an old payload's schemaVersion will never equal
     * CACHE_SCHEMA_VERSION (it defaults to 0, a sentinel that never
     * matches), so it's discarded here rather than trusted.
     */
    private fun decodeIfCurrentSchema(raw: String): CachedTripsData? {
        val decoded = runCatching { json.decodeFromString<CachedTripsData>(raw) }.getOrNull() ?: return null
        return decoded.takeIf { it.schemaVersion == CACHE_SCHEMA_VERSION }
    }

    suspend fun saveCached(direction: Direction, data: CachedTripsData) {
        val versioned = data.copy(schemaVersion = CACHE_SCHEMA_VERSION)
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.cache(direction)] = json.encodeToString(versioned)
        }
    }

    suspend fun getLastFailureReason(direction: Direction): NoDataReason? {
        val raw = dataStore.data.first()[PreferenceKeys.lastFailure(direction)] ?: return null
        return runCatching { NoDataReason.valueOf(raw) }.getOrNull()
    }

    fun observeLastFailureReason(direction: Direction): Flow<NoDataReason?> =
        dataStore.data.map { prefs ->
            prefs[PreferenceKeys.lastFailure(direction)]?.let { raw -> runCatching { NoDataReason.valueOf(raw) }.getOrNull() }
        }

    /** Pass null to clear it — that's what a successful fetch does. */
    suspend fun setLastFailureReason(direction: Direction, reason: NoDataReason?) {
        dataStore.edit { prefs ->
            if (reason == null) {
                prefs.remove(PreferenceKeys.lastFailure(direction))
            } else {
                prefs[PreferenceKeys.lastFailure(direction)] = reason.name
            }
        }
    }
}
