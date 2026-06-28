package com.meshpay.app.data.repository

import com.meshpay.app.data.entity.TransactionEntity
import com.meshpay.app.data.local.TransactionLocalDataSource

/**
 * Repository for wallet transaction history.
 * Exposes a clean API for future ViewModels by delegating
 * to [TransactionLocalDataSource]. Never accesses DAOs directly.
 */
class TransactionRepository(private val localDataSource: TransactionLocalDataSource) {

    suspend fun insertTransaction(transaction: TransactionEntity) =
        localDataSource.insertTransaction(transaction)

    suspend fun updateTransaction(transaction: TransactionEntity) =
        localDataSource.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity) =
        localDataSource.deleteTransaction(transaction)

    suspend fun getTransactionById(transactionId: String): TransactionEntity? =
        localDataSource.getTransactionById(transactionId)

    suspend fun getAllTransactions(): List<TransactionEntity> =
        localDataSource.getAllTransactions()
}
