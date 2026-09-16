package com.github.stevermeister.vertrek

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tiles.TileService
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.formattedArrivalTime
import com.github.stevermeister.vertrek.data.formattedDepartureTime
import com.github.stevermeister.vertrek.data.tripsDataStore
import com.github.stevermeister.vertrek.tile.VertrekTileService
import com.github.stevermeister.vertrek.ui.TripsBody
import com.github.stevermeister.vertrek.ui.TripsUiState
import com.github.stevermeister.vertrek.ui.TripsViewModel
import com.github.stevermeister.vertrek.work.RefreshWorker
import kotlin.math.min

// How far (in px, converted from dp at render time) a downward pull past
// the top of the list must travel before releasing triggers a refresh.
private val PULL_TRIGGER_DP = 56.dp

// Smallest width the header ever gives the origin name, even when the
// destination needs nearly the whole row — enough for a couple of
// characters plus an ellipsis, so it never disappears outright.
private val MIN_ORIGIN_WIDTH = 28.dp
private const val PULL_RUBBER_BAND = 0.5f // drag feels heavier than 1:1, like a real pull-to-refresh

/** Tap target for the tile — shows the full list of upcoming trips. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = TripsRepository(applicationContext.tripsDataStore)
        val factory = viewModelFactory(applicationContext, repository)
        setContent {
            val viewModel: TripsViewModel = viewModel(factory = factory)
            VertrekApp(viewModel)
        }
    }
}

private fun viewModelFactory(context: Context, repository: TripsRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TripsViewModel(
                repository = repository,
                onRefreshRequested = { RefreshWorker.enqueue(context) },
                onDirectionChanged = {
                    TileService.getUpdater(context).requestUpdate(VertrekTileService::class.java)
                },
            ) as T
        }
    }

@Composable
fun VertrekApp(viewModel: TripsViewModel) {
    val state by viewModel.uiState.collectAsState()
    MaterialTheme {
        when (val current = state) {
            is TripsUiState.Loading -> LoadingScreen()
            is TripsUiState.Content ->
                ContentScreen(state = current, onSwap = viewModel::swapDirection, onRefresh = viewModel::refresh)
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Header + up to four rows, laid out directly (not a scrolling list) —
 * with only five items total there's nothing to virtualize, and a plain
 * Column sidesteps ScalingLazyColumn's center-focus scaling entirely.
 * That scaling is designed for a handful of large, editable items; on a
 * dense data list it shrank and clipped off-center rows against their
 * own scaled-down bounds. Horizontal padding here clears the round
 * bezel; the whole block is centered vertically in the circular frame.
 */
@Composable
private fun ContentScreen(state: TripsUiState.Content, onSwap: () -> Unit, onRefresh: () -> Unit) {
    var pullPx by remember { mutableFloatStateOf(0f) }
    val triggerPx = with(LocalDensity.current) { PULL_TRIGGER_DP.toPx() }

    // Hand-rolled rather than a library pull-to-refresh: neither
    // androidx.wear.compose.material3 nor the classic androidx.wear.compose
    // .material ship one (checked both, up to and including a 1.7.0 wear
    // material3 alpha — nothing named Pull*/Refresh* exists in either).
    // A plain drag detector is enough here since the screen itself never
    // scrolls — there's no competing scrollable to nest against.
    Box(
        modifier =
            Modifier.fillMaxSize().pointerInput(state.isRefreshing) {
                if (state.isRefreshing) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (pullPx >= triggerPx) onRefresh()
                        pullPx = 0f
                    },
                    onDragCancel = { pullPx = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0f) {
                            pullPx = min(pullPx + dragAmount * PULL_RUBBER_BAND, triggerPx * 1.5f)
                            change.consume()
                        }
                    },
                )
            },
    ) {
        // Arrangement.Center on a fillMaxSize() Column, not Box's own
        // contentAlignment — a Box centering a wrap-content child measures
        // that child with loose constraints first, which starves any
        // RowScope.weight() descendant (the header's ellipsizing station
        // name) of a real width and it renders as nothing at all.
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            DirectionHeader(fromStationName = state.fromStationName, toStationName = state.toStationName)

            // Everything the Worker returned, unfiltered by transfers or
            // count — this is the detail view. The tile is the one that
            // narrows to the commute-specific 2-row glance.
            when (val body = state.body) {
                is TripsBody.Fresh -> body.trips.forEach { trip -> TripRow(trip) }
                is TripsBody.Stale -> {
                    Text("Data is ${body.ageMinutes} min old", style = MaterialTheme.typography.bodySmall)
                    body.trips.forEach { trip -> TripRow(trip) }
                }
                is TripsBody.NoData -> NoDataMessage(body.reason)
                TripsBody.Empty -> Text("No upcoming trips")
            }
        }

        if (state.isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(16.dp),
            )
        } else if (pullPx > 0f) {
            Text(
                "↓",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DirectionHeader(fromStationName: String?, toStationName: String?) {
    val fromName = fromStationName ?: "–"
    val toName = toStationName ?: "–"
    val toText = " → $toName"
    val style = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // The origin is ellipsized, not the destination — you know where
    // you're leaving from, you care where you're going. A fixed
    // percentage cap on the origin isn't enough to guarantee that: for
    // a long destination it just silently clips the destination instead
    // (no ellipsis, no indication — the text is simply cut off by the
    // row's own bounds). So the destination's actual rendered width is
    // measured first, and the origin gets only whatever's left. Not
    // RowScope.weight(): weight() on this exact pair of Texts silently
    // rendered the flexible one as nothing at all under Wear Compose
    // Material3's Text.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val toWidthPx = remember(toText, style) { textMeasurer.measure(toText, style).size.width }
        val toWidthDp = with(density) { toWidthPx.toDp() }
        // Never let the origin vanish to zero width even when the
        // destination alone claims nearly the whole row (a bare arrow
        // with nothing before it reads as broken, not "prioritised") —
        // a small floor still shows a character or two before the
        // ellipsis, at the cost of the destination occasionally losing
        // its own last character or two on the narrowest screens.
        val fromNameMaxWidth = (maxWidth - toWidthDp).coerceAtLeast(MIN_ORIGIN_WIDTH)
        val toNameMaxWidth = maxWidth - fromNameMaxWidth
        Row {
            Text(
                fromName,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = fromNameMaxWidth),
            )
            Text(
                toText,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Only actually binds on the rare screen where even the
                // origin's floored minimum doesn't leave enough room for
                // the whole destination — otherwise this is >= toText's
                // own natural width and has no effect.
                modifier = Modifier.widthIn(max = toNameMaxWidth),
            )
        }
    }
}

