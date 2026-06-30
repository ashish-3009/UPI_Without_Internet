package com.meshpay.app.data.entity

/**
 * Canonical state machine for [PacketStatus] in Store-Carry-Forward networking.
 *
 * Lifecycle:
 *   CREATED -> RELAYING -> UPLOADED -> SETTLED
 *   CREATED -> RELAYING -> EXPIRED
 *
 * [allowedTransitions] is the single source of truth for which moves are legal.
 * Backward transitions (e.g. SETTLED -> CREATED) and any transition out of a
 * terminal state are rejected. CREATED -> UPLOADED is permitted as a fast-path
 * for a packet whose initial broadcast failed (so it never reached RELAYING) but
 * is later uploaded directly by its creator.
 *
 * This object holds rules only - no Android, Room, logging or I/O dependencies -
 * so the orchestration layer ([com.meshpay.app.data.local.PacketStore]) owns
 * validation, logging and cross-layer synchronization.
 */
object PacketLifecycle {

    private val allowedTransitions: Map<PacketStatus, Set<PacketStatus>> = mapOf(
        PacketStatus.CREATED to setOf(
            PacketStatus.RELAYING,
            PacketStatus.UPLOADED,
            PacketStatus.EXPIRED
        ),
        PacketStatus.RELAYING to setOf(
            PacketStatus.UPLOADED,
            PacketStatus.EXPIRED
        ),
        PacketStatus.UPLOADED to setOf(PacketStatus.SETTLED),
        PacketStatus.SETTLED to emptySet(),
        PacketStatus.EXPIRED to emptySet(),
        // Reserved future state: defined as terminal with no edges yet. When a
        // failure path is introduced, add its incoming transitions here.
        PacketStatus.FAILED to emptySet()
    )

    /** Statuses for a packet still moving through the mesh; kept in the in-memory cache. */
    fun isPending(status: PacketStatus): Boolean =
        status == PacketStatus.CREATED || status == PacketStatus.RELAYING

    /** Terminal statuses have no outgoing transitions. */
    fun isTerminal(status: PacketStatus): Boolean =
        allowedTransitions[status].isNullOrEmpty()

    /** Whether [from] -> [to] is a legal lifecycle transition. */
    fun isValidTransition(from: PacketStatus, to: PacketStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    /** Every status that may legally transition into [target]. */
    fun validSourcesFor(target: PacketStatus): Set<PacketStatus> =
        allowedTransitions.filterValues { it.contains(target) }.keys
}
