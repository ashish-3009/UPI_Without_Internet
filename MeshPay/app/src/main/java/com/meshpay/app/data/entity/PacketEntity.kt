package com.meshpay.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

/**
 * Room entity representing a mesh network packet for Store-Carry-Forward networking.
 * Each packet carries a UPI transaction payload that hops across devices
 * until it reaches an internet-connected node for settlement.
 */
@Entity(tableName = "packets")
@TypeConverters(PacketStatusConverter::class)
data class PacketEntity(
    @PrimaryKey
    val packetId: String,
    val senderVPA: String,
    val receiverVPA: String,
    val amount: Double,
    val createdAt: Long,
    val lastSeenAt: Long,
    /**
     * Forwarding budget (Task 17): the number of times this node may still relay the
     * packet onward. Initialized to [com.meshpay.app.data.local.PacketStore.DEFAULT_MAX_HOPS]
     * and decremented by exactly one on each successful forward (see
     * MeshProtocolHandler.handleRequest). Retries and receives never change it. When it
     * reaches 0 the packet is retired to EXPIRED through the lifecycle. This single
     * persisted value is the sole forwarding budget - there is no separate hopCount/ttl.
     */
    val remainingHopCount: Int,
    val status: PacketStatus,
    val uploadedBy: String? = null
)
