package com.meshpay.app.data.repository

import com.meshpay.app.data.entity.PacketEntity
import com.meshpay.app.data.local.PacketLocalDataSource

/**
 * Repository for mesh network packets.
 * Exposes a clean API for future ViewModels by delegating
 * to [PacketLocalDataSource]. Never accesses DAOs directly.
 */
class PacketRepository(private val localDataSource: PacketLocalDataSource) {

    suspend fun insertPacket(packet: PacketEntity): Long =
        localDataSource.insertPacket(packet)

    suspend fun updatePacket(packet: PacketEntity) =
        localDataSource.updatePacket(packet)

    suspend fun deletePacket(packet: PacketEntity) =
        localDataSource.deletePacket(packet)

    suspend fun getPacketById(packetId: String): PacketEntity? =
        localDataSource.getPacketById(packetId)

    suspend fun getAllPackets(): List<PacketEntity> =
        localDataSource.getAllPackets()

    suspend fun getPendingPackets(): List<PacketEntity> =
        localDataSource.getPendingPackets()

    /**
     * Thin data-access delegate for the guarded status update. Lifecycle validation
     * and logging live in the orchestration layer ([com.meshpay.app.data.local.PacketStore]),
     * not here. Returns rows updated (0 = guard rejected or packet missing).
     */
    suspend fun transitionStatus(packetId: String, target: String, validSources: List<String>): Int =
        localDataSource.transitionStatus(packetId, target, validSources)

    /** Consumes one hop from a pending packet's forwarding budget. Returns rows updated. */
    suspend fun decrementRemainingHop(packetId: String): Int =
        localDataSource.decrementRemainingHop(packetId)

    suspend fun exists(packetId: String): Boolean =
        localDataSource.exists(packetId)

    suspend fun deleteExpired() =
        localDataSource.deleteExpired()
}