@Composable
private fun TripRow(trip: TripDto) {
    // TimesBlock at its natural width, then a weighted Spacer (not a
    // Text) to push the track chip and crowd dots to the end — putting
    // weight() directly on TimesBlock made its arrival time vanish on
    // the smaller 384x384 screen specifically, the same Wear Compose
    // Material3 Text + weight() interaction as the header's origin/
    // destination split.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimesBlock(trip)
        Spacer(modifier = Modifier.weight(1f))
        TrackChip(trip.track)
        CrowdDots(trip.crowdForecast, modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * Departure time is primary — larger, full weight — with the delay
 * marker riding alongside it in the error colour. Arrival time is
 * secondary: smaller and dimmer, after a separator. Both come from the
 * Worker's planned departureTime (see the comment on toCompactTrip() in
 * worker/src/ns.ts), so "+N" here is additive, not a double count.
 * Struck through when cancelled.
 */
@Composable
private fun TimesBlock(trip: TripDto, modifier: Modifier = Modifier) {
    val decoration = if (trip.cancelled) TextDecoration.LineThrough else TextDecoration.None
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            trip.formattedDepartureTime(),
            style = MaterialTheme.typography.titleMedium,
            textDecoration = decoration,
            maxLines = 1,
        )
        if (trip.delayMinutes > 0) {
            Text(
                " +${trip.delayMinutes}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textDecoration = decoration,
                maxLines = 1,
            )
        }
        Text(
            " · ${trip.formattedArrivalTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = decoration,
            maxLines = 1,
        )
    }
}

/** Platform number in a small outlined box — no "Track" label. */
@Composable
private fun TrackChip(track: String?) {
    Text(
        track ?: "–",
        modifier =
            Modifier
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        maxLines = 1,
    )
}

/**
 * Three small dots, filled 1/2/3 for LOW/MEDIUM/HIGH. UNKNOWN renders
 * nothing at all — no composable, not even an empty placeholder.
 *
 * Filled = solid bright dot; unfilled = hollow outline ring — a real
 * fill-vs-outline distinction, not two shades of grey (which read as
 * identical at a glance at this size).
 */
@Composable
private fun CrowdDots(crowdForecast: String, modifier: Modifier = Modifier) {
    val filledCount =
        when (crowdForecast) {
            "LOW" -> 1
            "MEDIUM" -> 2
            "HIGH" -> 3
            else -> 0
        }
    if (filledCount == 0) return

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val filled = index < filledCount
            Box(
                modifier =
                    Modifier
                        .size(5.dp)
                        .then(
                            if (filled) {
                                Modifier.background(color = MaterialTheme.colorScheme.onSurface, shape = CircleShape)
                            } else {
                                Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
                            },
                        ),
            )
        }
    }
}

@Composable
private fun NoDataMessage(reason: NoDataReason) {
    val (message, detail) =
        when (reason) {
            NoDataReason.NEVER_FETCHED -> "No data yet" to "Waiting for first sync"
            NoDataReason.AUTH_REJECTED -> "No data" to "Auth rejected"
            NoDataReason.NETWORK_DOWN -> "No data" to "Network unavailable"
        }
    Column {
        Text(message)
        Text(detail)
    }
}
