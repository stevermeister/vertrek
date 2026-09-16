package com.github.stevermeister.vertrek.tile

import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.expectedDepartureInstant
import java.time.Clock
import java.time.Instant

private const val TILE_ROW_COUNT = 2

/**
 * What the tile actually shows: up to [TILE_ROW_COUNT] rows, and whether
 * they're a transfers>0 fallback (no direct trains were upcoming) rather
 * than the preferred direct-only selection. Empty rows means there is
 * genuinely nothing upcoming to show at all.
 */
data class TileTripsSelection(val rows: List<TripDto>, val isFallback: Boolean) {
    companion object {
        val EMPTY = TileTripsSelection(emptyList(), isFallback = false)
    }
}

/**
 * Selects the tile's rows for the user's actual commute: the next two
 * direct (transfers == 0) trains, never a hardcoded time — NS reschedules
 * twice a year, so anything hardcoded would silently go stale and empty
 * the tile.
 *
 * Already-departed trips are dropped BEFORE picking "next" — a Stale
 * cache's first trip can have already left by the time this renders (the
 * original "0 min" bug: computing against a trip that had already
 * departed and clamping the negative result to zero, rather than moving
 * on to the next one). Direct trips are preferred; if none are upcoming,
 * the next two trips of any kind are shown instead, marked as a fallback
 * — never an empty tile just because today's fastest connection needs a
 * change.
 */
fun selectTileTrips(trips: List<TripDto>, clock: Clock): TileTripsSelection {
    val now = Instant.now(clock)
    val upcoming = trips.filter { trip -> trip.expectedDepartureInstant()?.isAfter(now) == true }
    if (upcoming.isEmpty()) return TileTripsSelection.EMPTY

    val direct = upcoming.filter { it.transfers == 0 }
    return if (direct.isNotEmpty()) {
        TileTripsSelection(direct.take(TILE_ROW_COUNT), isFallback = false)
    } else {
        TileTripsSelection(upcoming.take(TILE_ROW_COUNT), isFallback = true)
    }
}
