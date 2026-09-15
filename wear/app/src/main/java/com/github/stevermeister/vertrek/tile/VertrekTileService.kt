package com.github.stevermeister.vertrek.tile

import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.cacheStateOf
import com.github.stevermeister.vertrek.data.tripsDataStore
import com.github.stevermeister.vertrek.work.RefreshWorker
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Rows no longer show a relative "N min" figure, so nothing on screen
// changes minute to minute anymore — a shorter interval just burned
// battery for redraws nobody could see.
private const val FRESHNESS_INTERVAL_MILLIS = 180_000L

class VertrekTileService : TileService() {

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val repository = TripsRepository(applicationContext.tripsDataStore)
        val clock = Clock.systemDefaultZone()

        // Synchronous, off the local DataStore cache only — no network here.
        val currentOverride = runBlocking { repository.getDirectionOverride() }
        val lastClickableId = requestParams.currentState.lastClickableId
        val effectiveDirection = resolveEffectiveDirection(lastClickableId, currentOverride, clock)

        val desiredDirection = desiredDirectionFromClickableId(lastClickableId)
        if (desiredDirection != null) {
            // Persisted asynchronously, outside this request's return path.
            // The id names an absolute direction, so replaying a stale id
            // on a later, tap-less request just re-persists the same
            // value — it can never flip the direction on its own.
            ioScope.launch { repository.setDirectionOverride(desiredDirection) }
        }

        val cached = runBlocking { repository.getCached(effectiveDirection) }
        val lastFailureReason = runBlocking { repository.getLastFailureReason(effectiveDirection) }
        val cacheState = cacheStateOf(cached, lastFailureReason, clock)

        if (shouldEnqueueRefresh(cacheState)) {
            RefreshWorker.enqueue(applicationContext)
        }

        val layoutElement =
            buildTileLayout(
                context = applicationContext,
                deviceParameters = requestParams.deviceConfiguration,
                direction = effectiveDirection,
                cacheState = cacheState,
                clock = clock,
            )

        val tile =
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
                .setTileTimeline(Timeline.fromLayoutElement(layoutElement))
                .build()

        return Futures.immediateFuture(tile)
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}

/**
 * Only enqueue a background refresh when the cache isn't already Fresh
 * (age < CacheFreshness.FRESH_THRESHOLD — 3 minutes, the same window as
 * FRESHNESS_INTERVAL_MILLIS above under a different name).
 *
 * Without this gate, onTileRequest() enqueued a refresh unconditionally
 * on every call — and RefreshWorker's own post-success requestUpdate()
 * (needed so the tile actually redraws with new data, since protolayout
 * tiles don't observe the cache Flow themselves) immediately re-invokes
 * onTileRequest(). That closed a self-feeding loop bounded only by
 * network round-trip latency, not this tile's declared 180s interval —
 * confirmed live: ~20 RefreshWorker runs in 90 seconds. Gating on
 * freshness breaks the loop at its root: right after a successful
 * refresh the cache is Fresh again, so the very next onTileRequest()
 * (the one requestUpdate() just triggered) enqueues nothing.
 */
internal fun shouldEnqueueRefresh(cacheState: CacheState): Boolean = cacheState !is CacheState.Fresh
