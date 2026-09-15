package com.github.stevermeister.vertrek.data

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
