package com.meshpay.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
@TypeConverters(PacketStatusConverter::class)
abstract class MeshPayDatabase : RoomDatabase() {
    abstract fun packetDao(): PacketDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        /**
         * v1 -> v2 (Task 17): collapse the two redundant local fields `hopCount`
         * (incrementing) and `ttl` (never read) into a single persisted forwarding
         * budget `remainingHopCount`. SQLite cannot drop columns in place, so the
         * `packets` table is recreated. Existing pending packets keep whatever budget
         * they had not yet spent: remaining = max(0, DEFAULT_MAX_HOPS - hopCount).
         * The literal 10 mirrors [com.meshpay.app.data.local.PacketStore.DEFAULT_MAX_HOPS]
         * (migrations are historical snapshots and cannot reference the live constant).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE packets_new (
                        packetId TEXT NOT NULL,
                        senderVPA TEXT NOT NULL,
                        receiverVPA TEXT NOT NULL,
                        amount REAL NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        remainingHopCount INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        uploadedBy TEXT,
                        PRIMARY KEY(packetId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO packets_new (
                        packetId, senderVPA, receiverVPA, amount, createdAt,
                        lastSeenAt, remainingHopCount, status, uploadedBy
                    )
                    SELECT
                        packetId, senderVPA, receiverVPA, amount, createdAt,
                        lastSeenAt, MAX(0, 10 - hopCount), status, uploadedBy
                    FROM packets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE packets")
                db.execSQL("ALTER TABLE packets_new RENAME TO packets")
            }
        }
    }
}
