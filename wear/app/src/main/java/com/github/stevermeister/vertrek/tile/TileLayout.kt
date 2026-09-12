package com.github.stevermeister.vertrek.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.protolayout.types.layoutString
import com.github.stevermeister.vertrek.BuildConfig
import com.github.stevermeister.vertrek.MainActivity
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Id of the header's swap Clickable — read back via TileRequest.currentState.lastClickableId. */
const val SWAP_CLICKABLE_ID = "swap_direction"

private val DEPARTURE_TIME_PARSER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val MAX_ROWS = 3

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

private fun MaterialScope.header(direction: Direction, cacheState: CacheState, clock: Clock): LayoutElement {
    val directionText =
        when (direction) {
            Direction.AB -> "${BuildConfig.STATION_A} → ${BuildConfig.STATION_B}"
            Direction.BA -> "${BuildConfig.STATION_B} → ${BuildConfig.STATION_A}"
        }
    val label =
        if (cacheState is CacheState.Stale) {
            "$directionText · ${formatAge(cacheState.data, clock)}"
        } else {
            directionText
        }

    // No onClick lambda: a LoadAction re-invokes onTileRequest, which reads
    // this id back from currentState.lastClickableId.
    val swapClickable = clickable(action = loadAction(), id = SWAP_CLICKABLE_ID)

    return LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.expand())
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(text(label.layoutString, typography = Typography.LABEL_SMALL, maxLines = 1))
                .build(),
        )
        .addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(swapClickable).build())
                .addContent(text("⇄".layoutString, typography = Typography.LABEL_SMALL))
                .build(),
        )
        .build()
}

private fun MaterialScope.mainContent(cacheState: CacheState, clock: Clock): LayoutElement =
    when (cacheState) {
        is CacheState.Fresh -> tripsCard(cacheState.data, clock)
        is CacheState.Stale -> tripsCard(cacheState.data, clock)
        is CacheState.NoData -> noDataContent(cacheState.reason)
    }

private fun MaterialScope.tripsCard(data: CachedTripsData, clock: Clock): LayoutElement {
    val trips = data.trips.take(MAX_ROWS)
    val first = trips.firstOrNull()
    val rest = trips.drop(1)

    return titleCard(
        onClick = clickable(action = launchMainActivity(context)),
        title = {
            text(
                (first?.let { minutesUntilLabel(it, clock) } ?: "–").layoutString,
                typography = Typography.DISPLAY_SMALL,
                maxLines = 1,
            )
        },
        content = {
            LayoutElementBuilders.Column.Builder()
                .apply {
                    if (first != null) addContent(secondaryLine(first))
                    rest.forEach { addContent(tripRow(it)) }
                }
                .build()
        },
        height = DimensionBuilders.wrap(),
    )
}

/** The first trip's secondary detail line: HH:mm and track under the big "N min" figure. */
private fun MaterialScope.secondaryLine(trip: TripDto): LayoutElement =
    LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.expand())
        .addContent(text(formatRowLabel(trip), typography = Typography.BODY_SMALL, maxLines = 1))
        .build()

/** A plain trip row (rows 2-3): HH:mm, "+N" delay when present, track right-aligned. */
private fun MaterialScope.tripRow(trip: TripDto): LayoutElement {
    val row = LayoutElementBuilders.Row.Builder().setWidth(DimensionBuilders.expand())

    if (trip.cancelled) {
        row.addContent(text(formatTime(trip).layoutString, typography = Typography.BODY_SMALL))
        row.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_END)
                .addContent(text("Cancelled".layoutString, typography = Typography.BODY_SMALL, color = colorScheme.error))
                .build(),
        )
        return row.build()
    }

    row.addContent(text(formatTime(trip).layoutString, typography = Typography.BODY_SMALL))
    if (trip.delayMinutes > 0) {
        row.addContent(
            text(
                "+${trip.delayMinutes}".layoutString,
                typography = Typography.BODY_SMALL,
                color = colorScheme.error,
            ),
        )
    }
    row.addContent(
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_END)
            .addContent(text((trip.track ?: "–").layoutString, typography = Typography.BODY_SMALL))
            .build(),
    )
    return row.build()
}

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

private fun formatRowLabel(trip: TripDto): androidx.wear.protolayout.types.LayoutString =
    "${formatTime(trip)}  ${trip.track ?: "–"}".layoutString

private fun formatTime(trip: TripDto): String =
    runCatching {
        OffsetDateTime.parse(trip.departureTime, DEPARTURE_TIME_PARSER)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DISPLAY_TIME_FORMATTER)
    }.getOrDefault("--:--")

private fun minutesUntilLabel(trip: TripDto, clock: Clock): String {
    val departure =
        runCatching { OffsetDateTime.parse(trip.departureTime, DEPARTURE_TIME_PARSER).toInstant() }
            .getOrNull() ?: return "--"
    val minutes = Duration.between(clock.instant(), departure).toMinutes().coerceAtLeast(0)
    return "$minutes min"
}

private fun formatAge(data: CachedTripsData, clock: Clock): String {
    // Rendered only for CacheState.Stale, whose age is already clamped to
    // [3, 15] minutes by cacheStateOf, so a plain minute count is enough.
    val ageMinutes =
        Duration.between(java.time.Instant.ofEpochMilli(data.fetchedAtEpochMillis), clock.instant()).toMinutes()
    return "$ageMinutes min old"
}
