package com.github.stevermeister.vertrek.tile

import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.minutesUntilDeparture
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTripsSelectionTest {

    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun tripAt(iso: String, transfers: Int = 0, delayMinutes: Int = 0) =
        TripDto(departureTime = iso, delayMinutes = delayMinutes, cancelled = false, transfers = transfers)

    @Test
    fun `past departures are skipped when picking the next trips`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trips =
            listOf(
                tripAt("2026-11-02T11:00:00+0000"), // past
                tripAt("2026-11-02T11:55:00+0000"), // past
                tripAt("2026-11-02T12:05:00+0000"), // upcoming
                tripAt("2026-11-02T12:20:00+0000"), // upcoming
            )

        val selection = selectTileTrips(trips, clock)

        assertEquals(
            listOf("2026-11-02T12:05:00+0000", "2026-11-02T12:20:00+0000"),
            selection.rows.map { it.departureTime },
        )
    }

    // A cache being 30 minutes old is exactly how the original "0 min" bug
    // showed up: the first trip in the (unchanged) cached list has since
    // departed relative to real time, even though the cache's own contents
    // never changed. selectTileTrips() doesn't take cache age at all — it's
    // clock-vs-departure only — so a 30-minute-old fetch is indistinguishable
    // here from a fresh one; what matters is whether each trip's own
    // departure is still ahead of now.
    @Test
    fun `a cache 30 minutes old still finds the real next departure, not a stale first trip clamped to 0 min`() {
        val now = Instant.parse("2026-11-02T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val trips =
            listOf(
                // Departed 25 minutes ago from "now" — this is what the
                // first entry of a 30-minute-old cache looks like.
                tripAt("2026-11-02T11:35:00+0000"),
                // The real next departure: 14 minutes from "now".
                tripAt("2026-11-02T12:14:00+0000"),
            )

        val selection = selectTileTrips(trips, clock)

        assertEquals(1, selection.rows.size)
        assertEquals(14L, selection.rows.first().minutesUntilDeparture(clock))
    }

    @Test
    fun `prefers direct trips over ones with transfers`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trips =
            listOf(
                tripAt("2026-11-02T12:05:00+0000", transfers = 1),
                tripAt("2026-11-02T12:10:00+0000", transfers = 0),
                tripAt("2026-11-02T12:15:00+0000", transfers = 1),
                tripAt("2026-11-02T12:20:00+0000", transfers = 0),
            )

        val selection = selectTileTrips(trips, clock)

        assertEquals(false, selection.isFallback)
        assertEquals(
            listOf("2026-11-02T12:10:00+0000", "2026-11-02T12:20:00+0000"),
            selection.rows.map { it.departureTime },
        )
    }

    @Test
    fun `never shows more than two rows, even with more direct trips upcoming`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trips =
            listOf(
                tripAt("2026-11-02T12:05:00+0000"),
                tripAt("2026-11-02T12:10:00+0000"),
                tripAt("2026-11-02T12:15:00+0000"),
                tripAt("2026-11-02T12:20:00+0000"),
            )

        assertEquals(2, selectTileTrips(trips, clock).rows.size)
    }

    @Test
    fun `falls back to trips with transfers, marked as such, when no direct trains are upcoming`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trips =
            listOf(
                tripAt("2026-11-02T12:05:00+0000", transfers = 1),
                tripAt("2026-11-02T12:20:00+0000", transfers = 2),
            )

        val selection = selectTileTrips(trips, clock)

        assertTrue(selection.isFallback)
        assertEquals(2, selection.rows.size)
    }

    @Test
    fun `an empty trip list produces an empty selection, not a crash`() {
        assertEquals(TileTripsSelection.EMPTY, selectTileTrips(emptyList(), clockAt("2026-11-02T12:00:00Z")))
    }

    @Test
    fun `every trip having already departed produces an empty selection, not stale data or a crash`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trips = listOf(tripAt("2026-11-02T11:00:00+0000"), tripAt("2026-11-02T11:30:00+0000"))

        assertEquals(TileTripsSelection.EMPTY, selectTileTrips(trips, clock))
    }
}
