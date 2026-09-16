package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for a real bug: the tile's "N min" figure once
 * showed "0 min" for a train that was actually ~10 minutes out. Root
 * cause was almost certainly computing against a stale cache whose first
 * trip had already departed, with the negative result clamped to zero.
 * minutesUntilDeparture() is deliberately clock-based, not cache-age-
 * based — see selectTileTrips() in TileTripsSelectionTest.kt for the
 * same guarantee at the row-selection level.
 */
class TripFormattingTest {

    private fun clockAt(iso: String): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun tripDepartingAt(iso: String, delayMinutes: Int = 0) =
        TripDto(departureTime = iso, delayMinutes = delayMinutes, cancelled = false)

    @Test
    fun `a trip departing in 14 minutes reads 14 min`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trip = tripDepartingAt("2026-11-02T12:14:00+0000")

        assertEquals(14L, trip.minutesUntilDeparture(clock))
    }

    @Test
    fun `a delay pushes the expected departure back, and the minute figure with it`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trip = tripDepartingAt("2026-11-02T12:10:00+0000", delayMinutes = 5) // expected 12:15

        assertEquals(15L, trip.minutesUntilDeparture(clock))
    }

    @Test
    fun `a departure already in the past is null, never a clamped zero`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trip = tripDepartingAt("2026-11-02T11:50:00+0000") // 10 minutes ago

        assertNull(trip.minutesUntilDeparture(clock))
    }

    @Test
    fun `a departure exactly now is null, not zero`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trip = tripDepartingAt("2026-11-02T12:00:00+0000")

        assertNull(trip.minutesUntilDeparture(clock))
    }

    @Test
    fun `an unparseable departure time is null, not a crash`() {
        val clock = clockAt("2026-11-02T12:00:00Z")
        val trip = tripDepartingAt("not-a-real-timestamp")

        assertNull(trip.minutesUntilDeparture(clock))
    }
}
