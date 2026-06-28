package com.meshpay.app.data.local

import com.meshpay.app.data.dao.PacketDao
import com.meshpay.app.data.entity.PacketEntity

/**
 * Local data source for mesh network packets.
 * Wraps [PacketDao] operations — repositories must use this layer
 * instead of accessing the DAO directly.
 */
class PacketLocalDataSource(private val packetDao: PacketDao) {

    suspend fun insertPacket(packet: PacketEntity): Long =
        packetDao.insertPacket(packet)

    suspend fun updatePacket(packet: PacketEntity) =
        packetDao.updatePacket(packet)

    suspend fun deletePacket(packet: PacketEntity) =
        packetDao.deletePacket(packet)

    suspend fun getPacketById(packetId: String): PacketEntity? =
        packetDao.getPacketById(packetId)

    suspend fun getAllPackets(): List<PacketEntity> =
        packetDao.getAllPackets()

    suspend fun getPendingPackets(): List<PacketEntity> =
        packetDao.getPendingPackets()

    suspend fun markUploaded(packetId: String) =
        packetDao.markUploaded(packetId)

    suspend fun markSettled(packetId: String) =
        packetDao.markSettled(packetId)

    suspend fun markExpired(packetId: String) =
        packetDao.markExpired(packetId)

    suspend fun exists(packetId: String): Boolean =
        packetDao.exists(packetId)

    suspend fun deleteExpired() =
        packetDao.deleteExpired()
}
