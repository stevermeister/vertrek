package com.github.stevermeister.vertrek.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The Worker sends offsets without a colon (e.g. "+0100"), which the
// default ISO_OFFSET_DATE_TIME formatter rejects — hence the explicit XX pattern.
private val DEPARTURE_TIME_PARSER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX")
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Shared by the tile and MainActivity so both render/compute from the same parse. */
fun TripDto.parsedDepartureInstant(): Instant? =
    runCatching { OffsetDateTime.parse(departureTime, DEPARTURE_TIME_PARSER).toInstant() }.getOrNull()

fun TripDto.formattedDepartureTime(): String =
    runCatching {
        OffsetDateTime.parse(departureTime, DEPARTURE_TIME_PARSER)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DISPLAY_TIME_FORMATTER)
    }.getOrDefault("--:--")
