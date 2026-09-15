package com.github.stevermeister.vertrek.data

import com.github.stevermeister.vertrek.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import kotlinx.serialization.json.Json

private const val VERTREK_KEY_HEADER = "X-Vertrek-Key"

/** Distinct outcomes instead of a generic failure — callers need to react differently to each. */
sealed interface WorkerOutcome {
    data class Success(val response: NextResponseDto) : WorkerOutcome
    data class Unauthorized(val message: String?) : WorkerOutcome
    data class ServerMisconfigured(val message: String?) : WorkerOutcome
    data class HttpError(val status: Int, val message: String?) : WorkerOutcome
    data class NetworkFailure(val cause: Throwable) : WorkerOutcome
}

interface WorkerClient {
    suspend fun fetchNext(direction: Direction): WorkerOutcome
}

// Sourced from local.properties via BuildConfig (see README Setup step 3)
// so these can be tuned per-network without editing code — the original
// single 5s REQUEST_TIMEOUT_MILLIS was too tight over a phone Bluetooth/
// hotspot companion link and masked itself as "Network Unavailable".
fun createWorkerHttpClient(engine: HttpClientEngine = CIO.create()): HttpClient =
    HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = BuildConfig.REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = BuildConfig.CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = BuildConfig.SOCKET_TIMEOUT_MILLIS
        }
        expectSuccess = false
    }

class KtorWorkerClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : WorkerClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchNext(direction: Direction): WorkerOutcome {
        val response =
            try {
                requestOnce(direction)
            } catch (e: IOException) {
                // One retry, network failure only — a received HTTP response
                // (any status) never reaches this catch block at all, and
                // IOException can't catch a coroutine CancellationException
                // (unrelated hierarchies), so cancellation still propagates.
                try {
                    requestOnce(direction)
                } catch (retryFailure: IOException) {
                    return WorkerOutcome.NetworkFailure(retryFailure)
                }
            }
        return mapResponse(response)
    }

    private suspend fun requestOnce(direction: Direction): HttpResponse =
        httpClient.get(baseUrl.trimEnd('/') + "/next") {
            parameter("dir", direction.paramValue)
            header(VERTREK_KEY_HEADER, apiKey)
        }

    private suspend fun mapResponse(response: HttpResponse): WorkerOutcome {
        val status = response.status.value
        if (status == 200) {
            val body = response.bodyAsText()
            return runCatching { json.decodeFromString<NextResponseDto>(body) }
                .fold(
                    onSuccess = { WorkerOutcome.Success(it) },
                    onFailure = { WorkerOutcome.HttpError(status, "Malformed response body") },
                )
        }

        val message = errorMessageOrNull(response)
        return when (status) {
            401 -> WorkerOutcome.Unauthorized(message)
            500 -> WorkerOutcome.ServerMisconfigured(message)
            else -> WorkerOutcome.HttpError(status, message)
        }
    }

    private suspend fun errorMessageOrNull(response: HttpResponse): String? {
        val body = runCatching { response.bodyAsText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<WorkerErrorBody>(body).error.message }.getOrNull()
    }
}
