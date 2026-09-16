package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.LocalTime

/** Matches the Worker's `dir` query param values exactly. */
enum class Direction(val paramValue: String) {
    AB("ab"),
    BA("ba"),
    ;

    companion object {
        fun fromParam(value: String): Direction? = entries.firstOrNull { it.paramValue == value }
    }
}

private val DIRECTION_SWITCH_TIME: LocalTime = LocalTime.of(13, 0)

/**
 * Before 13:00 local time -> AB (the morning commute direction), else BA
 * — ignoring any override. Exposed so TripsRepository can tell whether a
 * stored override was set during the *same* natural period as now (see
 * its getDirectionOverride()) without duplicating this rule.
 */
fun naturalDirection(clock: Clock): Direction =
    if (LocalTime.now(clock).isBefore(DIRECTION_SWITCH_TIME)) Direction.AB else Direction.BA

/**
 * DataStore override wins if set. Otherwise: before 13:00 local time -> AB
 * (the morning commute direction), else BA. Takes a [Clock] rather than
 * reading the system clock directly so this is testable without mocking
 * static methods.
 */
fun resolveDirection(override: Direction?, clock: Clock): Direction = override ?: naturalDirection(clock)

fun Direction.opposite(): Direction = if (this == Direction.AB) Direction.BA else Direction.AB
