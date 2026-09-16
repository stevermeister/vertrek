package com.github.stevermeister.vertrek.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.stevermeister.vertrek.BuildConfig
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.KtorWorkerClient
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.WorkerOutcome
import com.github.stevermeister.vertrek.data.createWorkerHttpClient
import com.github.stevermeister.vertrek.data.resolveDirection
import com.github.stevermeister.vertrek.data.tripsDataStore
import com.github.stevermeister.vertrek.tile.VertrekTileService
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Fetches the current direction's trips and writes them to DataStore.
 * Never throws: any failure becomes a Result, not a crash.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val repository = TripsRepository(applicationContext.tripsDataStore)
            val clock = Clock.systemDefaultZone()
            val direction = resolveWorkDirection(inputData, repository.getDirectionOverride(clock), clock)

            logActiveNetwork(applicationContext)

            val httpClient = createWorkerHttpClient()
            val outcome =
                try {
                    KtorWorkerClient(
                        httpClient = httpClient,
                        baseUrl = BuildConfig.NS_WORKER_BASE_URL,
                        apiKey = BuildConfig.VERTREK_API_KEY,
                    ).fetchNext(direction)
                } finally {
                    httpClient.close()
                }

            val result =
                when (outcome) {
                    is WorkerOutcome.Success -> {
                        repository.setLastFailureReason(direction, null)
                        repository.saveCached(
                            direction,
                            CachedTripsData(
                                direction = direction.paramValue,
                                fromStationName = outcome.response.fromStationName,
                                toStationName = outcome.response.toStationName,
                                fromStationShort =
                                    outcome.response.fromStationShort.ifBlank { outcome.response.fromStationName },
                                toStationShort =
                                    outcome.response.toStationShort.ifBlank { outcome.response.toStationName },
                                trips = outcome.response.trips,
                                fetchedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        )
                        Result.success()
                    }
                    is WorkerOutcome.Unauthorized -> {
                        Log.w(TAG, "Worker rejected the request: 401 Unauthorized, body=${outcome.message}")
                        repository.setLastFailureReason(direction, NoDataReason.AUTH_REJECTED)
                        Result.failure()
                    }
                    is WorkerOutcome.ServerMisconfigured -> {
                        Log.w(TAG, "Worker is misconfigured: 500, body=${outcome.message}")
                        repository.setLastFailureReason(direction, NoDataReason.AUTH_REJECTED)
                        Result.failure()
                    }
                    is WorkerOutcome.HttpError -> {
                        Log.w(TAG, "Worker returned an unexpected status: ${outcome.status}, body=${outcome.message}")
                        repository.setLastFailureReason(direction, NoDataReason.NETWORK_DOWN)
                        Result.failure()
                    }
                    is WorkerOutcome.NetworkFailure -> {
                        Log.e(
                            TAG,
                            "Network failure calling the Worker: ${outcome.cause::class.qualifiedName}: " +
                                "${outcome.cause.message}",
                            outcome.cause,
                        )
                        repository.setLastFailureReason(direction, NoDataReason.NETWORK_DOWN)
                        // A network hiccup is worth WorkManager's own retry; the
                        // others won't fix themselves by retrying immediately.
                        Result.retry()
                    }
                }

            if (outcome is WorkerOutcome.Success) {
                TileService.getUpdater(applicationContext).requestUpdate(VertrekTileService::class.java)
            }

            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception in doWork(): ${e::class.qualifiedName}: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        // Single tag for every log line this class emits, network-transport
        // logging included — `adb logcat -s VertrekRefresh` gets everything
        // needed to diagnose a "No data" report without guessing.
        private const val TAG = "VertrekRefresh"
        private const val UNIQUE_WORK_NAME = "vertrek_refresh"
        internal const val KEY_DIRECTION_PARAM = "direction_param"

        /**
         * [direction], when given, is fetched exactly as-is — no re-resolving
         * against the DataStore override inside doWork(). This closes a real
         * race: VertrekTileService.onTileRequest() persists a tapped
         * direction via a fire-and-forget `ioScope.launch { setDirectionOverride(...) }`
         * and immediately calls this, with no ordering guarantee that the
         * write lands before doWork() would have read it back. Re-resolving
         * independently could fetch/cache the *previous* direction —
         * leaving the one actually on screen still stale, forcing one more
         * enqueue on the next request before it self-corrects. Passing the
         * already-decided direction through removes the second, independent
         * resolution entirely, not just narrows its timing window.
         *
         * Left null for callers with no tap-derived direction of their own
         * (MainActivity's pull-to-refresh) — doWork() falls back to the
         * plain override-or-time-of-day resolution, which is exactly what
         * that caller's own displayed direction already uses.
         */
        fun enqueue(context: Context, direction: Direction? = null) {
            val inputData =
                direction?.let { Data.Builder().putString(KEY_DIRECTION_PARAM, it.paramValue).build() }
                    ?: Data.EMPTY

            val request =
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(inputData)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Package-visible for testing; see the doc on enqueue() for why this exists. */
        internal fun resolveWorkDirection(inputData: Data, override: Direction?, clock: Clock): Direction =
            inputData.getString(KEY_DIRECTION_PARAM)?.let { Direction.fromParam(it) }
                ?: resolveDirection(override, clock)

        /**
         * Logs which transports the active network reports and whether it's
         * validated (has a working path to the internet, not just a link).
         * Wear OS can route background requests over the phone's Bluetooth
         * companion proxy even when a Wi-Fi-connected adb shell would use
         * Wi-Fi directly — this makes that distinction visible in logs
         * instead of left to guessing from timing.
         */
        private fun logActiveNetwork(context: Context) {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

            if (capabilities == null) {
                Log.w(TAG, "Active network: none (no active network or capabilities unavailable)")
                return
            }

            val transports =
                buildList {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                }
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            Log.i(TAG, "Active network: transports=$transports validated=$validated ($network)")
        }
    }
}
