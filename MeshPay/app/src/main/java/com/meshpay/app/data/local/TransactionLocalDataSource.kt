package com.meshpay.app.data.local

import com.meshpay.app.data.dao.TransactionDao
import com.meshpay.app.data.entity.TransactionEntity

/**
 * Local data source for wallet transaction history.
 * Wraps [TransactionDao] operations — repositories must use this layer
 * instead of accessing the DAO directly.
 */
class TransactionLocalDataSource(private val transactionDao: TransactionDao) {

    suspend fun insertTransaction(transaction: TransactionEntity) =
        transactionDao.insertTransaction(transaction)

    suspend fun updateTransaction(transaction: TransactionEntity) =
        transactionDao.updateTransaction(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity) =
        transactionDao.deleteTransaction(transaction)

    suspend fun getTransactionById(transactionId: String): TransactionEntity? =
        transactionDao.getTransactionById(transactionId)

    suspend fun getAllTransactions(): List<TransactionEntity> =
        transactionDao.getAllTransactions()
}
