package com.github.stevermeister.vertrek.data

import kotlinx.serialization.Serializable

/** Mirrors worker/src/ns.ts CompactTrip exactly. */
@Serializable
data class TripDto(
    val departureTime: String,
    val delayMinutes: Int,
    val track: String? = null,
    val durationMinutes: Int,
    val transfers: Int,
    val cancelled: Boolean,
)

/** Mirrors the Worker's GET /next 200 response body. */
@Serializable
data class NextResponseDto(
    val dir: String,
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
