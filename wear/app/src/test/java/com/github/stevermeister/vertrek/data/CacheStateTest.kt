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
    fun `no cached data and no recorded failure is NoData NEVER_FETCHED`() {
        val state = cacheStateOf(cached = null, lastFailureReason = null, clock = Clock.fixed(fetchedAt, ZoneOffset.UTC))
        assertEquals(CacheState.NoData(NoDataReason.NEVER_FETCHED), state)
    }

    @Test
    fun `no cached data but a recorded failure surfaces that reason, not NEVER_FETCHED`() {
        val state =
            cacheStateOf(
                cached = null,
                lastFailureReason = NoDataReason.AUTH_REJECTED,
                clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
            )
        assertEquals(CacheState.NoData(NoDataReason.AUTH_REJECTED), state)
    }

    @Test
    fun `zero age is Fresh`() {
        val state = cacheStateOf(sample(), null, clockAtAge(Duration.ZERO))
        assertTrue(state is CacheState.Fresh)
    }

    @Test
    fun `just under 3 minutes is Fresh`() {
        val state = cacheStateOf(sample(), null, clockAtAge(Duration.ofMinutes(3).minusSeconds(1)))
        assertTrue(state is CacheState.Fresh)
    }

    @Test
    fun `exactly 3 minutes is Stale`() {
        val state = cacheStateOf(sample(), null, clockAtAge(Duration.ofMinutes(3)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `just under 15 minutes is Stale`() {
        val state = cacheStateOf(sample(), null, clockAtAge(Duration.ofMinutes(15).minusSeconds(1)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `exactly 15 minutes is still Stale`() {
        val state = cacheStateOf(sample(), null, clockAtAge(Duration.ofMinutes(15)))
        assertTrue(state is CacheState.Stale)
    }

    @Test
    fun `just over 15 minutes is NoData, falling back to the last recorded failure`() {
        val state =
            cacheStateOf(
                sample(),
                NoDataReason.NETWORK_DOWN,
                clockAtAge(Duration.ofMinutes(15).plusSeconds(1)),
            )
        assertEquals(CacheState.NoData(NoDataReason.NETWORK_DOWN), state)
    }

    @Test
    fun `stale and fresh states still carry the data`() {
        val data = sample()
        val fresh = cacheStateOf(data, null, clockAtAge(Duration.ZERO)) as CacheState.Fresh
        val stale = cacheStateOf(data, null, clockAtAge(Duration.ofMinutes(3))) as CacheState.Stale
        assertEquals(data, fresh.data)
        assertEquals(data, stale.data)
    }
}
