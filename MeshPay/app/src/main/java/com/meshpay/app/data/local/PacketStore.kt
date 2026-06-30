package com.meshpay.app.data.local

import android.util.Log
import com.meshpay.app.data.entity.PacketEntity
import com.meshpay.app.data.entity.PacketLifecycle
import com.meshpay.app.data.entity.PacketStatus
import com.meshpay.app.data.repository.PacketRepository
import com.meshpay.app.nearby.MeshPacket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Transport-level record of a packet sent to a peer and awaiting acknowledgement.
 * Carries the send time and how many times it has been retransmitted so the retry
 * scheduler can detect timeouts and enforce a retry cap. This is delivery state, not
 * packet-lifecycle state - it never affects [PacketStatus].
 */
data class PendingAck(
    val packetId: String,
    val peerId: String,
    val sentAt: Long,
    val retryCount: Int
)

/**
 * In-memory cache and store for [MeshPacket] records.
 * Restores pending packets (CREATED, RELAYING) from Room on startup,
 * and maintains the memory cache of active packets for Store-Carry-Forward routing.
 */
class PacketStore(private val packetRepository: PacketRepository) {

    private val cache = ConcurrentHashMap<String, MeshPacket>()

    // Transport-level delivery tracking (Task 15/16): one entry per (packet, peer) we
    // have sent and not yet seen ACKed, with sentAt + retryCount for timeout-based
    // retransmission. This is NOT lifecycle state - it never affects PacketStatus.
    private val pendingAcks = ConcurrentHashMap<String, PendingAck>()

    private fun ackKey(packetId: String, peerId: String): String = "$packetId|$peerId"

    /**
     * Fetches pending packets from the database, expires any that have outlived
     * [PACKET_EXPIRY_DAYS], and loads the rest into the in-memory cache.
     * Must be called from a background thread (e.g. Dispatchers.IO).
     *
     * Expiry policy (Task 11): a pending packet (CREATED/RELAYING) whose age since
     * [PacketEntity.createdAt] exceeds the threshold can no longer be settled, so it
     * is retired to EXPIRED through the Task 10 lifecycle and is NOT restored - the
     * cache must never hold an expired packet. Only age is considered here; TTL,
     * hop-count and forwarding are intentionally out of scope.
     */
    suspend fun restorePendingPackets() {
        try {
            Log.d("PacketStore", "Starting packet restoration")

            val pending = packetRepository.getPendingPackets()

            Log.d("PacketStore", "Pending packets found = ${pending.size}")

            val now = System.currentTimeMillis()
            var restored = 0
            var expired = 0

            for (entity in pending) {
                val age = now - entity.createdAt
                if (age > PACKET_EXPIRY_MILLIS) {
                    // Stale: retire through the centralized lifecycle (CREATED/RELAYING
                    // -> EXPIRED) and skip restoration so it never enters the cache.
                    val ageDays = age / ONE_DAY_MILLIS
                    Log.i(
                        "PacketStore",
                        "Packet ${entity.packetId} expiring (${entity.status} -> EXPIRED): " +
                            "age ${ageDays}d exceeded $PACKET_EXPIRY_DAYS d"
                    )
                    if (transition(entity.packetId, PacketStatus.EXPIRED)) {
                        expired++
                    }
                    continue
                }

                val packet = MeshPacket(
                    packetId = entity.packetId,
                    sender = entity.senderVPA,
                    receiver = entity.receiverVPA,
                    amount = entity.amount,
                    timestamp = Instant.ofEpochMilli(entity.createdAt).toString()
                )

                cache[packet.packetId] = packet
                restored++

                Log.d("PacketStore", "Restored packet ${packet.packetId}")
            }

            Log.d(
                "PacketStore",
                "Restoration complete: restored=$restored expired=$expired cacheSize=${cache.size}"
            )

        } catch (e: Exception) {
            Log.e("PacketStore", "Failed to restore pending packets", e)
        }
    }

    /**
     * Adds a packet to the in-memory cache.
     */
    fun addPacket(packet: MeshPacket) {
        cache[packet.packetId] = packet
    }

    /**
     * Removes a packet from the in-memory cache.
     */
    fun removePacket(packetId: String) {
        cache.remove(packetId)
    }

    /**
     * Central lifecycle transition for a packet. This is the single choke point
     * that keeps Room and the in-memory cache in sync:
     *
     *  1. reads the current status from Room,
     *  2. validates the move against [PacketLifecycle] (rules live there, not here),
     *  3. applies the guarded Room update,
     *  4. evicts the packet from the cache once it is no longer pending,
     *  5. logs every outcome - applied, skipped, rejected or missing - with the
     *     previous and new state.
     *
     * Returns true only when the status actually changed in the database.
     */
    suspend fun transition(packetId: String, target: PacketStatus): Boolean {
        val current = packetRepository.getPacketById(packetId)?.status

        if (current == null) {
            Log.w("PacketStore", "Transition (none -> $target) rejected for $packetId: packet not found")
            return false
        }
        if (current == target) {
            Log.d("PacketStore", "Transition ($current -> $target) skipped for $packetId: already in target state")
            return false
        }
        if (!PacketLifecycle.isValidTransition(current, target)) {
            Log.w("PacketStore", "Transition ($current -> $target) REJECTED for $packetId: invalid lifecycle transition")
            return false
        }

        val validSources = PacketLifecycle.validSourcesFor(target).map { it.name }
        val rows = packetRepository.transitionStatus(packetId, target.name, validSources)
        if (rows == 0) {
            Log.w("PacketStore", "Transition ($current -> $target) not applied for $packetId: state changed concurrently")
            return false
        }

        Log.i("PacketStore", "Transition ($current -> $target) applied for $packetId")
        if (!PacketLifecycle.isPending(target)) {
            removePacket(packetId)
            Log.d("PacketStore", "Removed $packetId from cache after terminal transition ($current -> $target)")
        }
        return true
    }

