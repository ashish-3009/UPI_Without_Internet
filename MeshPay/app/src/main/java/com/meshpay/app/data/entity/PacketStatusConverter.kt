package com.meshpay.app.data.entity

import androidx.room.TypeConverter

/**
 * Room TypeConverter for [PacketStatus] enum.
 * Converts between [PacketStatus] and its [String] name for database storage.
 */
class PacketStatusConverter {

    @TypeConverter
    fun fromPacketStatus(status: PacketStatus): String {
        return status.name
    }

    @TypeConverter
    fun toPacketStatus(value: String): PacketStatus {
        return PacketStatus.valueOf(value)
    }
}
