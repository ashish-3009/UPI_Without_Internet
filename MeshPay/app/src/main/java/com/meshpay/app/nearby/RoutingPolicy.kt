package com.meshpay.app.nearby

/**
 * Centralized routing policy (Task 18): the single source of truth for the order in
 * which packets are advertised, served on request, and retried, plus the per-round
 * batch cap. Pure sorting / limit logic - no Android, Room, networking, coroutine or
 * I/O dependencies - so every selection site shares one identical, deterministic
 * prioritization instead of ad-hoc database order.
 *
 * When connection time or bandwidth is scarce, the most valuable packets must leave
 * first. Priority, most important rule first:
 *
 *   1. Older packets first    - they have waited longest (ascending createdAt).
 *   2. Lower remainingHopCount - closest to hop-expiry, forward before they die.
 *   3. Lower retryCount       - don't let heavily-retried packets starve fresh ones.
 *   4. Stable packetId        - deterministic tie-break so ordering never wobbles.
 *
 * This changes ONLY ordering and batch size; it never alters lifecycle, hop-limit,
 * the wire protocol, or which packets are eligible (callers still pass the pending /
 * relayable set). No new message types, no schema changes.
 */
object RoutingPolicy {

    /**
     * Upper bound on how many packets a single synchronization round forwards. Anything
     * beyond this is not dropped - it simply synchronizes during a later advertisement
     * round, so no protocol change is needed. Centralized here as the one tunable.
     */
    const val MAX_PACKETS_PER_SYNC = 25

    /**
     * Returns [items] ordered by routing priority (see class doc). Fields are read via
     * selectors so the policy stays decoupled from any concrete type (PacketEntity, a
     * retry candidate, ...) and carries no dependencies. Sorting is stable.
     */
    fun <T> prioritize(
        items: List<T>,
        createdAt: (T) -> Long,
        remainingHopCount: (T) -> Int,
        retryCount: (T) -> Int,
        packetId: (T) -> String
    ): List<T> {
        val comparator = compareBy(createdAt)
            .thenBy(remainingHopCount)
            .thenBy(retryCount)
            .thenBy(packetId)
        return items.sortedWith(comparator)
    }
}
