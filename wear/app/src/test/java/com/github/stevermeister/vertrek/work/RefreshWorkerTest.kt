package com.github.stevermeister.vertrek.work

import androidx.work.Data
import com.github.stevermeister.vertrek.data.Direction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for a real, live-observed race: VertrekTileService
 * persists a tapped direction via a fire-and-forget coroutine launch and
 * immediately enqueues RefreshWorker, with no guarantee that write lands
 * before doWork() would otherwise re-resolve its own direction from the
 * same DataStore override. That could fetch/cache the *previous*
 * direction, leaving the one actually on screen stale and forcing one
 * extra enqueue before it self-corrected. resolveWorkDirection() removes
 * the second, independent resolution: when a direction is passed through
 * enqueue()'s input data, it's used exactly as given, never re-derived.
 */
class RefreshWorkerTest {

    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:30:00Z"), ZoneOffset.UTC) // before 13:00 -> AB naturally

    @Test
    fun `an explicit direction in input data wins, even when it disagrees with the override`() {
        val inputData = Data.Builder().putString(RefreshWorker.KEY_DIRECTION_PARAM, Direction.BA.paramValue).build()

        // Override says AB, clock says AB (well before 13:00) — both would
        // naturally resolve to AB. The explicit direction (BA) must still win.
        val resolved = RefreshWorker.resolveWorkDirection(inputData, override = Direction.AB, clock = clock)

        assertEquals(Direction.BA, resolved)
    }

    @Test
    fun `no input data falls back to the plain override-or-time-of-day resolution`() {
        val resolved = RefreshWorker.resolveWorkDirection(Data.EMPTY, override = Direction.BA, clock = clock)

        assertEquals(Direction.BA, resolved)
    }

    @Test
    fun `no input data and no override falls back to time-of-day resolution`() {
        val resolved = RefreshWorker.resolveWorkDirection(Data.EMPTY, override = null, clock = clock)

        assertEquals(Direction.AB, resolved) // before 13:00
    }

    @Test
    fun `an unparseable direction param falls back rather than crashing`() {
        val corrupt = Data.Builder().putString(RefreshWorker.KEY_DIRECTION_PARAM, "not-a-direction").build()

        val resolved = RefreshWorker.resolveWorkDirection(corrupt, override = Direction.BA, clock = clock)

        assertEquals(Direction.BA, resolved)
    }
}
