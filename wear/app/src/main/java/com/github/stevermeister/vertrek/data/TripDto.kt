package com.github.stevermeister.vertrek.data

import kotlinx.serialization.Serializable

/**
 * Mirrors worker/src/ns.ts CompactTrip exactly.
 *
 * arrivalTime/crowdForecast default to "" / "UNKNOWN" so decoding an old
 * cached payload (from before these fields existed) never throws — see
 * CachedTripsData's schemaVersion, which is what actually decides whether
 * a decoded-with-defaults object like that gets trusted or discarded.
 */
@Serializable
data class TripDto(
    val departureTime: String,
    val arrivalTime: String = "",
    val delayMinutes: Int,
    val track: String? = null,
    val cancelled: Boolean,
    val crowdForecast: String = "UNKNOWN", // "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN"
)

/** Mirrors the Worker's GET /next 200 response body. */
@Serializable
data class NextResponseDto(
    val dir: String,
    val fromStationName: String,
    val toStationName: String,
    val trips: List<TripDto>,
)

/** Mirrors the Worker's `{ "error": { "code", "message" } }` error body. */
@Serializable
data class WorkerErrorBody(
    val error: WorkerErrorDetail,
)

@Serializable
data class WorkerErrorDetail(
    val code: String,
    val message: String,
)
