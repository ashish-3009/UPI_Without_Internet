package com.meshpay.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.meshpay.app.data.entity.PacketEntity

/**
 * Data Access Object for [PacketEntity].
 * Provides CRUD operations and status-update queries
 * for mesh network packets used in Store-Carry-Forward networking.
 */
@Dao
interface PacketDao {

    // ── Insert ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketEntity): Long

    // ── Update ───────────────────────────────────────────────

    @Update
    suspend fun updatePacket(packet: PacketEntity)

    // ── Delete ───────────────────────────────────────────────

    @Delete
    suspend fun deletePacket(packet: PacketEntity)

    // ── Queries ──────────────────────────────────────────────

    @Query("SELECT * FROM packets WHERE packetId = :packetId")
    suspend fun getPacketById(packetId: String): PacketEntity?

    @Query("SELECT * FROM packets ORDER BY createdAt DESC")
    suspend fun getAllPackets(): List<PacketEntity>

    @Query("SELECT * FROM packets WHERE status IN ('CREATED', 'RELAYING') ORDER BY createdAt ASC")
    suspend fun getPendingPackets(): List<PacketEntity>

    // ── Status Updates ───────────────────────────────────────

    @Query("UPDATE packets SET status = 'SETTLED' WHERE packetId = :packetId")
    suspend fun markSettled(packetId: String)

    @Query("UPDATE packets SET status = 'UPLOADED' WHERE packetId = :packetId")
    suspend fun markUploaded(packetId: String)

    @Query("UPDATE packets SET status = 'EXPIRED' WHERE packetId = :packetId")
    suspend fun markExpired(packetId: String)

    // ── Utility ──────────────────────────────────────────────

    @Query("SELECT EXISTS(SELECT 1 FROM packets WHERE packetId = :packetId)")
    suspend fun exists(packetId: String): Boolean

    @Query("DELETE FROM packets WHERE status = 'EXPIRED'")
    suspend fun deleteExpired()
}
