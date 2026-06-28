package com.meshpay.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a wallet transaction history record.
 * This is completely separate from [PacketEntity] and tracks
 * the user's transaction history for display and auditing purposes.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey
    val transactionId: String,
    val senderVPA: String,
    val receiverVPA: String,
    val amount: Double,
    val createdAt: Long,
    val settledAt: Long? = null,
    val status: String
)
