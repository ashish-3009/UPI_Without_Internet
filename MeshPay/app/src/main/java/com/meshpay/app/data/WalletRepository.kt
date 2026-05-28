package com.meshpay.app.data

import com.meshpay.app.network.ApiService
import com.meshpay.app.network.RetrofitClient
import com.meshpay.app.network.WalletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class WalletRepository(
    private val apiService: ApiService = RetrofitClient.apiService
) {
    suspend fun getWallet(vpa: String): Result<WalletResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedVpa = vpa.trim().lowercase(Locale.ROOT)
            val response = apiService.getWallet(normalizedVpa)
            if (response.isSuccessful) {
                response.body() ?: error("Empty wallet response")
            } else {
                walletFromAccountsFallback(normalizedVpa)
            }
        }
    }

    private suspend fun walletFromAccountsFallback(vpa: String): WalletResponse {
        val response = apiService.getAccounts()
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string()
            error("Wallet fetch failed (${response.code()}): ${errorBody ?: response.message()}")
        }

        val account = response.body()
            ?.firstOrNull { it.vpa.equals(vpa, ignoreCase = true) }
            ?: error("Wallet not found for $vpa")
        return WalletResponse(account.vpa, account.balance)
    }
}
