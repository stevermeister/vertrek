package com.github.stevermeister.vertrek.tile

import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
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

private const val FRESHNESS_INTERVAL_MILLIS = 60_000L

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

        RefreshWorker.enqueue(applicationContext)

        val cached = runBlocking { repository.getCached(effectiveDirection) }
        val lastFailureReason = runBlocking { repository.getLastFailureReason(effectiveDirection) }
        val cacheState = cacheStateOf(cached, lastFailureReason, clock)

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
