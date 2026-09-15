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

    // A crowd dot is a 5dp x 5dp Box — see crowdDots() in TileLayout.kt.
    private fun isCrowdDot(): LayoutElementMatcher =
        LayoutElementMatcher("is a 5dp x 5dp crowd dot") { element, _ ->
            val box = element as? LayoutElementBuilders.Box
            val width = box?.width as? DimensionBuilders.DpProp
            val height = box?.height as? DimensionBuilders.DpProp
            width?.value == 5f && height?.value == 5f
        }

    // Direct structural count, not "at least one": a Row that dropped two of
    // its three dots (or the whole crowdDots() subtree bar one surviving
    // child) would still pass a bare hasChild(isCrowdDot()) check. This
    // guards specifically against the class of bug this test exists for —
    // see the comment on spacer()/verticalSpacer() in TileLayout.kt.
    private fun isFiveDpBox(element: LayoutElementBuilders.LayoutElement): Boolean {
        val box = element as? LayoutElementBuilders.Box ?: return false
        val width = box.width as? DimensionBuilders.DpProp
        val height = box.height as? DimensionBuilders.DpProp
        return width?.value == 5f && height?.value == 5f
    }

    private fun hasExactlyNCrowdDotChildren(n: Int): LayoutElementMatcher =
        LayoutElementMatcher("has exactly $n crowd dot children") { element, _ ->
            val row = element as? LayoutElementBuilders.Row ?: return@LayoutElementMatcher false
            row.contents.count(::isFiveDpBox) == n
        }

    // Binds the check to one specific row: the strikethrough overlay Box
    // (the one with a 1dp line as a *direct* child) must also carry that
    // row's own departure time somewhere in its subtree. A matcher that
    // only asked "does a line exist anywhere" would pass even if the line
    // rendered on the wrong row entirely.
    private fun hasStrikethroughOverlayFor(trip: TripDto): LayoutElementMatcher =
        hasDescendant(containsText(trip.formattedDepartureTime())).and(hasChild(isStrikethroughLine()))

    // Row 1 (index 0) deliberately has no delay: per spec, "+N" is only
    // rendered for rows 2-3, so a delay on row 1 wouldn't appear as text
    // anywhere and would be the wrong thing for this test to assert on.
    private fun sampleData() =
        CachedTripsData(
            direction = "ab",
            fromStationName = "Almere Oostvaarders",
            toStationName = "Amsterdam Centraal",
            fromStationShort = "Oostvaarders",
            toStationShort = "Amsterdam",
            trips =
                listOf(
                    TripDto(
                        departureTime = "2026-11-02T11:07:00Z",
                        arrivalTime = "2026-11-02T11:40:00Z",
                        delayMinutes = 0,
                        track = "4b",
                        cancelled = false,
                        crowdForecast = "UNKNOWN",
                    ),
                    TripDto(
                        departureTime = "2026-11-02T11:18:00Z",
                        arrivalTime = "2026-11-02T11:46:00Z",
                        delayMinutes = 5,
                        track = "2",
                        cancelled = false,
                        crowdForecast = "HIGH",
                    ),
                    TripDto(
                        departureTime = "2026-11-02T11:33:00Z",
                        arrivalTime = "2026-11-02T12:12:00Z",
                        delayMinutes = 0,
                        track = "3",
                        cancelled = true,
                        crowdForecast = "LOW",
                    ),
                ),
            fetchedAtEpochMillis = Instant.parse("2026-11-02T11:00:00Z").toEpochMilli(),
        )

    // Two cancelled trips among four (the tile's own MAX_ROWS), non-adjacent
    // (rows 1 and 3 of 0..3), so a strikethrough that leaks onto a
    // neighbouring row would actually get caught rather than being
    // indistinguishable from the right answer.
    private fun sampleDataWithNonAdjacentCancellations(): CachedTripsData =
        CachedTripsData(
            direction = "ab",
            fromStationName = "Almere Oostvaarders",
            toStationName = "Amsterdam Centraal",
            fromStationShort = "Oostvaarders",
            toStationShort = "Amsterdam",
            trips =
                listOf(
                    TripDto("2026-11-02T11:07:00Z", "2026-11-02T11:40:00Z", 0, "4b", false, "UNKNOWN"),
                    TripDto("2026-11-02T11:18:00Z", "2026-11-02T11:46:00Z", 0, "2", true, "UNKNOWN"),
                    TripDto("2026-11-02T11:33:00Z", "2026-11-02T12:12:00Z", 0, "3", false, "UNKNOWN"),
                    TripDto("2026-11-02T11:48:00Z", "2026-11-02T12:20:00Z", 0, "1", true, "UNKNOWN"),
                ),
            fetchedAtEpochMillis = Instant.parse("2026-11-02T11:00:00Z").toEpochMilli(),
        )

    private fun layoutFor(direction: Direction, cacheState: CacheState) =
        buildTileLayout(context, roundDevice, direction, cacheState, clock)

    // Track "9" is unambiguous — none of the times/dates in any fixture in
    // this file contain a lone "9" — so a text match against it can only be
    // the track chip, not an accidental hit on a departure/arrival time.
    private fun sampleDataWithBusiestTrip(): CachedTripsData =
        CachedTripsData(
            direction = "ab",
            fromStationName = "Almere Oostvaarders",
            toStationName = "Amsterdam Centraal",
            fromStationShort = "Oostvaarders",
            toStationShort = "Amsterdam",
            trips = listOf(TripDto("2026-11-02T11:07:00Z", "2026-11-02T11:40:00Z", 0, "9", false, "HIGH")),
            fetchedAtEpochMillis = Instant.parse("2026-11-02T11:00:00Z").toEpochMilli(),
        )

    @Test
    fun `header shows only the origin short name, not the destination or an arrow`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Oostvaarders")).assertExists()
        LayoutElementAssertionsProvider(layout).onElement(containsText("Amsterdam")).assertDoesNotExist()
        LayoutElementAssertionsProvider(layout).onElement(containsText("→")).assertDoesNotExist()
    }

    @Test
    fun `delayed row shows a plus-N marker`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("+5")).assertExists()
    }

    @Test
    fun `strikethrough overlay lands exactly on cancelled rows, never on their neighbours`() {
        val data = sampleDataWithNonAdjacentCancellations()
        val layout = layoutFor(Direction.AB, CacheState.Fresh(data))
        val provider = LayoutElementAssertionsProvider(layout)

        data.trips.forEach { trip ->
            val assertion = provider.onElement(hasStrikethroughOverlayFor(trip))
            if (trip.cancelled) assertion.assertExists() else assertion.assertDoesNotExist()
        }
    }

    // Regression guard for a real, verified defect: spacer()/verticalSpacer()
    // built an empty Box with one dimension left at the default WRAP and
    // zero children, which the real on-device ProtoLayoutInflater logged as
    // "Box set to wrap but contents are unmeasurable. Ignoring." — confirmed
    // by bisection on a physical render (removing the inter-dot spacer()
    // eliminated the warning entirely), not assumed by analogy to the
    // unrelated tripsColumn/expand() incident this comment used to cite.
    // Robolectric never exercises the real inflater, so it can't reproduce
    // that warning — what it CAN do, and what this test does, is prove the
    // LayoutElement tree this code actually builds still carries all three
    // dots and the track chip together, in the same row, undamaged by the
    // fix. That's the actual risk the warning raised (silent content loss —
    // it happened once before, see tripsColumn's own comment) and it's now
    // checked structurally rather than by eyeballing a screenshot.
    @Test
    fun `crowd dots and the track chip both survive, together, in the busiest row`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleDataWithBusiestTrip()))
        val provider = LayoutElementAssertionsProvider(layout)

        // Co-location, not just "exists somewhere": the crowd dots live in
        // their own nested Row inside the trip row, so the common ancestor
        // that has both a crowd dot AND the track text somewhere beneath it
        // is the trip row itself — not a coincidental unrelated match.
        provider.onElement(hasDescendant(isCrowdDot()).and(hasDescendant(containsText("9")))).assertExists()

        // Exact count: HIGH means 3 filled dots. A silent partial drop
        // (e.g. only 1 of 3 surviving) would pass a bare existence check
        // but not this one.
        provider.onElement(hasExactlyNCrowdDotChildren(3)).assertExists()
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