    /**
     * Returns the packets eligible for relay: every pending (CREATED/RELAYING) packet
     * in Room, oldest first. Reads from Room - not just the cache - so it also covers
     * packets this node created but never cached (e.g. outgoing payments persisted by
     * SendPaymentViewModel). Filtered through [PacketLifecycle.isPending] so only
     * relayable packets are ever returned.
     */
    suspend fun getRelayablePackets(): List<PacketEntity> =
        packetRepository.getPendingPackets().filter { PacketLifecycle.isPending(it.status) }

    /**
     * Consumes one hop from a pending packet's forwarding budget just before it is
     * relayed (Task 17). Returns true only when the packet was still pending AND had
     * budget left, so the guarded Room update actually applied. A false result means
     * the caller must NOT forward: the packet is terminal, missing, or out of hops.
     */
    suspend fun decrementRemainingHop(packetId: String): Boolean =
        packetRepository.decrementRemainingHop(packetId) > 0

    /**
     * Retrieves a packet by its ID from the in-memory cache.
     */
    fun getPacket(packetId: String): MeshPacket? {
        return cache[packetId]
    }

    /**
     * Returns a list of all packets currently in the cache.
     */
    fun getCachedPackets(): List<MeshPacket> {
        return cache.values.toList()
    }

    /**
     * Checks if the cache contains a packet with the given ID.
     */
    fun contains(packetId: String): Boolean {
        return cache.containsKey(packetId)
    }

    // ── Reliable delivery (transport state, not lifecycle) ───

    /**
     * Records that [peerId] has just been sent [packetId] over the mesh and has not yet
     * acknowledged it, stamped with the current time and retryCount = 0. Does not change
     * the packet's [PacketStatus].
     */
    fun markAwaitingAck(packetId: String, peerId: String) {
        pendingAcks[ackKey(packetId, peerId)] =
            PendingAck(packetId, peerId, System.currentTimeMillis(), retryCount = 0)
    }

    /**
     * Clears the awaiting-ACK record for [packetId] from [peerId]. Returns true if we
     * were genuinely awaiting that ACK (false = unsolicited or already-cleared ACK).
     */
    fun confirmAck(packetId: String, peerId: String): Boolean {
        return pendingAcks.remove(ackKey(packetId, peerId)) != null
    }

    /**
     * Records a retransmission: bumps retryCount and restamps sentAt to now, so the
     * next timeout is measured from this attempt. No-op if the entry is already gone.
     */
    fun recordAckRetry(packetId: String, peerId: String) {
        val key = ackKey(packetId, peerId)
        val existing = pendingAcks[key] ?: return
        pendingAcks[key] = existing.copy(
            retryCount = existing.retryCount + 1,
            sentAt = System.currentTimeMillis()
        )
    }

    /** Stops tracking a delivery (ACK received elsewhere, retries exhausted, etc.). */
    fun giveUpAck(packetId: String, peerId: String) {
        pendingAcks.remove(ackKey(packetId, peerId))
    }

    /** Snapshot of all outstanding deliveries, for the retry scheduler to inspect. */
    fun getPendingAcks(): List<PendingAck> = pendingAcks.values.toList()

    /**
     * True if [packetId] is still awaiting an ACK from [peerId].
     */
    fun isAwaitingAck(packetId: String, peerId: String): Boolean {
        return pendingAcks.containsKey(ackKey(packetId, peerId))
    }

    companion object {
        /**
         * Age threshold for startup expiry. Pending packets older than this are
         * moved to EXPIRED instead of being restored. Single source of truth -
         * change this one value to tune the policy.
         */
        const val PACKET_EXPIRY_DAYS = 7

        /**
         * Forwarding budget granted to a packet (Task 17): the maximum number of times
         * a node may relay it onward before it is retired to EXPIRED. Single source of
         * truth for the hop limit - every fresh/received packet starts here and each
         * successful forward decrements [PacketEntity.remainingHopCount] by one.
         * NOTE: the one-time `MIGRATION_1_2` mirrors this value as a SQL literal (10);
         * keep them in sync if you ever change it.
         */
        const val DEFAULT_MAX_HOPS = 10

        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val PACKET_EXPIRY_MILLIS = PACKET_EXPIRY_DAYS * ONE_DAY_MILLIS
    }
}
