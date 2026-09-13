package com.github.stevermeister.vertrek.data

import kotlinx.serialization.Serializable

/**
 * Bump this whenever CachedTripsData or TripDto's shape changes in a way
 * that changes meaning (not just adding an optional field with a safe
 * default). TripsRepository discards any cached entry whose schemaVersion
 * doesn't match this, rather than trusting a decoded-with-defaults object
 * left over from an older app version.
 */
const val CACHE_SCHEMA_VERSION = 2

/**
 * What's persisted to DataStore: the last successful response, plus when
 * it was fetched.
 *
 * schemaVersion defaults to 0 — a sentinel that can never equal
 * [CACHE_SCHEMA_VERSION] — specifically so that decoding a pre-versioning
 * cached payload (which has no schemaVersion field at all) still succeeds
 * structurally instead of throwing, leaving TripsRepository free to make
 * the discard decision itself based on the version alone.
 */
@Serializable
data class CachedTripsData(
    val schemaVersion: Int = 0,
    val direction: String,
    val fromStationName: String = "",
    val toStationName: String = "",
    val trips: List<TripDto>,
    val fetchedAtEpochMillis: Long,
)
