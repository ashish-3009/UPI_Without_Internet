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
    val hopCount: Int,
    val ttl: Int,
    val status: PacketStatus,
    val uploadedBy: String? = null
)
