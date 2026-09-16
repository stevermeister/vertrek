package com.github.stevermeister.vertrek.tile

import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.testing.LayoutElementAssertionsProvider
import androidx.wear.protolayout.testing.LayoutElementMatcher
import androidx.wear.protolayout.testing.hasChild
import androidx.wear.protolayout.testing.hasDescendant
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.formattedDepartureTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structural ("screenshot-adjacent") test: no pixels, but it builds the
 * real LayoutElement tree the tile ships and asserts on its content — a
 * regression here means the rendered tile actually changed. See also
 * TilePreview.kt (src/debug) for a visual preview in Android Studio.
 *
 * All trip departure times are relative to [clock] (fixed at 11:00) so
 * they land where each test expects — "upcoming", "the next departure",
 * "past" — deterministically instead of by accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TileLayoutTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-02T11:00:00Z"), ZoneOffset.UTC)

    private val roundDevice =
        DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(450)
            .setScreenHeightDp(450)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .build()

    private fun containsText(expectedSubstring: String): LayoutElementMatcher =
        LayoutElementMatcher("contains text \"$expectedSubstring\"") { element, _ ->
            (element as? LayoutElementBuilders.Text)?.text?.value?.contains(expectedSubstring) == true
        }

    // A 1dp-height Box is the strikethrough overlay line (protolayout Text
    // has no native strikethrough) — see strikethroughOverlay() in TileLayout.kt.
    private fun isStrikethroughLine(): LayoutElementMatcher =
        LayoutElementMatcher("is a 1dp-height strikethrough overlay line") { element, _ ->
            val box = element as? LayoutElementBuilders.Box
            val height = box?.height as? DimensionBuilders.DpProp
            height?.value == 1f
        }

    // Binds the check to one specific row: the strikethrough overlay Box
    // (the one with a 1dp line as a *direct* child) must also carry that
    // row's own departure time somewhere in its subtree. A matcher that
    // only asked "does a line exist anywhere" would pass even if the line
    // rendered on the wrong row entirely.
    private fun hasStrikethroughOverlayFor(trip: TripDto): LayoutElementMatcher =
        hasDescendant(containsText(trip.formattedDepartureTime())).and(hasChild(isStrikethroughLine()))

    private fun tripsData(trips: List<TripDto>, fetchedAtIso: String = "2026-11-02T11:00:00Z"): CachedTripsData =
        CachedTripsData(
            direction = "ab",
            fromStationName = "Almere Oostvaarders",
            toStationName = "Amsterdam Centraal",
            fromStationShort = "Oostvaarders",
            toStationShort = "Amsterdam",
            trips = trips,
            fetchedAtEpochMillis = Instant.parse(fetchedAtIso).toEpochMilli(),
        )

    // Three upcoming direct trips (clock is fixed at 11:00) — only the
    // first two must ever render as rows. The second carries a delay, so
    // the "+N" marker test has something to look for; the third exists
    // purely to prove it's excluded.
    private fun sampleData(): CachedTripsData =
        tripsData(
            listOf(
                TripDto(departureTime = "2026-11-02T11:07:00Z", delayMinutes = 0, track = "4b", cancelled = false),
                TripDto(departureTime = "2026-11-02T11:18:00Z", delayMinutes = 5, track = "2", cancelled = false),
                TripDto(departureTime = "2026-11-02T11:33:00Z", delayMinutes = 0, track = "3", cancelled = false),
            ),
        )

    private fun layoutFor(direction: Direction, cacheState: CacheState) =
        buildTileLayout(context, roundDevice, direction, cacheState, clock)

    @Test
    fun `header shows only the origin short name, not the destination or an arrow`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Oostvaarders")).assertExists()
        LayoutElementAssertionsProvider(layout).onElement(containsText("Amsterdam")).assertDoesNotExist()
        LayoutElementAssertionsProvider(layout).onElement(containsText("→")).assertDoesNotExist()
    }

    @Test
    fun `primary figure shows minutes until the next direct train, computed against the clock`() {
        // clock=11:00, first upcoming trip departs 11:07 -> 7 minutes.
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("7 min")).assertExists()
    }

    @Test
    fun `delayed row shows a plus-N marker`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("+5")).assertExists()
    }

    @Test
    fun `never shows a third row, even with more direct trips upcoming`() {
        val data = sampleData()
        val excludedThirdTrip = data.trips[2]
        val layout = layoutFor(Direction.AB, CacheState.Fresh(data))

        LayoutElementAssertionsProvider(layout)
            .onElement(containsText(excludedThirdTrip.formattedDepartureTime()))
            .assertDoesNotExist()
    }

    @Test
    fun `a past departure is dropped, not shown as the next trip`() {
        // clock=11:00: the first trip already left 10 minutes ago and must
        // not appear anywhere; the real next departure is the second one.
        val pastTrip = TripDto(departureTime = "2026-11-02T10:50:00Z", delayMinutes = 0, cancelled = false)
        val nextTrip = TripDto(departureTime = "2026-11-02T11:14:00Z", delayMinutes = 0, cancelled = false)
        val layout = layoutFor(Direction.AB, CacheState.Fresh(tripsData(listOf(pastTrip, nextTrip))))
        val provider = LayoutElementAssertionsProvider(layout)

        provider.onElement(containsText(pastTrip.formattedDepartureTime())).assertDoesNotExist()
        provider.onElement(containsText(nextTrip.formattedDepartureTime())).assertExists()
        provider.onElement(containsText("14 min")).assertExists() // not "0 min" — the original bug
    }

    @Test
    fun `strikethrough overlay lands on the cancelled row, never on its neighbour`() {
        val cancelled = TripDto(departureTime = "2026-11-02T11:07:00Z", delayMinutes = 0, cancelled = true)
        val notCancelled = TripDto(departureTime = "2026-11-02T11:18:00Z", delayMinutes = 0, cancelled = false)
        val layout = layoutFor(Direction.AB, CacheState.Fresh(tripsData(listOf(cancelled, notCancelled))))
        val provider = LayoutElementAssertionsProvider(layout)

        provider.onElement(hasStrikethroughOverlayFor(cancelled)).assertExists()
        provider.onElement(hasStrikethroughOverlayFor(notCancelled)).assertDoesNotExist()
    }

    @Test
    fun `prefers direct trips, never mentioning a transfer when direct ones are upcoming`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("transfer")).assertDoesNotExist()
    }

    @Test
    fun `falls back to trips with transfers, clearly marked, when no direct trains are upcoming`() {
        val data =
            tripsData(
                listOf(
                    TripDto(departureTime = "2026-11-02T11:07:00Z", delayMinutes = 0, cancelled = false, transfers = 1),
                    TripDto(departureTime = "2026-11-02T11:18:00Z", delayMinutes = 0, cancelled = false, transfers = 2),
                ),
            )
        val layout = layoutFor(Direction.AB, CacheState.Fresh(data))
        val provider = LayoutElementAssertionsProvider(layout)

        provider.onElement(containsText("1 transfer")).assertExists()
        provider.onElement(containsText("2 transfers")).assertExists()
    }

    @Test
    fun `every trip already departed shows the no-upcoming-trips message, not stale data`() {
        val longDeparted = TripDto(departureTime = "2026-11-02T09:00:00Z", delayMinutes = 0, cancelled = false)
        val layout = layoutFor(Direction.AB, CacheState.Fresh(tripsData(listOf(longDeparted))))
        LayoutElementAssertionsProvider(layout).onElement(containsText("No upcoming trips")).assertExists()
    }

    @Test
    fun `stale state shows an age indicator in the header`() {
        val layout = layoutFor(Direction.AB, CacheState.Stale(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("m old")).assertExists()
    }

    @Test
    fun `never fetched no-data state is distinguished from other reasons`() {
        val layout = layoutFor(Direction.AB, CacheState.NoData(NoDataReason.NEVER_FETCHED))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Waiting for first sync")).assertExists()
    }

    @Test
    fun `auth rejected no-data state says so`() {
        val layout = layoutFor(Direction.AB, CacheState.NoData(NoDataReason.AUTH_REJECTED))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Auth rejected")).assertExists()
    }

    @Test
    fun `network down no-data state says so`() {
        val layout = layoutFor(Direction.AB, CacheState.NoData(NoDataReason.NETWORK_DOWN))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Network unavailable")).assertExists()
    }
}
