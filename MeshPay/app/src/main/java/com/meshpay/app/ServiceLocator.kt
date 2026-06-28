package com.meshpay.app

import android.content.Context
import androidx.room.Room
import com.meshpay.app.data.MeshPayDatabase
import com.meshpay.app.data.local.PacketLocalDataSource
import com.meshpay.app.data.local.TransactionLocalDataSource
import com.meshpay.app.data.repository.PacketRepository
import com.meshpay.app.data.repository.TransactionRepository
import com.meshpay.app.data.local.PacketStore

/**
 * Application-scoped service locator for the Room persistence layer.
 * Lazily creates the database and all downstream dependencies exactly once.
 *
 * Usage: call [ServiceLocator.init] from [MeshPayApplication.onCreate],
 * then access repositories via [ServiceLocator.packetRepository] /
 * [ServiceLocator.transactionRepository].
 *
 * No Room operations execute until a repository method is actually called.
 */
object ServiceLocator {

    private lateinit var database: MeshPayDatabase

    val packetRepository: PacketRepository by lazy {
        val dao = database.packetDao()
        val localDataSource = PacketLocalDataSource(dao)
        PacketRepository(localDataSource)
    }

    val transactionRepository: TransactionRepository by lazy {
        val dao = database.transactionDao()
        val localDataSource = TransactionLocalDataSource(dao)
        TransactionRepository(localDataSource)
    }

    val packetStore: PacketStore by lazy {
        PacketStore(packetRepository)
    }

    fun init(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext,
            MeshPayDatabase::class.java,
            "meshpay.db"
        ).build()
    }
}
