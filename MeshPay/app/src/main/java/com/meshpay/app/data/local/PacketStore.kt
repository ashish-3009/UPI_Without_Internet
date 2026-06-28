package com.meshpay.app.data.local

import android.util.Log
import com.meshpay.app.data.repository.PacketRepository
import com.meshpay.app.nearby.MeshPacket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache and store for [MeshPacket] records.
 * Restores pending packets (CREATED, RELAYING) from Room on startup,
 * and maintains the memory cache of active packets for Store-Carry-Forward routing.
 */
class PacketStore(private val packetRepository: PacketRepository) {

    private val cache = ConcurrentHashMap<String, MeshPacket>()

    /**
     * Fetches pending packets from the database and loads them into the in-memory cache.
     * Must be called from a background thread (e.g. Dispatchers.IO).
     */
    suspend fun restorePendingPackets() {
        try {
            Log.d("PacketStore", "Starting packet restoration")

            val pending = packetRepository.getPendingPackets()

            Log.d("PacketStore", "Pending packets found = ${pending.size}")

            for (entity in pending) {
                val packet = MeshPacket(
                    packetId = entity.packetId,
                    sender = entity.senderVPA,
                    receiver = entity.receiverVPA,
                    amount = entity.amount,
                    timestamp = Instant.ofEpochMilli(entity.createdAt).toString()
                )

                cache[packet.packetId] = packet

                Log.d("PacketStore", "Restored packet ${packet.packetId}")
            }

            Log.d("PacketStore", "Cache size = ${cache.size}")

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
}
