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
    // 0 = direct, no transfers. Defaults to 0 so decoding an old cached
    // payload (from before this field existed) never throws — a stale
    // cached trip predating this field was, in practice, always direct
    // (transfers weren't tracked, not "unknown and possibly indirect").
    val transfers: Int = 0,
)

/**
 * Mirrors the Worker's GET /next 200 response body.
 *
 * fromStationShort/toStationShort default to "" so decoding a response
 * from a Worker predating these fields never throws; TripsViewModel falls
 * back to the full name when a short one is blank.
 */
@Serializable
data class NextResponseDto(
    val dir: String,
    val fromStationName: String,
    val toStationName: String,
    val fromStationShort: String = "",
    val toStationShort: String = "",
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
