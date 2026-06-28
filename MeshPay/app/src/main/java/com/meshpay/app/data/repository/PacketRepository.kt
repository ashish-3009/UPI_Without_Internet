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

    suspend fun markUploaded(packetId: String) =
        localDataSource.markUploaded(packetId)

    suspend fun markSettled(packetId: String) =
        localDataSource.markSettled(packetId)

    suspend fun markExpired(packetId: String) =
        localDataSource.markExpired(packetId)

    suspend fun exists(packetId: String): Boolean =
        localDataSource.exists(packetId)

    suspend fun deleteExpired() =
        localDataSource.deleteExpired()
}
