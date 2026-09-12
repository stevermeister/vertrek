package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionTest {

    private fun clockAt(hour: Int, minute: Int, second: Int = 0): Clock {
        val instant = Instant.parse("2026-11-02T%02d:%02d:%02dZ".format(hour, minute, second))
        return Clock.fixed(instant, ZoneOffset.UTC)
    }

    @Test
    fun `just before 13-00 resolves to AB`() {
        assertEquals(Direction.AB, resolveDirection(override = null, clock = clockAt(12, 59, 59)))
    }

    @Test
    fun `exactly 13-00 resolves to BA`() {
        assertEquals(Direction.BA, resolveDirection(override = null, clock = clockAt(13, 0, 0)))
    }

    @Test
    fun `just after 13-00 resolves to BA`() {
        assertEquals(Direction.BA, resolveDirection(override = null, clock = clockAt(13, 0, 1)))
    }

    @Test
    fun `well before 13-00 resolves to AB`() {
        assertEquals(Direction.AB, resolveDirection(override = null, clock = clockAt(7, 30)))
    }

    @Test
    fun `well after 13-00 resolves to BA`() {
        assertEquals(Direction.BA, resolveDirection(override = null, clock = clockAt(20, 0)))
    }

    @Test
    fun `override wins over morning time-of-day`() {
        assertEquals(Direction.BA, resolveDirection(override = Direction.BA, clock = clockAt(7, 30)))
    }

    @Test
    fun `override wins over evening time-of-day`() {
        assertEquals(Direction.AB, resolveDirection(override = Direction.AB, clock = clockAt(20, 0)))
    }
}
