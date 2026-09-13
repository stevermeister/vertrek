package com.github.stevermeister.vertrek

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tiles.TileService
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.formattedDepartureTime
import com.github.stevermeister.vertrek.data.tripsDataStore
import com.github.stevermeister.vertrek.tile.VertrekTileService
import com.github.stevermeister.vertrek.ui.TripsBody
import com.github.stevermeister.vertrek.ui.TripsUiState
import com.github.stevermeister.vertrek.ui.TripsViewModel
import com.github.stevermeister.vertrek.work.RefreshWorker

// Matches the Worker's MAX_TRIPS (worker/wrangler.jsonc), which is the
// real ceiling on how many trips the cache can ever contain.
private const val MAX_ROWS_SHOWN = 6

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
        item { DirectionHeader(state.direction, state.isRefreshing, onSwap, onRefresh) }

        when (val body = state.body) {
            is TripsBody.Fresh -> items(body.trips.take(MAX_ROWS_SHOWN)) { TripRow(it) }
            is TripsBody.Stale -> {
                item { Text("Data is ${body.ageMinutes} min old") }
                items(body.trips.take(MAX_ROWS_SHOWN)) { TripRow(it) }
            }
            is TripsBody.NoData -> item { NoDataMessage(body.reason) }
            TripsBody.Empty -> item { Text("No upcoming trips") }
        }
    }
}

@Composable
private fun DirectionHeader(
    direction: Direction,
    isRefreshing: Boolean,
    onSwap: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Button(onClick = onSwap) {
            Text(
                when (direction) {
                    Direction.AB -> "${BuildConfig.STATION_A} → ${BuildConfig.STATION_B}"
                    Direction.BA -> "${BuildConfig.STATION_B} → ${BuildConfig.STATION_A}"
                },
            )
        }
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
        } else {
            Button(onClick = onRefresh) { Text("⟳") }
        }
    }
}

@Composable
private fun TripRow(trip: TripDto) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(trip.formattedDepartureTime())
            Text(
                when {
                    trip.cancelled -> "Cancelled"
                    trip.delayMinutes > 0 -> "+${trip.delayMinutes}"
                    else -> "On time"
                },
            )
        }
        if (!trip.cancelled) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Track ${trip.track ?: "–"}")
                Text("${trip.durationMinutes} min")
                if (trip.transfers > 0) Text("${trip.transfers} chg")
            }
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
