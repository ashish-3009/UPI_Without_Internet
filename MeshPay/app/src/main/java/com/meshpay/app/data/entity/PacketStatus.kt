package com.meshpay.app.data.entity

/**
 * Represents the lifecycle status of a mesh network packet.
 * Used by [PacketEntity] to track packet state in Store-Carry-Forward networking.
 */
enum class PacketStatus {
    CREATED,
    RELAYING,
    UPLOADED,
    SETTLED,
    FAILED,
    EXPIRED
}
