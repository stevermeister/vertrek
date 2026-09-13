package com.github.stevermeister.vertrek.tile

import androidx.test.core.app.ApplicationProvider
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.testing.LayoutElementAssertionsProvider
import androidx.wear.protolayout.testing.LayoutElementMatcher
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
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

    private fun hasText(expected: String): LayoutElementMatcher =
        LayoutElementMatcher("has text \"$expected\"") { element, _ ->
            (element as? LayoutElementBuilders.Text)?.text?.value == expected
        }

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

    // Row 1 (index 0) deliberately has no delay: per spec, "+N" is only
    // rendered for rows 2-3, so a delay on row 1 wouldn't appear as text
    // anywhere and would be the wrong thing for this test to assert on.
    private fun sampleData() =
        CachedTripsData(
            direction = "ab",
            fromStationName = "Almere Oostvaarders",
            toStationName = "Amsterdam Centraal",
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

    private fun layoutFor(direction: Direction, cacheState: CacheState) =
        buildTileLayout(context, roundDevice, direction, cacheState, clock)

    @Test
    fun `header shows full station names, not codes`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("Almere Oostvaarders")).assertExists()
        LayoutElementAssertionsProvider(layout).onElement(containsText("Amsterdam Centraal")).assertExists()
        LayoutElementAssertionsProvider(layout).onElement(containsText("→")).assertExists()
    }

    @Test
    fun `primary row shows minutes until departure`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        // Departure is 11:07, "now" is fixed at 11:00 -> 7 minutes.
        LayoutElementAssertionsProvider(layout).onElement(hasText("7 min")).assertExists()
    }

    @Test
    fun `delayed row shows a plus-N marker`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(containsText("+5")).assertExists()
    }

    @Test
    fun `cancelled row renders a strikethrough overlay line`() {
        val layout = layoutFor(Direction.AB, CacheState.Fresh(sampleData()))
        LayoutElementAssertionsProvider(layout).onElement(isStrikethroughLine()).assertExists()
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
