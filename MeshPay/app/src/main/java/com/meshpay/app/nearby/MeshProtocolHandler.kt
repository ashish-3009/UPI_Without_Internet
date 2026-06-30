package com.meshpay.app.nearby

import com.meshpay.app.data.entity.PacketEntity
import com.meshpay.app.data.entity.PacketLifecycle
import com.meshpay.app.data.entity.PacketStatus
import com.meshpay.app.data.local.PacketStore
import com.meshpay.app.data.local.PendingAck
import com.meshpay.app.data.repository.PacketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Owns the MeshPay sync protocol: serialization, deserialization, and processing of
 * ADVERTISEMENT, REQUEST and PACKET messages.
 *
 * [NearbyMeshService] is pure transport (raw bytes in/out); this handler turns those
 * bytes into protocol actions and data operations, and exposes packet-level state to
 * the ViewModel (which stays focused on orchestration and UI).
 *
 * Selective synchronization (DTN): when a peer connects, a node advertises the ids of
 * its pending packets; the peer requests only the ids it does not already hold; the
 * node sends only those packets. Comparison is by packetId only (no hashes). Room
 * stays the source of truth and the duplicate authority (OnConflictStrategy.IGNORE).
 *
 * This class owns no coroutine scope: every entry point is a suspend function the
 * ViewModel drives from its own scope.
 */
