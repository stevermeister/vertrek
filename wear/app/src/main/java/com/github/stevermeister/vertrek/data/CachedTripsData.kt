package com.github.stevermeister.vertrek.data

import kotlinx.serialization.Serializable

/** What's persisted to DataStore: the last successful response, plus when it was fetched. */
@Serializable
data class CachedTripsData(
    val direction: String,
    val trips: List<TripDto>,
    val fetchedAtEpochMillis: Long,
)
