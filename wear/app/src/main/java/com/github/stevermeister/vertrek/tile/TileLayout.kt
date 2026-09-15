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
            mainSlot = { mainContent(direction, cacheState, clock) },
            onClick = clickable(action = launchMainActivity(context)),
        )
    }

private fun launchMainActivity(context: Context): ActionBuilders.Action =
    ActionBuilders.launchAction(ComponentName(context, MainActivity::class.java))

// Default clickable() minimum touch target (Material's standard ~48dp
// accessible tap target) was consuming as much header row width as the
// entire ORIGIN_HEADER_WIDTH budget below — it, not the origin, was why
// the destination still didn't fit after tightening the origin down to
// where it vanished with no gain. A small icon in a glance-only tile
// (the primary swap gesture lives on MainActivity) doesn't need the
// full accessible minimum.
private const val SWAP_ICON_TOUCH_TARGET_DP = 18f

/**
 * Small swap icon, inline at the end of the header row — not
 * titleSlot, which reserved its own line above the header for it and
 * wasted vertical space no other row got to use.
 */
private fun MaterialScope.swapIcon(direction: Direction): LayoutElement {
    val swapClickable =
        clickable(
            loadAction(),
            swapClickableId(direction.opposite()),
            SWAP_ICON_TOUCH_TARGET_DP,
            SWAP_ICON_TOUCH_TARGET_DP,
        )
    return LayoutElementBuilders.Box.Builder()
        .setModifiers(Modifiers.Builder().setClickable(swapClickable).build())
        .addContent(text("⇄".layoutString, typography = Typography.LABEL_SMALL))
        .build()
}

/**
 * Origin only — no arrow, no destination. The destination is implied by
 * which cached direction is showing; the only thing that changes at a
 * glance is where you're leaving from. Comes straight from the Worker's
 * fromStationShort (see STATION_A_SHORT/STATION_B_SHORT in the README),
 * short enough by construction that maxLines=1 + ellipsize is a safety
 * net, not a load-bearing mechanism. The swap icon stays inline, pinned
 * to the row's end by the origin text's own expand() width.
 */
private fun MaterialScope.header(direction: Direction, cacheState: CacheState, clock: Clock): LayoutElement {
    var fromName = shortFromStationNameOrNull(cacheState) ?: "–"
    if (cacheState is CacheState.Stale) {
        fromName = "$fromName · ${cacheState.data.ageMinutes(clock)}m old"
    }

    return LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.expand())
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(
                    text(
                        fromName.layoutString,
                        typography = Typography.LABEL_SMALL,
                        maxLines = 1,
                        overflow = LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE,
                    ),
                )
                .build(),
        )
        .addContent(swapIcon(direction))
        .build()
}

private fun shortFromStationNameOrNull(cacheState: CacheState): String? =
    when (cacheState) {
        is CacheState.Fresh -> cacheState.data.fromStationShort
        is CacheState.Stale -> cacheState.data.fromStationShort
        is CacheState.NoData -> null
    }

// Vertical gap between the header and the first row, and between each
// pair of rows — matches MainActivity's row rhythm (its TripRow uses
// 4dp top+bottom padding, an 8dp gap edge-to-edge between rows).
private val ROW_GAP = DimensionBuilders.dp(8f)

private fun MaterialScope.mainContent(direction: Direction, cacheState: CacheState, clock: Clock): LayoutElement {
    val column = LayoutElementBuilders.Column.Builder().setWidth(DimensionBuilders.expand())
    column.addContent(header(direction, cacheState, clock))
    column.addContent(verticalSpacer(ROW_GAP))
    when (cacheState) {
        is CacheState.Fresh -> column.addContent(tripsColumn(cacheState.data))
        is CacheState.Stale -> column.addContent(tripsColumn(cacheState.data))
        is CacheState.NoData -> column.addContent(noDataContent(cacheState.reason))
    }

    // The content block is naturally shorter than the tile now (four
    // compact rows, no more dominant "N min" figure eating vertical
    // space) — centering it in the available height uses the leftover
    // room instead of leaving it pinned to the top with empty space
    // below, on both 454 and 384.
    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(column.build())
        .build()
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
    shown.forEachIndexed { index, trip ->
        if (index > 0) column.addContent(verticalSpacer(ROW_GAP))
        column.addContent(tripRow(trip))
    }
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
        // MainActivity uses the same nominal 4dp gap here and it reads
        // fine there; on the tile's renderer, at this size, 4dp rendered
        // as the dots touching the track chip with no visible gap at all.
        // Doubled rather than chasing why the same value looks different
        // across the two renderers.
        rowBuilder.addContent(spacer(DimensionBuilders.dp(8f)))
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
 *
 * Filled = solid bright dot; unfilled = hollow outline ring — a real
 * fill-vs-outline distinction, not two shades of grey (which read as
 * identical at a glance at this size).
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
        if (i > 0) row.addContent(spacer(DimensionBuilders.dp(3f)))
        val filled = i < filledCount
        val dotModifier =
            if (filled) {
                LayoutModifier.background(colorScheme.onSurface).clip(2.5f)
            } else {
                LayoutModifier.border(width = 1f, color = colorScheme.outline).clip(2.5f)
            }
        row.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(5f))
                .setHeight(DimensionBuilders.dp(5f))
                .setModifiers(dotModifier.toProtoLayoutModifiers())
                .build(),
        )
    }
    return row.build()
}

// Root cause of a real, verified "Box set to wrap but contents are
// unmeasurable" warning on-device: an empty Box with one dimension left
// at the default WRAP and zero children has nothing for the real
// ProtoLayoutInflater to measure that axis against — it isn't just
// benign library noise (confirmed by bisection: removing the missing
// setHeight()/setWidth() call below eliminated the warning entirely on
// a real emulator render). The cross axis of a spacer is never meant to
// be flexible, so pin it to 0dp instead of leaving it WRAP.
private fun spacer(width: DimensionBuilders.ContainerDimension): LayoutElement =
    LayoutElementBuilders.Box.Builder().setWidth(width).setHeight(DimensionBuilders.dp(0f)).build()

private fun verticalSpacer(height: DimensionBuilders.ContainerDimension): LayoutElement =
    LayoutElementBuilders.Box.Builder().setHeight(height).setWidth(DimensionBuilders.dp(0f)).build()

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
