package com.meshpay.app.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.Response

interface ApiService {

    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @GET("/api/accounts")
    suspend fun getAccounts(): Response<List<AccountResponse>>

    @GET("/api/transactions")
    suspend fun getTransactions(): Response<List<TransactionResponse>>

    @GET("/api/wallet/{vpa}")
    suspend fun getWallet(@Path("vpa") vpa: String): Response<WalletResponse>

    @POST("/api/bridge/ingest")
    suspend fun bridgeIngest(
        @Body request: BridgeIngestRequest,
        @Header("X-Bridge-Node-Id") bridgeNodeId: String,
        @Header("X-Hop-Count") hopCount: Int
    ): Response<BridgeIngestResponse>
}

// ── Request / Response models ──

data class RegisterRequest(
    val userId: String,
    val phoneNumber: String,
    val publicKey: String
)

data class RegisterResponse(
    val status: String? = null,
    val message: String? = null,
    val userId: String? = null
)

data class AccountResponse(
    val vpa: String,
    val holderName: String,
    val balance: Double
)

data class TransactionResponse(
    val id: Long,
    val senderVpa: String,
    val receiverVpa: String,
    val amount: Double,
    val status: String,
    val hopCount: Int,
    val bridgeNodeId: String
)
