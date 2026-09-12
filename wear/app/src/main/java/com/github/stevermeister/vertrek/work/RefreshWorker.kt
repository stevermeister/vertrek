package com.github.stevermeister.vertrek.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.stevermeister.vertrek.BuildConfig
import com.github.stevermeister.vertrek.data.CachedTripsData
import com.github.stevermeister.vertrek.data.KtorWorkerClient
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.WorkerOutcome
import com.github.stevermeister.vertrek.data.createWorkerHttpClient
import com.github.stevermeister.vertrek.data.resolveDirection
import com.github.stevermeister.vertrek.data.tripsDataStore
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
            val direction = resolveDirection(repository.getDirectionOverride(), Clock.systemDefaultZone())

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

            when (outcome) {
                is WorkerOutcome.Success -> {
                    repository.saveCached(
                        direction,
                        CachedTripsData(
                            direction = direction.paramValue,
                            trips = outcome.response.trips,
                            fetchedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                    Result.success()
                }
                // A network hiccup is worth WorkManager's own retry; a bad
                // key or a misconfigured Worker won't fix itself by retrying.
                is WorkerOutcome.NetworkFailure -> Result.retry()
                is WorkerOutcome.Unauthorized,
                is WorkerOutcome.ServerMisconfigured,
                is WorkerOutcome.HttpError,
                -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "vertrek_refresh"

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
