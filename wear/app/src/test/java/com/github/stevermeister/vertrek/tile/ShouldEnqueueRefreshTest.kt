package com.github.stevermeister.vertrek.tile

import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.NoDataReason
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for a real, live-observed bug: onTileRequest()
 * used to enqueue a background refresh unconditionally on every call,
 * and RefreshWorker's own post-success requestUpdate() re-invoked
 * onTileRequest() immediately — a self-feeding loop bounded only by
 * network latency (confirmed live: ~20 refreshes in 90 seconds), not
 * the tile's declared 180s freshness interval. See the comment on
 * shouldEnqueueRefresh() in VertrekTileService.kt.
 */
class ShouldEnqueueRefreshTest {

    private fun dataFetchedAt(epochMillis: Long) =
        CachedTripsData(direction = "ab", trips = emptyList(), fetchedAtEpochMillis = epochMillis)

    @Test
    fun `does not enqueue when the cache was just written and is Fresh`() {
        // This is the exact moment RefreshWorker's post-success requestUpdate()
        // re-invokes onTileRequest(): the cache was just written (age ~0), so
        // the state is Fresh. Before the fix, the enqueue call didn't look at
        // this at all and fired anyway — this is the assertion that would
        // have caught it.
        val justWritten = dataFetchedAt(Instant.parse("2026-11-02T11:00:00Z").toEpochMilli())
        assertFalse(shouldEnqueueRefresh(CacheState.Fresh(justWritten)))
    }

    @Test
    fun `enqueues when the cache is stale`() {
        val fiveMinutesOld = dataFetchedAt(Instant.parse("2026-11-02T10:55:00Z").toEpochMilli())
        assertTrue(shouldEnqueueRefresh(CacheState.Stale(fiveMinutesOld)))
    }

    @Test
    fun `enqueues when there is no usable cache at all`() {
        assertTrue(shouldEnqueueRefresh(CacheState.NoData(NoDataReason.NEVER_FETCHED)))
    }
}
