package com.meshpay.app.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.Response

interface ApiService {

    @POST("/api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @GET("/api/accounts")
    suspend fun getAccounts(): Response<List<AccountResponse>>

    @GET("/api/transactions")
    suspend fun getTransactions(): Response<List<TransactionResponse>>

    @POST("/api/bridge/ingest")
    suspend fun bridgeIngest(@Body request: BridgeIngestRequest): Response<BridgeIngestResponse>
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
