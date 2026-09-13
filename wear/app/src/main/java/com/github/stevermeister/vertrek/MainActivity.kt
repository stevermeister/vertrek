package com.github.stevermeister.vertrek

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tiles.TileService
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.formattedArrivalTime
import com.github.stevermeister.vertrek.data.formattedDepartureTime
import com.github.stevermeister.vertrek.data.parsedDepartureInstant
import com.github.stevermeister.vertrek.data.tripsDataStore
import com.github.stevermeister.vertrek.tile.VertrekTileService
import com.github.stevermeister.vertrek.ui.TripsBody
import com.github.stevermeister.vertrek.ui.TripsUiState
import com.github.stevermeister.vertrek.ui.TripsViewModel
import com.github.stevermeister.vertrek.work.RefreshWorker

// Matches the Worker's MAX_TRIPS (worker/wrangler.jsonc), which is NS's
// own per-call cap on this endpoint — not a number either side can raise
// without a second NS API call (deliberately not implemented; see the
// comment above fetchTrips() in worker/src/ns.ts).
private const val MAX_ROWS_SHOWN = 5

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

@Composable
private fun ContentScreen(state: TripsUiState.Content, onSwap: () -> Unit, onRefresh: () -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            DirectionHeader(
                fromStationName = state.fromStationName,
                toStationName = state.toStationName,
                isRefreshing = state.isRefreshing,
                onSwap = onSwap,
                onRefresh = onRefresh,
            )
        }

        when (val body = state.body) {
            is TripsBody.Fresh ->
                itemsIndexed(body.trips.take(MAX_ROWS_SHOWN)) { index, trip -> TripRow(trip, isFirstRow = index == 0) }
            is TripsBody.Stale -> {
                item { Text("Data is ${body.ageMinutes} min old") }
                itemsIndexed(body.trips.take(MAX_ROWS_SHOWN)) { index, trip -> TripRow(trip, isFirstRow = index == 0) }
            }
            is TripsBody.NoData -> item { NoDataMessage(body.reason) }
            TripsBody.Empty -> item { Text("No upcoming trips") }
        }
    }
}

@Composable
private fun DirectionHeader(
    fromStationName: String?,
    toStationName: String?,
    isRefreshing: Boolean,
    onSwap: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The first name and arrow always render in full; only the second
        // name shrinks/ellipsizes if there isn't room. No filled pill.
        Text("${fromStationName ?: "–"} → ", maxLines = 1)
        Text(
            toStationName ?: "–",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Small icon, not a large button.
        Text(
            "⇄",
            modifier = Modifier.clickable(onClick = onSwap).padding(horizontal = 6.dp),
        )
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        } else {
            Text(
                "⟳",
                modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun TripRow(trip: TripDto, isFirstRow: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isFirstRow) {
            Text(minutesUntilLabel(trip), style = MaterialTheme.typography.displaySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimesBlock(trip, modifier = Modifier.weight(1f))
            TrackChip(trip.track)
            CrowdDots(trip.crowdForecast, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** "HH:mm +N - HH:mm", delay inline in the error colour, struck through when cancelled. */
@Composable
private fun TimesBlock(trip: TripDto, modifier: Modifier = Modifier) {
    val decoration = if (trip.cancelled) TextDecoration.LineThrough else TextDecoration.None
    Row(modifier = modifier) {
        Text(trip.formattedDepartureTime(), textDecoration = decoration, maxLines = 1)
        if (trip.delayMinutes > 0) {
            Text(
                " +${trip.delayMinutes}",
                color = MaterialTheme.colorScheme.error,
                textDecoration = decoration,
                maxLines = 1,
            )
        }
        Text(" - ${trip.formattedArrivalTime()}", textDecoration = decoration, maxLines = 1)
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

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            val filled = index < filledCount
            Box(
                modifier =
                    Modifier
                        .size(4.dp)
                        .background(
                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(2.dp),
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

private fun minutesUntilLabel(trip: TripDto): String {
    val departure = trip.parsedDepartureInstant() ?: return "-- min"
    val minutes =
        java.time.Duration.between(java.time.Instant.now(), departure).toMinutes().coerceAtLeast(0)
    return "$minutes min"
}
