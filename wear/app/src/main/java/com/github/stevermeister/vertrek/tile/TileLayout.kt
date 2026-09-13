package com.github.stevermeister.vertrek.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.background
import androidx.wear.protolayout.modifiers.border
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.clip
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.protolayout.modifiers.padding
import androidx.wear.protolayout.modifiers.toProtoLayoutModifiers
import androidx.wear.protolayout.types.layoutString
import com.github.stevermeister.vertrek.MainActivity
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.ageMinutes
import com.github.stevermeister.vertrek.data.formattedArrivalTime
import com.github.stevermeister.vertrek.data.formattedDepartureTime
import com.github.stevermeister.vertrek.data.opposite
import com.github.stevermeister.vertrek.data.parsedDepartureInstant
import java.time.Clock
import java.time.Duration

// Matches the Worker's MAX_TRIPS (worker/wrangler.jsonc) — NS's own
// per-call cap, not a number either side can raise on its own.
private const val MAX_ROWS = 5

fun buildTileLayout(
    context: Context,
    deviceParameters: DeviceParameters,
    direction: Direction,
    cacheState: CacheState,
    clock: Clock,
): LayoutElement =
    materialScope(context = context, deviceConfiguration = deviceParameters, allowDynamicTheme = false) {
        primaryLayout(
            titleSlot = { header(direction, cacheState, clock) },
            mainSlot = { mainContent(cacheState, clock) },
            onClick = clickable(action = launchMainActivity(context)),
        )
    }

private fun launchMainActivity(context: Context): ActionBuilders.Action =
    ActionBuilders.launchAction(ComponentName(context, MainActivity::class.java))

/**
 * Full station names on one line: the first name and the arrow always
 * render in full; only the trailing name (and, if stale, its age suffix)
 * ellipsizes if there isn't room. A small swap icon sits at the end —
 * not a large button.
 */
private fun MaterialScope.header(direction: Direction, cacheState: CacheState, clock: Clock): LayoutElement {
    val stationNames = stationNamesOrNull(cacheState)
    val fromName = stationNames?.first ?: "–"
    var toName = stationNames?.second ?: "–"
    if (cacheState is CacheState.Stale) {
        toName = "$toName · ${cacheState.data.ageMinutes(clock)}m old"
    }

    val swapClickable = clickable(action = loadAction(), id = swapClickableId(direction.opposite()))

    return LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.expand())
        .addContent(text("$fromName → ".layoutString, typography = Typography.LABEL_SMALL, maxLines = 1))
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(
                    text(
                        toName.layoutString,
                        typography = Typography.LABEL_SMALL,
                        maxLines = 1,
                        overflow = LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE,
                    ),
                )
                .build(),
        )
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(Modifiers.Builder().setClickable(swapClickable).build())
                .addContent(text("⇄".layoutString, typography = Typography.LABEL_SMALL))
                .build(),
        )
        .build()
}

private fun stationNamesOrNull(cacheState: CacheState): Pair<String, String>? =
    when (cacheState) {
        is CacheState.Fresh -> cacheState.data.fromStationName to cacheState.data.toStationName
        is CacheState.Stale -> cacheState.data.fromStationName to cacheState.data.toStationName
        is CacheState.NoData -> null
    }

private fun MaterialScope.mainContent(cacheState: CacheState, clock: Clock): LayoutElement =
    when (cacheState) {
        is CacheState.Fresh -> tripsColumn(cacheState.data, clock)
        is CacheState.Stale -> tripsColumn(cacheState.data, clock)
        is CacheState.NoData -> noDataContent(cacheState.reason)
    }

private fun MaterialScope.tripsColumn(data: CachedTripsData, clock: Clock): LayoutElement {
    val shown = data.trips.take(MAX_ROWS)
    if (shown.isEmpty()) {
        return text("No upcoming trips".layoutString, typography = Typography.BODY_SMALL, maxLines = 1)
    }

    val column = LayoutElementBuilders.Column.Builder()
    shown.forEachIndexed { index, trip -> column.addContent(tripRow(trip, isFirstRow = index == 0, clock)) }
    return column.build()
}