class MeshProtocolHandler(
    private val transport: NearbyMeshService,
    private val packetStore: PacketStore,
    private val packetRepository: PacketRepository
) {

    private val _latestReceivedPacket = MutableStateFlow<MeshPacket?>(null)
    val latestReceivedPacket: StateFlow<MeshPacket?> = _latestReceivedPacket.asStateFlow()

    // Emits only newly-stored (non-duplicate) packets, so the UI and cache react once
    // per genuinely new packet. Duplicates never reach here.
    private val _receivedPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 10)
    val receivedPackets: SharedFlow<MeshPacket> = _receivedPackets.asSharedFlow()

    private var retryJob: Job? = null

    fun clearLatestPacket() {
        _latestReceivedPacket.value = null
    }

    /**
     * The single packet send path: serialize [packet] as a PACKET envelope and hand it
     * to the transport for [endpointId]. Reused by the request handler and the retry
     * scheduler so retransmission never duplicates send logic. Does NOT touch hop count
     * or awaiting-ACK state - the caller owns that.
     */
    private suspend fun sendPacketTo(endpointId: String, packet: MeshPacket): Boolean {
        val message = withContext(Dispatchers.IO) {
            MeshMessageSerializer.serialize(MeshMessage.packet(packet))
        }
        return transport.sendMessage(endpointId, message)
    }

    // ── Outbound ─────────────────────────────────────────────

    /**
     * Sends a packet this node just created to the active peer (send-on-create).
     * Returns whether the transport accepted the send.
     */
    suspend fun broadcastPacket(packet: MeshPacket): Boolean {
        val message = withContext(Dispatchers.IO) {
            MeshMessageSerializer.serialize(MeshMessage.packet(packet))
        }
        val endpointId = transport.broadcastMessage(message)
        if (endpointId != null) {
            // Reliable delivery: track the awaiting ACK for the peer we broadcast to.
            packetStore.markAwaitingAck(packet.packetId, endpointId)
            transport.logEvent("Relay: broadcast packet ${packet.packetId.take(8)} to $endpointId - waiting ACK")
        }
        return endpointId != null
    }

    /**
     * Advertises the ids (and status) of this node's pending packets to a freshly
     * connected peer. The peer decides which it is missing and requests those.
     */
    suspend fun advertiseTo(endpointId: String) {
        val pending = packetStore.getRelayablePackets()
        if (pending.isEmpty()) {
            transport.logEvent("Advertisement: no pending packets to advertise to $endpointId")
            return
        }
        // Advertise in forwarding-priority order and cap the batch (Task 18) so a freshly
        // connected peer first learns about the most valuable packets. Same RoutingPolicy
        // as the request/retry paths - one ordering strategy, no duplication.
        val selected = RoutingPolicy.prioritize(
            pending,
            createdAt = { it.createdAt },
            remainingHopCount = { it.remainingHopCount },
            retryCount = { 0 },
            packetId = { it.packetId }
        ).take(RoutingPolicy.MAX_PACKETS_PER_SYNC)
        transport.logEvent(
            "Routing: advertise selected=${selected.size} pending=${pending.size} " +
                "oldestAge=${oldestAgeDays(pending)}d"
        )
        val summaries = selected.map { PacketSummary(it.packetId, it.status.name) }
        val message = withContext(Dispatchers.IO) {
            MeshMessageSerializer.serialize(MeshMessage.advertisement(summaries))
        }
        val sent = transport.sendMessage(endpointId, message)
        transport.logEvent("Advertisement: sent ${summaries.size} summaries to $endpointId (sent=$sent)")
    }

    // ── Inbound ──────────────────────────────────────────────

    /**
     * Single entry point for every raw inbound transport message. Deserializes the
     * envelope and dispatches to the per-type handler.
     */
    suspend fun handleInbound(endpointId: String, rawMessage: String) {
        val message = withContext(Dispatchers.IO) { MeshMessageSerializer.deserialize(rawMessage) }
        if (message == null) {
            transport.logEvent("Protocol: unparseable message from $endpointId")
            return
        }
        when (message.type) {
            MessageType.ADVERTISEMENT -> handleAdvertisement(endpointId, message.summaries.orEmpty())
            MessageType.REQUEST -> handleRequest(endpointId, message.packetIds.orEmpty())
            MessageType.PACKET -> message.packet?.let { handlePacket(endpointId, it) }
            MessageType.ACK -> message.packetId?.let { handleAck(endpointId, it) }
            else -> transport.logEvent("Protocol: unknown message type '${message.type}' from $endpointId")
        }
    }

    private suspend fun handleAdvertisement(endpointId: String, summaries: List<PacketSummary>) {
        transport.logEvent("Advertisement: received ${summaries.size} summaries from $endpointId")
        // Request only the ids we do not already hold. Room is the authority on
        // "already have" - any stored status counts (pending or terminal).
        val missing = summaries.map { it.packetId }.filter { !packetRepository.exists(it) }
        if (missing.isEmpty()) {
            transport.logEvent("Advertisement: nothing missing from $endpointId")
            return
        }
        val message = withContext(Dispatchers.IO) {
            MeshMessageSerializer.serialize(MeshMessage.request(missing))
        }
        val sent = transport.sendMessage(endpointId, message)
        transport.logEvent("Advertisement: requested ${missing.size} missing packet(s) from $endpointId (sent=$sent)")
    }

    private suspend fun handleRequest(endpointId: String, requestedIds: List<String>) {
        transport.logEvent("Request: peer $endpointId requested ${requestedIds.size} packet(s)")
        val requested = requestedIds.toSet()
        // Only send packets we still hold as pending; getRelayablePackets is the
        // authoritative pending set (includes our own outgoing payments).
        val relayable = packetStore.getRelayablePackets().filter { it.packetId in requested }
        if (relayable.isEmpty()) {
            transport.logEvent("Request: nothing relayable to send to $endpointId")
            return
        }
        // Serve in forwarding-priority order, capped per round (Task 18): if the link
        // drops mid-sync the most valuable packets were already sent. Same RoutingPolicy
        // as advertise/retry.
        val selected = RoutingPolicy.prioritize(
            relayable,
            createdAt = { it.createdAt },
            remainingHopCount = { it.remainingHopCount },
            retryCount = { 0 },
            packetId = { it.packetId }
        ).take(RoutingPolicy.MAX_PACKETS_PER_SYNC)
        transport.logEvent(
            "Routing: request selected=${selected.size} pending=${relayable.size} " +
                "oldestAge=${oldestAgeDays(relayable)}d"
        )
        var sentCount = 0
        selected.forEachIndexed { index, entity ->
            // Forwarding budget (Task 17): a packet may only be relayed while it has
            // hops left. An exhausted budget is the single forwarding guard - the packet
            // is retired through the Task 10 lifecycle (no raw SQL) and never sent.
            if (entity.remainingHopCount <= 0) {
                packetStore.transition(entity.packetId, PacketStatus.EXPIRED)
                transport.logEvent("Relay: packet ${entity.packetId.take(8)} remainingHopCount=0 - expired")
                return@forEachIndexed
            }
            // Consume exactly one hop before forwarding (atomic + guarded to pending and
            // budget > 0). If no row was updated the packet just became terminal or hit
            // zero concurrently - skip it. Retries never reach here, so they never spend
            // a hop.
            if (!packetStore.decrementRemainingHop(entity.packetId)) {
                transport.logEvent("Relay: skipped ${entity.packetId.take(8)} - no longer forwardable")
                return@forEachIndexed
            }
            val remaining = entity.remainingHopCount - 1
            val sent = sendPacketTo(endpointId, entity.toMeshPacket())
            if (sent) {
                // Reliable delivery: remember we are awaiting an ACK for this packet
                // from this peer (transport state only - lifecycle is untouched).
                packetStore.markAwaitingAck(entity.packetId, endpointId)
                sentCount++
            }
            transport.logEvent(
                "Routing: packet ${entity.packetId.take(8)} priority=${index + 1} " +
                    "remainingHopCount=$remaining - forwarded to $endpointId " +
                    "(sent=$sent)${if (sent) " - waiting ACK" else ""}"
            )
        }
        transport.logEvent(
            "Routing: request sync complete sent=$sentCount remaining=${relayable.size - selected.size}"
        )
    }

    private suspend fun handlePacket(endpointId: String, packet: MeshPacket) {
        // Track the most recently received packet regardless of duplication (matches
        // the prior NearbyMeshService behavior the upload flow depends on).
        _latestReceivedPacket.value = packet

        // Early duplicate suppression: Room's INSERT IGNORE is the authoritative check.
        // Only a genuinely new packet is cached, emitted and surfaced to the UI.
        val stored = persistReceivedPacket(packet)
        if (stored) {
            packetStore.addPacket(packet)
            _receivedPackets.emit(packet)
            transport.logEvent("Packet received packetId=${packet.packetId.take(8)} stored=true addedToCache=true")
        } else {
            transport.logEvent("Duplicate packet detected packetId=${packet.packetId.take(8)} action=ignored")
        }

        // Reliable delivery: ACK only AFTER persistence. Whether newly stored or already
        // present, the receiver now safely owns the packet, so it is correct to ACK in
        // both cases. ACK is transport-level and never changes the packet lifecycle.
        val ack = withContext(Dispatchers.IO) {
            MeshMessageSerializer.serialize(MeshMessage.ack(packet.packetId))
        }
        val ackSent = transport.sendMessage(endpointId, ack)
        transport.logEvent(
            "Packet persisted ${packet.packetId.take(8)} - ACK sent to $endpointId (sent=$ackSent)"
        )
    }

    private fun handleAck(endpointId: String, packetId: String) {
        // Reliable delivery confirmed for this peer: stop awaiting its ACK. No lifecycle
        // change - ACK confirms mesh delivery only, not settlement.
        val wasAwaiting = packetStore.confirmAck(packetId, endpointId)
        transport.logEvent("ACK received: packet ${packetId.take(8)} peer=$endpointId (awaited=$wasAwaiting)")
    }

    /**
     * Persists a received packet at RELAYING. Returns true only if it was newly stored
     * (a -1 row id from OnConflictStrategy.IGNORE means Room already held it).
     */
    private suspend fun persistReceivedPacket(packet: MeshPacket): Boolean {
        val now = System.currentTimeMillis()
        val createdAt = try {
            Instant.parse(packet.timestamp).toEpochMilli()
        } catch (e: Exception) {
            now
        }
        val entity = PacketEntity(
            packetId = packet.packetId,
            senderVPA = packet.sender,
            receiverVPA = packet.receiver,
            amount = packet.amount,
            createdAt = createdAt,
            lastSeenAt = now,
            // Fresh forwarding budget on receive: this node may relay it up to
            // DEFAULT_MAX_HOPS times. Receiving is not a forward, so this is a grant,
            // not a decrement. (remainingHopCount is node-local; the wire format has no
            // hop field and is intentionally unchanged.)
            remainingHopCount = PacketStore.DEFAULT_MAX_HOPS,
            status = PacketStatus.RELAYING,
            uploadedBy = null
        )
        val rowId = withContext(Dispatchers.IO) { packetRepository.insertPacket(entity) }
        return rowId != -1L
    }

    // ── Retry scheduler (reliable retransmission) ───────────

    /**
     * Starts a lightweight coroutine that, once per [RETRY_TICK_MS], retransmits any
     * delivery whose ACK has not arrived within [ACK_TIMEOUT_SECONDS], up to
     * [MAX_RETRY_ATTEMPTS] times. Idempotent - a call while already running is a no-op.
     * The loop lives on the caller's scope (the ViewModel's), so it stops when the mesh
     * screen goes away. No WorkManager / background service is used.
     */
    fun startRetryScheduler(scope: CoroutineScope) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_TICK_MS)
                runRetryPass()
            }
        }
    }

    private suspend fun runRetryPass() {
        val now = System.currentTimeMillis()
        val timedOut = packetStore.getPendingAcks().filter { now - it.sentAt >= ACK_TIMEOUT_MS }
        if (timedOut.isEmpty()) return

        // First, run give-up cleanup over EVERY timed-out delivery (not just the batched
        // ones) so abandoned / no-longer-pending deliveries never accumulate. Survivors
        // become retry candidates carrying their packet entity for prioritization.
        val candidates = mutableListOf<RetryCandidate>()
        for (pending in timedOut) {
            // Give up once the retry cap is hit. A transport failure is NOT a business
            // failure: drop the retry and log it, but never touch the packet lifecycle.
            if (pending.retryCount >= MAX_RETRY_ATTEMPTS) {
                packetStore.giveUpAck(pending.packetId, pending.peerId)
                transport.logEvent(
                    "RetryScheduler: packet ${pending.packetId.take(8)} maximum retries reached - giving up (peer=${pending.peerId})"
                )
                continue
            }

            // If the packet is no longer pending (settled/expired/removed), stop retrying.
            val entity = packetRepository.getPacketById(pending.packetId)
            if (entity == null || !PacketLifecycle.isPending(entity.status)) {
                packetStore.giveUpAck(pending.packetId, pending.peerId)
                transport.logEvent(
                    "RetryScheduler: packet ${pending.packetId.take(8)} no longer pending - dropping retry (peer=${pending.peerId})"
                )
                continue
            }

            candidates += RetryCandidate(pending, entity)
        }
        if (candidates.isEmpty()) return

        // Prioritize retries with the SAME RoutingPolicy as advertise/request and cap the
        // pass (Task 18). This is the anti-starvation guarantee: a large backlog of old,
        // heavily-retried deliveries can never monopolize the link ahead of fresh, near-
        // expiry packets, and only MAX_PACKETS_PER_SYNC retransmit per tick.
        val batch = RoutingPolicy.prioritize(
            candidates,
            createdAt = { it.entity.createdAt },
            remainingHopCount = { it.entity.remainingHopCount },
            retryCount = { it.ack.retryCount },
            packetId = { it.ack.packetId }
        ).take(RoutingPolicy.MAX_PACKETS_PER_SYNC)
        transport.logEvent("Routing: retry sync selected=${batch.size} pending=${candidates.size}")

        for (candidate in batch) {
            val pending = candidate.ack
            // Retransmit via the shared send path (no hop-count change). Count the
            // attempt whether or not the transport accepted it, so a vanished peer can
            // never retry forever - it gives up after MAX_RETRY_ATTEMPTS.
            val attempt = pending.retryCount + 1
            val sent = sendPacketTo(pending.peerId, candidate.entity.toMeshPacket())
            packetStore.recordAckRetry(pending.packetId, pending.peerId)
            transport.logEvent(
                "RetryScheduler: packet ${pending.packetId.take(8)} timed out - retry $attempt/$MAX_RETRY_ATTEMPTS to ${pending.peerId} (sent=$sent)"
            )
        }
        transport.logEvent(
            "Routing: retry sync complete sent=${batch.size} remaining=${candidates.size - batch.size}"
        )
    }

    /** Pairs a timed-out [PendingAck] with its packet entity so the retry pass can sort it. */
    private data class RetryCandidate(val ack: PendingAck, val entity: PacketEntity)

    /** Age in whole days of the oldest packet in [packets], for routing log lines. */
    private fun oldestAgeDays(packets: List<PacketEntity>): Long {
        val now = System.currentTimeMillis()
        val oldest = packets.minOfOrNull { it.createdAt } ?: now
        return (now - oldest) / ONE_DAY_MILLIS
    }

    private fun PacketEntity.toMeshPacket(): MeshPacket = MeshPacket(
        packetId = packetId,
        sender = senderVPA,
        receiver = receiverVPA,
        amount = amount,
        timestamp = Instant.ofEpochMilli(createdAt).toString()
    )

    companion object {
        /** A delivery with no ACK after this many seconds is retransmitted. */
        const val ACK_TIMEOUT_SECONDS = 5

        /** Maximum retransmissions before a delivery is abandoned (transport-level only). */
        const val MAX_RETRY_ATTEMPTS = 3

        private const val RETRY_TICK_MS = 1_000L
        private const val ACK_TIMEOUT_MS = ACK_TIMEOUT_SECONDS * 1_000L
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
