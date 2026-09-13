package com.github.stevermeister.vertrek.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerClientTest {

    private fun clientWith(engine: HttpClientEngine): KtorWorkerClient {
        val httpClient =
            HttpClient(engine) {
                install(HttpTimeout) { requestTimeoutMillis = 5_000 }
                expectSuccess = false
            }
        return KtorWorkerClient(httpClient, "https://worker.example", "test-key")
    }

    private fun MockRequestHandleScope.jsonResponse(status: HttpStatusCode, body: String) =
        respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `retries once on network failure then succeeds`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) throw IOException("simulated network failure")
            jsonResponse(
                HttpStatusCode.OK,
                """{"dir":"ab","fromStationName":"Almere Oostvaarders","toStationName":"Amsterdam Centraal","trips":[]}""",
            )
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertEquals(2, callCount)
        assertTrue(outcome is WorkerOutcome.Success)
    }

    @Test
    fun `network failure on both attempts returns NetworkFailure after exactly one retry`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            throw IOException("simulated network failure")
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertEquals(2, callCount) // the original attempt plus exactly one retry, not more
        assertTrue(outcome is WorkerOutcome.NetworkFailure)
    }

    @Test
    fun `401 is mapped to Unauthorized and is never retried`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonResponse(HttpStatusCode.Unauthorized, """{"error":{"code":"UNAUTHORIZED","message":"bad key"}}""")
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertEquals(1, callCount)
        assertTrue(outcome is WorkerOutcome.Unauthorized)
        assertEquals("bad key", (outcome as WorkerOutcome.Unauthorized).message)
    }

    @Test
    fun `500 is mapped to ServerMisconfigured and is never retried`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            jsonResponse(
                HttpStatusCode.InternalServerError,
                """{"error":{"code":"SERVER_MISCONFIGURED","message":"VERTREK_KEY is not configured"}}""",
            )
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertEquals(1, callCount)
        assertTrue(outcome is WorkerOutcome.ServerMisconfigured)
        assertEquals("VERTREK_KEY is not configured", (outcome as WorkerOutcome.ServerMisconfigured).message)
    }

    @Test
    fun `502 falls into the generic HttpError bucket`() = runTest {
        val engine = MockEngine {
            jsonResponse(HttpStatusCode.BadGateway, """{"error":{"code":"NS_API_UNAVAILABLE","message":"down"}}""")
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertTrue(outcome is WorkerOutcome.HttpError)
        assertEquals(502, (outcome as WorkerOutcome.HttpError).status)
    }

    @Test
    fun `success body decodes into matching DTOs`() = runTest {
        val engine = MockEngine {
            jsonResponse(
                HttpStatusCode.OK,
                """{"dir":"ab","fromStationName":"Almere Oostvaarders","toStationName":"Amsterdam Centraal",""" +
                    """"trips":[{"departureTime":"2026-11-02T12:08:00+0100","arrivalTime":"2026-11-02T12:41:00+0100",""" +
                    """"delayMinutes":5,"track":"4b","cancelled":false,"crowdForecast":"MEDIUM"}]}""",
            )
        }

        val outcome = clientWith(engine).fetchNext(Direction.AB)

        assertTrue(outcome is WorkerOutcome.Success)
        val response = (outcome as WorkerOutcome.Success).response
        assertEquals("Almere Oostvaarders", response.fromStationName)
        assertEquals("Amsterdam Centraal", response.toStationName)
        assertEquals(
            TripDto(
                departureTime = "2026-11-02T12:08:00+0100",
                arrivalTime = "2026-11-02T12:41:00+0100",
                delayMinutes = 5,
                track = "4b",
                cancelled = false,
                crowdForecast = "MEDIUM",
            ),
            response.trips.single(),
        )
    }
}
