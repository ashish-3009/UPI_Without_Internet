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

    // ── Status Transition ────────────────────────────────────

    /**
     * Atomically moves a packet to [target] only if its current status is one of
     * [validSources]. The guard keeps the lifecycle transition safe against
     * concurrent writers and forbids illegal moves at the database level.
     * Returns the number of rows updated (0 = guard rejected or packet missing).
     */
    @Query("UPDATE packets SET status = :target WHERE packetId = :packetId AND status IN (:validSources)")
    suspend fun transitionStatus(packetId: String, target: String, validSources: List<String>): Int

    // ── Forwarding Budget (Task 17) ──────────────────────────

    /**
     * Atomically consumes one hop from a still-pending packet's forwarding budget
     * just before it is relayed. Triple-guarded so it can never over-forward: the
     * packet must be pending (CREATED/RELAYING) AND have budget left
     * (remainingHopCount > 0). Returns rows updated (0 = not pending, missing, or
     * the budget is already exhausted). Retransmissions and receives must NOT call
     * this - only a genuine forward consumes a hop.
     */
    @Query("UPDATE packets SET remainingHopCount = remainingHopCount - 1 WHERE packetId = :packetId AND status IN ('CREATED', 'RELAYING') AND remainingHopCount > 0")
    suspend fun decrementRemainingHop(packetId: String): Int

    // ── Utility ──────────────────────────────────────────────

    @Query("SELECT EXISTS(SELECT 1 FROM packets WHERE packetId = :packetId)")
    suspend fun exists(packetId: String): Boolean

    @Query("DELETE FROM packets WHERE status = 'EXPIRED'")
    suspend fun deleteExpired()
}
