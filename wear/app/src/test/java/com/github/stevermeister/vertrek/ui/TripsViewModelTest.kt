package com.github.stevermeister.vertrek.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.TripsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Uses the real TripsRepository backed by a temp-file DataStore (same
 * pattern as TripsRepositoryTest) as the "fake repository" — it never
 * touches production storage or the network, and exercises the real
 * serialization path rather than a hand-rolled double that could drift
 * from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-02T11:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newRepository(): TripsRepository {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempFolder.newFile("test-${System.nanoTime()}.preferences_pb") },
            )
        return TripsRepository(dataStore)
    }

    private fun sampleTrips() =
        listOf(
            TripDto(
                departureTime = "2026-11-02T11:08:00Z",
                arrivalTime = "2026-11-02T11:41:00Z",
                delayMinutes = 5,
                track = "4b",
                cancelled = false,
                crowdForecast = "MEDIUM",
            ),
        )

    @Test
    fun `initial state before any data arrives is Loading`() = runTest {
        val repository = newRepository()
        val viewModel = TripsViewModel(repository, clock = fixedClock)

        assertEquals(TripsUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `fresh cached data produces a Fresh body for the overridden direction`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        repository.saveCached(
            Direction.AB,
            CachedTripsData(
                direction = "ab",
                fromStationName = "Almere Oostvaarders",
                toStationName = "Amsterdam Centraal",
                trips = sampleTrips(),
                fetchedAtEpochMillis = fixedClock.millis(),
            ),
        )

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(Direction.AB, state.direction)
        assertEquals("Almere Oostvaarders", state.fromStationName)
        assertEquals("Amsterdam Centraal", state.toStationName)
        assertTrue(state.body is TripsBody.Fresh)
        assertEquals(sampleTrips(), (state.body as TripsBody.Fresh).trips)
    }

    @Test
    fun `stale cached data produces a Stale body carrying its age`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        val fetchedAt = fixedClock.instant().minusSeconds(5 * 60) // 5 minutes old -> Stale
        repository.saveCached(
            Direction.AB,
            CachedTripsData(direction = "ab", trips = sampleTrips(), fetchedAtEpochMillis = fetchedAt.toEpochMilli()),
        )

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        val body = state.body as TripsBody.Stale
        assertEquals(5L, body.ageMinutes)
        assertEquals(sampleTrips(), body.trips)
    }

    @Test
    fun `no cache and no recorded failure produces NoData NEVER_FETCHED`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(TripsBody.NoData(NoDataReason.NEVER_FETCHED), state.body)
        assertEquals(null, state.fromStationName)
        assertEquals(null, state.toStationName)
    }

    @Test
    fun `a recorded auth failure produces NoData AUTH_REJECTED`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        repository.setLastFailureReason(Direction.AB, NoDataReason.AUTH_REJECTED)

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(TripsBody.NoData(NoDataReason.AUTH_REJECTED), state.body)
    }

    @Test
    fun `a recorded network failure produces NoData NETWORK_DOWN`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        repository.setLastFailureReason(Direction.AB, NoDataReason.NETWORK_DOWN)

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(TripsBody.NoData(NoDataReason.NETWORK_DOWN), state.body)
    }

    @Test
    fun `a successful fetch with zero trips is Empty, not NoData`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        repository.saveCached(
            Direction.AB,
            CachedTripsData(direction = "ab", trips = emptyList(), fetchedAtEpochMillis = fixedClock.millis()),
        )

        val viewModel = TripsViewModel(repository, clock = fixedClock)
        val state = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(TripsBody.Empty, state.body)
    }

    @Test
    fun `swapDirection flips the override and notifies the tile`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        var tileNotified = false
        val viewModel =
            TripsViewModel(repository, clock = fixedClock, onDirectionChanged = { tileNotified = true })

        // Let the initial Content state land before swapping.
        viewModel.uiState.drop(1).first()

        viewModel.swapDirection()
        val afterSwap = viewModel.uiState.drop(1).first() as TripsUiState.Content

        assertEquals(Direction.BA, afterSwap.direction)
        assertEquals(Direction.BA, repository.getDirectionOverride(fixedClock))
        assertTrue(tileNotified)
    }

    @Test
    fun `refresh enqueues work and flips isRefreshing on`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        var refreshRequested = false
        val viewModel =
            TripsViewModel(repository, clock = fixedClock, onRefreshRequested = { refreshRequested = true })

        viewModel.uiState.drop(1).first() // let the initial Content state land

        viewModel.refresh()

        assertTrue(refreshRequested)
        assertTrue((viewModel.uiState.value as TripsUiState.Content).isRefreshing)
    }

    // Regression coverage for the tile-side loop found live (~20 refreshes
    // in 90 seconds): onTileRequest() enqueued unconditionally, and a
    // successful fetch's own cache write fed straight back into another
    // enqueue via requestUpdate(). This ViewModel has its own would-be
    // trigger point for the same shape of bug — uiState reactively observes
    // the cache via Flow — so it needs the same guarantee: only an explicit
    // user action enqueues, never an incidental state recomposition.
    @Test
    fun `a cache write from elsewhere never triggers a refresh enqueue`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        var enqueueCount = 0
        val viewModel =
            TripsViewModel(repository, clock = fixedClock, onRefreshRequested = { enqueueCount++ })

        viewModel.uiState.drop(1).first() // let the initial Content state land

        // Simulates exactly what RefreshWorker does on success — a direct
        // cache write, with nothing calling refresh()/onRefreshRequested.
        repository.saveCached(
            Direction.AB,
            CachedTripsData(direction = "ab", trips = sampleTrips(), fetchedAtEpochMillis = fixedClock.millis()),
        )

        // Confirms the write actually propagated (otherwise this test would
        // pass vacuously, having never exercised the reactive chain at all).
        val updated = viewModel.uiState.value as TripsUiState.Content
        assertEquals(sampleTrips(), (updated.body as TripsBody.Fresh).trips)
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `calling refresh again while already refreshing enqueues only once`() = runTest {
        val repository = newRepository()
        repository.setDirectionOverride(Direction.AB, fixedClock)
        var enqueueCount = 0
        val viewModel =
            TripsViewModel(repository, clock = fixedClock, onRefreshRequested = { enqueueCount++ })

        viewModel.uiState.drop(1).first() // let the initial Content state land

        viewModel.refresh()
        viewModel.refresh() // e.g. a second completed drag before the first indicator clears

        assertEquals(1, enqueueCount)
    }
}
