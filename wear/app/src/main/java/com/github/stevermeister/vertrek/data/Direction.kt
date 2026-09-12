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
 * DataStore override wins if set. Otherwise: before 13:00 local time -> AB
 * (the morning commute direction), else BA. Takes a [Clock] rather than
 * reading the system clock directly so this is testable without mocking
 * static methods.
 */
fun resolveDirection(override: Direction?, clock: Clock): Direction {
    if (override != null) return override
    return if (LocalTime.now(clock).isBefore(DIRECTION_SWITCH_TIME)) Direction.AB else Direction.BA
}
