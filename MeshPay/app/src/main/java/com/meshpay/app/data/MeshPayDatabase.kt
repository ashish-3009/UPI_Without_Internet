package com.meshpay.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.meshpay.app.data.dao.PacketDao
import com.meshpay.app.data.dao.TransactionDao
import com.meshpay.app.data.entity.PacketEntity
import com.meshpay.app.data.entity.PacketStatusConverter
import com.meshpay.app.data.entity.TransactionEntity

/**
 * Room database definition for MeshPay.
 * Serves as the single source of truth for local packet and transaction data.
 *
 * Not instantiated or injected yet — this is only the class definition.
 */
@Database(
    entities = [PacketEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(PacketStatusConverter::class)
abstract class MeshPayDatabase : RoomDatabase() {
    abstract fun packetDao(): PacketDao
    abstract fun transactionDao(): TransactionDao
}
