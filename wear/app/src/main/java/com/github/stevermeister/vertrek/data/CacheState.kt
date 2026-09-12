package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The two staleness thresholds, kept together so there's one place to tune them. */
object CacheFreshness {
    val FRESH_THRESHOLD: Duration = Duration.ofMinutes(3)
    val STALE_THRESHOLD: Duration = Duration.ofMinutes(15)
}

/**
 * A sealed state instead of a boolean: a stale cache is still worth
 * rendering (flagged), a no-data state is not the same thing as "stale"
 * and callers must be able to tell them apart.
 */
sealed interface CacheState {
    data class Fresh(val data: CachedTripsData) : CacheState
    data class Stale(val data: CachedTripsData) : CacheState
    data object NoData : CacheState
}

/**
 * age < FRESH_THRESHOLD          -> Fresh
 * FRESH_THRESHOLD <= age <= STALE_THRESHOLD -> Stale (still rendered, flagged)
 * age > STALE_THRESHOLD          -> NoData (payload discarded)
 */
fun cacheStateOf(cached: CachedTripsData?, clock: Clock): CacheState {
    if (cached != null) {
        val age = Duration.between(Instant.ofEpochMilli(cached.fetchedAtEpochMillis), Instant.now(clock))
        when {
            age < CacheFreshness.FRESH_THRESHOLD -> return CacheState.Fresh(cached)
            age <= CacheFreshness.STALE_THRESHOLD -> return CacheState.Stale(cached)
        }
    }
    return CacheState.NoData
}
