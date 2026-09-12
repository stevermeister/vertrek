package com.github.stevermeister.vertrek.tile

import android.content.Context
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tiles.tooling.preview.TilePreviewHelper.singleTimelineEntryTileBuilder
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import java.time.Clock

/**
 * Renders in Android Studio's Tile Preview surface — no watch or emulator
 * needed. Open this file and use the Preview pane (or Split view) to see
 * the tile before sideloading.
 */
@Preview
fun freshTripsPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.AB,
                cacheState = CacheState.Fresh(sampleData()),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

@Preview
fun staleTripsPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.BA,
                cacheState = CacheState.Stale(sampleData(ageMillis = 8 * 60_000L)),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

@Preview
fun cancelledTripPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.AB,
                cacheState = CacheState.Fresh(sampleData(secondTripCancelled = true)),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

@Preview
fun noDataNetworkDownPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.AB,
                cacheState = CacheState.NoData(NoDataReason.NETWORK_DOWN),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

@Preview
fun noDataAuthRejectedPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.AB,
                cacheState = CacheState.NoData(NoDataReason.AUTH_REJECTED),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

@Preview
fun noDataNeverFetchedPreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        singleTimelineEntryTileBuilder(
            buildTileLayout(
                context = context,
                deviceParameters = request.deviceConfiguration,
                direction = Direction.AB,
                cacheState = CacheState.NoData(NoDataReason.NEVER_FETCHED),
                clock = Clock.systemDefaultZone(),
            ),
        ).build()
    }

private fun sampleData(ageMillis: Long = 0, secondTripCancelled: Boolean = false): CachedTripsData =
    CachedTripsData(
        direction = "ab",
        trips =
            listOf(
                TripDto(
                    departureTime = "2026-11-02T12:08:00+0100",
                    delayMinutes = 5,
                    track = "4b",
                    durationMinutes = 33,
                    transfers = 0,
                    cancelled = false,
                ),
                TripDto(
                    departureTime = "2026-11-02T12:18:00+0100",
                    delayMinutes = 0,
                    track = "4b",
                    durationMinutes = 28,
                    transfers = 0,
                    cancelled = secondTripCancelled,
                ),
                TripDto(
                    departureTime = "2026-11-02T12:33:00+0100",
                    delayMinutes = 0,
                    track = "3",
                    durationMinutes = 39,
                    transfers = 1,
                    cancelled = false,
                ),
            ),
        fetchedAtEpochMillis = System.currentTimeMillis() - ageMillis,
    )
