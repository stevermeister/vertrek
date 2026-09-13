package com.github.stevermeister.vertrek.tile

import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.resolveDirection
import java.time.Clock

private const val SWAP_CLICKABLE_ID_PREFIX = "swap_to_"

/**
 * The swap button's clickable id for a tap that would switch TO [target].
 * Naming the target (not the action) is what makes replaying an id
 * idempotent instead of an oscillating toggle — see resolveEffectiveDirection.
 */
fun swapClickableId(target: Direction): String = "$SWAP_CLICKABLE_ID_PREFIX${target.paramValue}"

/**
 * The direction named by a clickable id, if it's one of ours. Null for an
 * absent or unrecognised id — including a stale id the system keeps
 * reporting via TileRequest.currentState.lastClickableId on a later,
 * unrelated request (freshness refresh, requestUpdate()) where no new tap
 * actually happened.
 */
fun desiredDirectionFromClickableId(clickableId: String?): Direction? {
    if (clickableId == null || !clickableId.startsWith(SWAP_CLICKABLE_ID_PREFIX)) return null
    return Direction.fromParam(clickableId.removePrefix(SWAP_CLICKABLE_ID_PREFIX))
}

/**
 * The direction to render for this request. Because the clickable id
 * names an absolute target direction rather than "toggle", replaying the
 * same id across multiple requests with no new tap keeps returning the
 * same direction instead of flipping it again. An absent or unrecognised
 * id falls through to the normal override-or-time-of-day resolution.
 */
fun resolveEffectiveDirection(lastClickableId: String?, override: Direction?, clock: Clock): Direction =
    desiredDirectionFromClickableId(lastClickableId) ?: resolveDirection(override, clock)
