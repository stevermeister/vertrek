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
import java.time.Clock

// Matches the Worker's MAX_TRIPS (worker/wrangler.jsonc) — four rows, a
// row-layout choice, not NS's own 5-per-call cap.
private const val MAX_ROWS = 4

fun buildTileLayout(
    context: Context,
    deviceParameters: DeviceParameters,
    direction: Direction,
    cacheState: CacheState,
    clock: Clock,
): LayoutElement =
    materialScope(context = context, deviceConfiguration = deviceParameters, allowDynamicTheme = false) {
        primaryLayout(
            titleSlot = { swapIcon(direction) },
            mainSlot = { mainContent(cacheState, clock) },
            onClick = clickable(action = launchMainActivity(context)),
        )
    }

private fun launchMainActivity(context: Context): ActionBuilders.Action =
    ActionBuilders.launchAction(ComponentName(context, MainActivity::class.java))

/**
 * Small swap icon only — material3's titleSlot wraps its content in its
 * own header layout sized for a short title, not a full route string;
 * putting "$fromName → $toName" there truncated to a couple of
 * characters ("Al…") no matter how the text itself was built. The full
 * station-name header lives in mainSlot instead, as an ordinary row with
 * the same explicit expand() width as the trip rows below it.
 */
private fun MaterialScope.swapIcon(direction: Direction): LayoutElement {
    val swapClickable = clickable(action = loadAction(), id = swapClickableId(direction.opposite()))
    return LayoutElementBuilders.Box.Builder()
        .setModifiers(Modifiers.Builder().setClickable(swapClickable).build())
        .addContent(text("⇄".layoutString, typography = Typography.LABEL_SMALL))
        .build()
}

/**
 * Full station names on one line, ellipsized as a whole (truncating from
 * the end lands on the trailing name, which is the desired behaviour)
 * rather than two separately-sized texts — the two-text/weight()-style
 * split used on MainActivity hit its own, unrelated rendering issue here.
 */
private fun MaterialScope.header(cacheState: CacheState, clock: Clock): LayoutElement {
    val stationNames = stationNamesOrNull(cacheState)
    val fromName = stationNames?.first ?: "–"
    var toName = stationNames?.second ?: "–"
    if (cacheState is CacheState.Stale) {
        toName = "$toName · ${cacheState.data.ageMinutes(clock)}m old"
    }

    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
        .addContent(
            text(
                "$fromName → $toName".layoutString,
                typography = Typography.LABEL_SMALL,
                maxLines = 1,
                overflow = LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE,
            ),
        )
        .build()
}

private fun stationNamesOrNull(cacheState: CacheState): Pair<String, String>? =
    when (cacheState) {
        is CacheState.Fresh -> cacheState.data.fromStationName to cacheState.data.toStationName
        is CacheState.Stale -> cacheState.data.fromStationName to cacheState.data.toStationName
        is CacheState.NoData -> null
    }

private fun MaterialScope.mainContent(cacheState: CacheState, clock: Clock): LayoutElement {
    val column = LayoutElementBuilders.Column.Builder().setWidth(DimensionBuilders.expand())
    column.addContent(header(cacheState, clock))
    when (cacheState) {
        is CacheState.Fresh -> column.addContent(tripsColumn(cacheState.data))
        is CacheState.Stale -> column.addContent(tripsColumn(cacheState.data))
        is CacheState.NoData -> column.addContent(noDataContent(cacheState.reason))
    }
    return column.build()
}

private fun MaterialScope.tripsColumn(data: CachedTripsData): LayoutElement {
    val shown = data.trips.take(MAX_ROWS)
    if (shown.isEmpty()) {
        return text("No upcoming trips".layoutString, typography = Typography.BODY_SMALL, maxLines = 1)
    }

    // Explicit width required: each row is a Row with setWidth(expand()),
    // and protolayout's real renderer (unlike Robolectric's test renderer)
    // refuses to inflate an expand()-width child inside a wrap-width
    // parent at all — "Column set to wrap but contents are unmeasurable" —
    // silently dropping the whole column rather than just the row.
    val column = LayoutElementBuilders.Column.Builder().setWidth(DimensionBuilders.expand())
    shown.forEach { trip -> column.addContent(tripRow(trip)) }
    return column.build()
}

private fun MaterialScope.tripRow(trip: TripDto): LayoutElement {
    val rowBuilder =
        LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .addContent(timesBlock(trip))
            .addContent(spacer(DimensionBuilders.expand()))
            .addContent(trackChip(trip.track))

    val dots = crowdDots(trip.crowdForecast)
    if (dots != null) {
        rowBuilder.addContent(spacer(DimensionBuilders.dp(4f)))
        rowBuilder.addContent(dots)
    }
    return rowBuilder.build()
}

/**
 * Departure time is primary — larger, full weight — with the delay
 * marker riding alongside it in the error colour. Arrival time is
 * secondary: smaller and dimmer, after a separator dot. Both come from
 * the Worker's planned departureTime (not the already-delay-adjusted
 * actual time — see the comment on toCompactTrip() in worker/src/ns.ts),
 * so "+N" here is additive on top of the displayed time, not a double
 * count. Struck through when cancelled.
 */
private fun MaterialScope.timesBlock(trip: TripDto): LayoutElement {
    val row = LayoutElementBuilders.Row.Builder()
    row.addContent(
        text(trip.formattedDepartureTime().layoutString, typography = Typography.TITLE_MEDIUM, maxLines = 1),
    )
    if (trip.delayMinutes > 0) {
        row.addContent(
            text(
                " +${trip.delayMinutes}".layoutString,
                typography = Typography.TITLE_MEDIUM,
                color = colorScheme.error,
                maxLines = 1,
            ),
        )
    }
    row.addContent(
        text(" · ".layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1),
    )
    row.addContent(
        text(
            trip.formattedArrivalTime().layoutString,
            typography = Typography.BODY_SMALL,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
        ),
    )
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