private fun MaterialScope.tripRow(trip: TripDto, isFirstRow: Boolean, clock: Clock): LayoutElement {
    val zonesRowBuilder =
        LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .addContent(timesBlock(trip))
            .addContent(spacer(DimensionBuilders.expand()))
            .addContent(trackChip(trip.track))

    val dots = crowdDots(trip.crowdForecast)
    if (dots != null) {
        zonesRowBuilder.addContent(spacer(DimensionBuilders.dp(4f)))
        zonesRowBuilder.addContent(dots)
    }
    val zonesRow = zonesRowBuilder.build()

    if (!isFirstRow) return zonesRow

    return LayoutElementBuilders.Column.Builder()
        .addContent(text(minutesUntilLabel(trip, clock).layoutString, typography = Typography.DISPLAY_SMALL, maxLines = 1))
        .addContent(zonesRow)
        .build()
}

/** "HH:mm +N - HH:mm", delay inline in the error colour. Struck through when cancelled. */
private fun MaterialScope.timesBlock(trip: TripDto): LayoutElement {
    val row = LayoutElementBuilders.Row.Builder()
    row.addContent(text(trip.formattedDepartureTime().layoutString, typography = Typography.BODY_SMALL, maxLines = 1))
    if (trip.delayMinutes > 0) {
        row.addContent(
            text(" +${trip.delayMinutes}".layoutString, typography = Typography.BODY_SMALL, color = colorScheme.error, maxLines = 1),
        )
    }
    row.addContent(text(" - ${trip.formattedArrivalTime()}".layoutString, typography = Typography.BODY_SMALL, maxLines = 1))
    val content = row.build()
    return if (trip.cancelled) strikethroughOverlay(content) else content
}

/**
 * protolayout's Text has no strikethrough property (only underline), so
 * this overlays a thin line across the vertical middle of the content —
 * the only way to get a real strikethrough rather than just a marker.
 * Not verified on a real device/renderer.
 */
private fun MaterialScope.strikethroughOverlay(content: LayoutElement): LayoutElement =
    LayoutElementBuilders.Box.Builder()
        .addContent(content)
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.dp(1f))
                .setModifiers(LayoutModifier.background(colorScheme.onSurfaceVariant).toProtoLayoutModifiers())
                .build(),
        )
        .build()

/** Platform number in a small outlined box — no "Track" label. */
private fun MaterialScope.trackChip(track: String?): LayoutElement =
    text(
        (track ?: "–").layoutString,
        typography = Typography.LABEL_SMALL,
        maxLines = 1,
        modifier =
            LayoutModifier
                .border(width = 1f, color = colorScheme.outline)
                .clip(3f)
                .padding(horizontal = 4f, vertical = 1f),
    )

/**
 * Three small dots, filled 1/2/3 for LOW/MEDIUM/HIGH. UNKNOWN renders
 * nothing at all — no element, not even an empty placeholder.
 */
private fun MaterialScope.crowdDots(crowdForecast: String): LayoutElement? {
    val filledCount =
        when (crowdForecast) {
            "LOW" -> 1
            "MEDIUM" -> 2
            "HIGH" -> 3
            else -> 0
        }
    if (filledCount == 0) return null

    val row = LayoutElementBuilders.Row.Builder()
    for (i in 0 until 3) {
        if (i > 0) row.addContent(spacer(DimensionBuilders.dp(2f)))
        val filled = i < filledCount
        row.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(4f))
                .setHeight(DimensionBuilders.dp(4f))
                .setModifiers(
                    LayoutModifier
                        .background(if (filled) colorScheme.primary else colorScheme.outlineVariant)
                        .clip(2f)
                        .toProtoLayoutModifiers(),
                )
                .build(),
        )
    }
    return row.build()
}

private fun spacer(width: DimensionBuilders.ContainerDimension): LayoutElement =
    LayoutElementBuilders.Box.Builder().setWidth(width).build()

private fun MaterialScope.noDataContent(reason: NoDataReason): LayoutElement {
    val (message, detail) =
        when (reason) {
            NoDataReason.NEVER_FETCHED -> "No data yet" to "Waiting for first sync"
            NoDataReason.AUTH_REJECTED -> "No data" to "Auth rejected"
            NoDataReason.NETWORK_DOWN -> "No data" to "Network unavailable"
        }

    return LayoutElementBuilders.Column.Builder()
        .addContent(text(message.layoutString, typography = Typography.TITLE_SMALL, maxLines = 1))
        .addContent(text(detail.layoutString, typography = Typography.BODY_SMALL, maxLines = 1))
        .build()
}

private fun minutesUntilLabel(trip: TripDto, clock: Clock): String {
    val departure = trip.parsedDepartureInstant() ?: return "--"
    val minutes = Duration.between(clock.instant(), departure).toMinutes().coerceAtLeast(0)
    return "$minutes min"
}
