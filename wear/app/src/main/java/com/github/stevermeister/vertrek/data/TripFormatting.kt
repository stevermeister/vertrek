package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The Worker sends offsets without a colon (e.g. "+0100"), which the
// default ISO_OFFSET_DATE_TIME formatter rejects — hence the explicit XX pattern.
private val DEPARTURE_TIME_PARSER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun TripDto.formattedDepartureTime(): String = formatIsoTime(departureTime)

fun TripDto.formattedArrivalTime(): String = formatIsoTime(arrivalTime)

private fun formatIsoTime(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso, DEPARTURE_TIME_PARSER)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DISPLAY_TIME_FORMATTER)
    }.getOrDefault("--:--")

/**
 * The actual expected departure instant: planned time plus any known
 * delay. Null only if departureTime can't be parsed (never expected in
 * practice — the Worker always sends a real ISO instant).
 */
fun TripDto.expectedDepartureInstant(): Instant? =
    runCatching {
        OffsetDateTime.parse(departureTime, DEPARTURE_TIME_PARSER).toInstant().plusSeconds(delayMinutes * 60L)
    }.getOrNull()

/**
 * Whole minutes from right now until this trip's expected departure, or
 * null if it can't be computed or has already departed. Always measured
 * against the device clock passed in, never against when the cache was
 * fetched — a cache can be old while the underlying departure is still
 * genuinely in the future, and a fresh cache's first trip can still have
 * already left by the time this renders. Rendering "0 min" for either
 * case instead of dropping the trip was the original bug.
 */
fun TripDto.minutesUntilDeparture(clock: Clock): Long? {
    val expected = expectedDepartureInstant() ?: return null
    val now = Instant.now(clock)
    if (!expected.isAfter(now)) return null
    return Duration.between(now, expected).toMinutes()
}
