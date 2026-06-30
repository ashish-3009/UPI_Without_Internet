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
    // Reserved future state for an explicit failure path. Currently unused: no
    // transition targets it (see PacketLifecycle). Kept so persisted values and
    // the enum ordinal layout stay stable for when a failure path is added.
    FAILED,
    EXPIRED
}
