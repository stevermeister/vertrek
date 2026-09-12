package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The two staleness thresholds, kept together so there's one place to tune them. */
object CacheFreshness {
    val FRESH_THRESHOLD: Duration = Duration.ofMinutes(3)
    val STALE_THRESHOLD: Duration = Duration.ofMinutes(15)
}

/** Why there's nothing renderable, distinguished so the tile can say why instead of just "no data". */
enum class NoDataReason {
    /** No cache and no recorded fetch attempt at all — the very first run. */
    NEVER_FETCHED,

    /** The Worker rejected the request (401 wrong/missing key, or 500 VERTREK_KEY unset). */
    AUTH_REJECTED,

    /** Couldn't reach the Worker, or the Worker's own upstream (NS) was unavailable. */
    NETWORK_DOWN,
}

/**
 * A sealed state instead of a boolean: a stale cache is still worth
 * rendering (flagged), a no-data state is not the same thing as "stale"
 * and callers must be able to tell them apart.
 */
sealed interface CacheState {
    data class Fresh(val data: CachedTripsData) : CacheState
    data class Stale(val data: CachedTripsData) : CacheState
    data class NoData(val reason: NoDataReason) : CacheState
}

/**
 * age < FRESH_THRESHOLD          -> Fresh
 * FRESH_THRESHOLD <= age <= STALE_THRESHOLD -> Stale (still rendered, flagged)
 * age > STALE_THRESHOLD          -> NoData (payload discarded)
 *
 * [lastFailureReason] is what to report when there's no usable cache: the
 * last recorded fetch failure, or NEVER_FETCHED if nothing was ever even
 * attempted (cache and failure both absent).
 */
fun cacheStateOf(cached: CachedTripsData?, lastFailureReason: NoDataReason?, clock: Clock): CacheState {
    if (cached != null) {
        val age = Duration.between(Instant.ofEpochMilli(cached.fetchedAtEpochMillis), Instant.now(clock))
        when {
            age < CacheFreshness.FRESH_THRESHOLD -> return CacheState.Fresh(cached)
            age <= CacheFreshness.STALE_THRESHOLD -> return CacheState.Stale(cached)
        }
    }
    return CacheState.NoData(lastFailureReason ?: NoDataReason.NEVER_FETCHED)
}
