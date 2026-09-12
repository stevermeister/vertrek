package com.github.stevermeister.vertrek.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepository(): TripsRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { tempFolder.newFile("test-${System.nanoTime()}.preferences_pb") },
            )
        return TripsRepository(dataStore)
    }

    @Test
    fun `cache round-trips through DataStore`() = runTest {
        val repository = newRepository()
        val data =
            CachedTripsData(
                direction = "ab",
                trips =
                    listOf(
                        TripDto(
                            departureTime = "2026-11-02T12:08:00+0100",
                            delayMinutes = 5,
                            track = "4b",
                            durationMinutes = 33,
                            transfers = 0,
                            cancelled = false,
                        ),
                    ),
                fetchedAtEpochMillis = 1_700_000_000_000L,
            )

        assertNull(repository.getCached(Direction.AB))

        repository.saveCached(Direction.AB, data)

        assertEquals(data, repository.getCached(Direction.AB))
        assertNull(repository.getCached(Direction.BA)) // a different direction's cache is untouched
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
