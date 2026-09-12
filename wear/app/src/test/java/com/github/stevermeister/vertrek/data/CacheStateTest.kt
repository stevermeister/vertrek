package com.github.stevermeister.vertrek.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheStateTest {

    private val fetchedAt = Instant.parse("2026-11-02T12:00:00Z")

    private fun sample() = CachedTripsData(
        direction = "ab",
        trips = emptyList(),
        fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    )

    private fun clockAtAge(age: Duration): Clock = Clock.fixed(fetchedAt.plus(age), ZoneOffset.UTC)

    @Test
    fun `no cached data is NoData`() {
        assertEquals(CacheState.NoData, cacheStateOf(cached = null, clock = Clock.fixed(fetchedAt, ZoneOffset.UTC)))
    }

    @Test
    fun `zero age is Fresh`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ZERO))
        assertTrue(state is CacheState.Fresh)
    }

    @Test
    fun `just under 3 minutes is Fresh`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ofMinutes(3).minusSeconds(1)))
        assertTrue(state is CacheState.Fresh)
    }

    @Test
    fun `exactly 3 minutes is Stale`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ofMinutes(3)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `just under 15 minutes is Stale`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ofMinutes(15).minusSeconds(1)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `exactly 15 minutes is still Stale`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ofMinutes(15)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `just over 15 minutes is NoData`() {
        val state = cacheStateOf(sample(), clockAtAge(Duration.ofMinutes(15).plusSeconds(1)))
        assertEquals(CacheState.NoData, state)
    }

    @Test
    fun `stale and fresh states still carry the data`() {
        val data = sample()
        val fresh = cacheStateOf(data, clockAtAge(Duration.ZERO)) as CacheState.Fresh
        val stale = cacheStateOf(data, clockAtAge(Duration.ofMinutes(3))) as CacheState.Stale
        assertEquals(data, fresh.data)
        assertEquals(data, stale.data)
    }
}
