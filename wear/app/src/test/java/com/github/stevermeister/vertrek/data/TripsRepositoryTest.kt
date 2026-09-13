package com.github.stevermeister.vertrek.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDataStore() =
        PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("test-${System.nanoTime()}.preferences_pb") },
        )

    private fun newRepository(): TripsRepository = TripsRepository(newDataStore())

    @Test
    fun `cache round-trips through DataStore, including the current schema version`() = runTest {
        val repository = newRepository()
        val data =
            CachedTripsData(
                direction = "ab",
                fromStationName = "Almere Oostvaarders",
                toStationName = "Amsterdam Centraal",
                trips =
                    listOf(
                        TripDto(
                            departureTime = "2026-11-02T12:08:00+0100",
                            arrivalTime = "2026-11-02T12:36:00+0100",
                            delayMinutes = 5,
                            track = "4b",
                            cancelled = false,
                            crowdForecast = "MEDIUM",
                        ),
                    ),
                fetchedAtEpochMillis = 1_700_000_000_000L,
            )

        assertNull(repository.getCached(Direction.AB))

        repository.saveCached(Direction.AB, data)

        val roundTripped = repository.getCached(Direction.AB)
        assertEquals(data.copy(schemaVersion = CACHE_SCHEMA_VERSION), roundTripped)
        assertNull(repository.getCached(Direction.BA)) // a different direction's cache is untouched
    }

    @Test
    fun `an old-shape cached payload is discarded without throwing`() = runTest {
        val dataStore = newDataStore()
        val repository = TripsRepository(dataStore)

        // The pre-redesign shape: no schemaVersion, no fromStationName/
        // toStationName/arrivalTime/crowdForecast, and it still has the
        // now-removed durationMinutes/transfers fields.
        val oldShapeJson =
            """
            {"direction":"ab","trips":[{"departureTime":"2026-11-02T12:08:00+0100","delayMinutes":5,"track":"4b","durationMinutes":33,"transfers":0,"cancelled":false}],"fetchedAtEpochMillis":1700000000000}
            """.trimIndent()

        dataStore.edit { prefs -> prefs[stringPreferencesKey("cache_ab")] = oldShapeJson }

        // Must not throw — discarded as if there were no cache at all.
        assertNull(repository.getCached(Direction.AB))
    }

    @Test
    fun `direction override round-trips and can be cleared back to unset`() = runTest {
        val repository = newRepository()

        assertNull(repository.getDirectionOverride())

        repository.setDirectionOverride(Direction.BA)
        assertEquals(Direction.BA, repository.getDirectionOverride())

        repository.setDirectionOverride(null)
        assertNull(repository.getDirectionOverride())
    }

    @Test
    fun `last failure reason round-trips, is per-direction, and clears`() = runTest {
        val repository = newRepository()

        assertNull(repository.getLastFailureReason(Direction.AB))

        repository.setLastFailureReason(Direction.AB, NoDataReason.AUTH_REJECTED)
        assertEquals(NoDataReason.AUTH_REJECTED, repository.getLastFailureReason(Direction.AB))
        assertNull(repository.getLastFailureReason(Direction.BA)) // other direction untouched

        repository.setLastFailureReason(Direction.AB, null)
        assertNull(repository.getLastFailureReason(Direction.AB))
    }
}
