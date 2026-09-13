package com.github.stevermeister.vertrek.tile

import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.opposite
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwapDirectionTest {

    private fun clockAt(hour: Int, minute: Int): Clock {
        val instant = Instant.parse("2026-11-02T%02d:%02d:00Z".format(hour, minute))
        return Clock.fixed(instant, ZoneOffset.UTC)
    }

    @Test
    fun `desiredDirectionFromClickableId parses known ids and rejects everything else`() {
        assertEquals(Direction.AB, desiredDirectionFromClickableId(swapClickableId(Direction.AB)))
        assertEquals(Direction.BA, desiredDirectionFromClickableId(swapClickableId(Direction.BA)))
        assertNull(desiredDirectionFromClickableId(null))
        assertNull(desiredDirectionFromClickableId(""))
        assertNull(desiredDirectionFromClickableId("some_unrelated_button"))
        assertNull(desiredDirectionFromClickableId("swap_to_xx"))
    }

    @Test
    fun `an unrecognised clickable id falls back to time-of-day resolution`() {
        assertEquals(
            Direction.AB,
            resolveEffectiveDirection(lastClickableId = "some_unrelated_button", override = null, clock = clockAt(7, 30)),
        )
        assertEquals(
            Direction.BA,
            resolveEffectiveDirection(lastClickableId = "some_unrelated_button", override = null, clock = clockAt(20, 0)),
        )
        assertEquals(
            Direction.AB,
            resolveEffectiveDirection(lastClickableId = null, override = null, clock = clockAt(7, 30)),
        )
    }

    @Test
    fun `an absent clickable id still respects a DataStore override`() {
        assertEquals(
            Direction.BA,
            resolveEffectiveDirection(lastClickableId = null, override = Direction.BA, clock = clockAt(7, 30)),
        )
    }

    @Test
    fun `replaying the same lastClickableId across repeated requests keeps the direction stable`() {
        // This is the exact bug: freshnessInterval/requestUpdate() re-invoke
        // onTileRequest with no new tap, but the system keeps reporting the
        // last real tap's id. Three consecutive calls with that same id and
        // no new tap must all resolve to the same direction, not flip it
        // again on the 2nd/3rd call.
        val clock = clockAt(7, 30) // well before 13:00 — natural resolution would be AB
        val tapId = swapClickableId(Direction.BA)

        val first = resolveEffectiveDirection(tapId, override = null, clock = clock)
        val override = desiredDirectionFromClickableId(tapId) // what the async persist would have written

        val second = resolveEffectiveDirection(tapId, override = override, clock = clock)
        val third = resolveEffectiveDirection(tapId, override = override, clock = clock)

        assertEquals(Direction.BA, first)
        assertEquals(Direction.BA, second)
        assertEquals(Direction.BA, third)
    }

    @Test
    fun `tap, refresh, tap resolves to AB, BA, BA, AB in order`() {
        val clock = clockAt(7, 30) // before 13:00 -> natural direction is AB
        var override: Direction? = null

        // Initial render: no tap yet.
        val initial = resolveEffectiveDirection(lastClickableId = null, override = override, clock = clock)

        // Tap the swap button shown while direction=AB -> its id targets BA.
        val tapToBa = swapClickableId(initial.opposite())
        val afterTap1 = resolveEffectiveDirection(tapToBa, override, clock)
        override = desiredDirectionFromClickableId(tapToBa)

        // Freshness-interval refresh: same lastClickableId reported again, no new tap.
        val afterRefresh = resolveEffectiveDirection(tapToBa, override, clock)

        // Tap again, this time the button shown while direction=BA -> targets AB.
        val tapToAb = swapClickableId(afterRefresh.opposite())
        val afterTap2 = resolveEffectiveDirection(tapToAb, override, clock)
        override = desiredDirectionFromClickableId(tapToAb)

        assertEquals(
            listOf(Direction.AB, Direction.BA, Direction.BA, Direction.AB),
            listOf(initial, afterTap1, afterRefresh, afterTap2),
        )
    }
}
