package com.meshpay.app.ui.wallet

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshpay.app.data.UserSession
import com.meshpay.app.data.WalletRepository
import com.meshpay.app.nearby.MeshPacket
import com.meshpay.app.nearby.MeshPacketSerializer
import com.meshpay.app.nearby.NearbyMeshService
import com.meshpay.app.network.BridgeIngestRequest
import com.meshpay.app.network.RetrofitClient
import com.meshpay.app.network.WalletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Uploading : UploadUiState()
    data class Success(val status: String, val message: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}

sealed class WalletUiState {
    data object Idle : WalletUiState()
    data object Loading : WalletUiState()
    data class Loaded(val vpa: String, val balance: Double) : WalletUiState()
    data class Error(val message: String, val lastBalance: Double?) : WalletUiState()
}

class HomeWalletViewModel(application: Application) : AndroidViewModel(application) {

    private val nearbyService = NearbyMeshService.getInstance(application)
    private val walletRepository = WalletRepository()
    private val pendingLogs = ArrayDeque<String>()
    private var logFlushJob: Job? = null

    val logs: SnapshotStateList<String> = mutableStateListOf()
    val connectedEndpoints: StateFlow<Set<String>> = nearbyService.connectedEndpoints
    val isAdvertising: StateFlow<Boolean> = nearbyService.isAdvertising
    val isDiscovering: StateFlow<Boolean> = nearbyService.isDiscovering
    val isConnected: StateFlow<Boolean> = nearbyService.isConnected

    private val _lastReceivedPacket = MutableStateFlow<MeshPacket?>(null)
    val lastReceivedPacket: StateFlow<MeshPacket?> = _lastReceivedPacket.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uploadState: StateFlow<UploadUiState> = _uploadState.asStateFlow()

    private val _uploadedPacketIds = MutableStateFlow<Set<String>>(emptySet())
    val uploadedPacketIds: StateFlow<Set<String>> = _uploadedPacketIds.asStateFlow()

    private val _registeredVpa = MutableStateFlow(UserSession.getRegisteredVpa(application).orEmpty())
    val registeredVpa: StateFlow<String> = _registeredVpa.asStateFlow()

    private val _walletState = MutableStateFlow<WalletUiState>(WalletUiState.Idle)
    val walletState: StateFlow<WalletUiState> = _walletState.asStateFlow()

    init {
        nearbyService.bindToScope(viewModelScope)
        refreshWalletBalance()

        viewModelScope.launch {
            nearbyService.logEvents.collect { line ->
                enqueueLog(line)
            }
        }

        viewModelScope.launch {
            nearbyService.receivedPackets.collect { packet ->
                _lastReceivedPacket.value = packet
                if (_uploadState.value !is UploadUiState.Uploading) {
                    _uploadState.value = UploadUiState.Idle
                }
            }
        }
    }

    fun startMesh() {
        viewModelScope.launch {
            nearbyService.startAdvertising()
        }
    }

    fun discoverPeers() {
        viewModelScope.launch {
            nearbyService.startDiscovery()
        }
    }

    fun sendTestPacket() {
        val senderVpa = _registeredVpa.value
        if (senderVpa.isBlank()) {
            logToService("test packet skipped - no registered VPA found")
            return
        }

        val testPacket = MeshPacket(
            packetId = UUID.randomUUID().toString(),
            sender = senderVpa,
            receiver = senderVpa,
            amount = 500,
            timestamp = Instant.now().toString()
        )

        viewModelScope.launch(Dispatchers.IO) {
            nearbyService.broadcastMeshPacket(testPacket)
        }
    }

    fun uploadPacketToBridge() {
        val packet = nearbyService.latestReceivedPacket.value
            ?: _lastReceivedPacket.value
        if (packet == null) {
            _uploadState.value = UploadUiState.Error("No packet to upload. Receive one via mesh first.")
            return
        }

        if (_uploadedPacketIds.value.contains(packet.packetId)) {
            logToService("upload skipped - packet ${packet.packetId} was already uploaded")
            _uploadState.value = UploadUiState.Error(
                "This packet was already uploaded. Receive or create a fresh packet before uploading again."
            )
            return
        }

        _uploadState.value = UploadUiState.Uploading
        logToService("upload started for packet ${packet.packetId}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isInternetAvailable()) {
                    logToService("upload failed - no internet connection")
                    _uploadState.value = UploadUiState.Error("No internet connection. Turn on Wi-Fi or mobile data first.")
                    return@launch
                }

                val plainPayload = MeshPacketSerializer.serialize(packet)
                val request = BridgeIngestRequest(
                    packetId = packet.packetId,
                    ttl = 5,
                    createdAt = Instant.now().toString(),
                    ciphertext = plainPayload
                )

                val response = RetrofitClient.apiService.bridgeIngest(request)

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.status ?: "UNKNOWN"
                    val message = body?.message ?: "No details"
                    logToService("upload success - status: $status")
                    _uploadedPacketIds.value = _uploadedPacketIds.value + packet.packetId
                    _uploadState.value = UploadUiState.Success(status, message)
                    if (status == "SETTLED") {
                        refreshSettlementWallets(packet)
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    val code = response.code()
                    logToService("upload failed - server returned $code: $errorBody")
                    _uploadState.value = UploadUiState.Error("Server error ($code): $errorBody")
                }
            } catch (e: Exception) {
                logToService("upload failed - ${e.localizedMessage ?: "Network error"}")
                _uploadState.value = UploadUiState.Error(
                    "Upload failed: ${e.localizedMessage ?: "Could not reach server"}"
                )
            }
        }
    }

    fun resetUploadState() {
        _uploadState.value = UploadUiState.Idle
    }

    fun refreshWalletBalance() {
        refreshWalletBalance(_registeredVpa.value, showLoading = true)
    }

    fun stopNearbyServices() {
        nearbyService.stopAll("explicit disconnect requested")
        nearbyService.clearLatestPacket()
        _lastReceivedPacket.value = null
    }

    fun clearLogs() {
        nearbyService.clearLogs()
        pendingLogs.clear()
        logFlushJob?.cancel()
        logs.clear()
    }

    fun logUiMessage(message: String) {
        logToService(message)
    }

    private fun refreshWalletBalance(vpa: String, showLoading: Boolean) {
        if (vpa.isBlank()) {
            _walletState.value = WalletUiState.Error("Register a wallet before refreshing balance.", null)
            return
        }

        if (showLoading) {
            _walletState.value = WalletUiState.Loading
        }

        viewModelScope.launch(Dispatchers.IO) {
            walletRepository.getWallet(vpa)
                .onSuccess { wallet ->
                    applyWalletResponse(wallet)
                    logToService("wallet balance refreshed for ${wallet.vpa}: Rs. ${formatAmount(wallet.balance)}")
                }
                .onFailure { error ->
                    val lastBalance = (_walletState.value as? WalletUiState.Loaded)?.balance
                    _walletState.value = WalletUiState.Error(
                        error.localizedMessage ?: "Could not refresh wallet balance",
                        lastBalance
                    )
                    logToService("wallet refresh failed - ${error.localizedMessage ?: "network error"}")
                }
        }
    }

    private suspend fun refreshSettlementWallets(packet: MeshPacket) {
        listOf(packet.sender, packet.receiver)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { vpa ->
                walletRepository.getWallet(vpa)
                    .onSuccess { wallet ->
                        logToService("settlement balance ${wallet.vpa}: Rs. ${formatAmount(wallet.balance)}")
                        if (wallet.vpa.equals(_registeredVpa.value, ignoreCase = true)) {
                            applyWalletResponse(wallet)
                        }
                    }
                    .onFailure { error ->
                        logToService("settlement balance refresh failed for $vpa - ${error.localizedMessage ?: "network error"}")
                    }
            }
    }

    private fun applyWalletResponse(wallet: WalletResponse) {
        _walletState.value = WalletUiState.Loaded(wallet.vpa, wallet.balance)
    }

    private fun enqueueLog(line: String) {
        pendingLogs.addLast(line)
        if (logFlushJob?.isActive == true) {
            return
        }

        logFlushJob = viewModelScope.launch {
            delay(LOG_FLUSH_INTERVAL_MS)
            flushPendingLogs()
        }
    }

    private fun flushPendingLogs() {
        if (pendingLogs.isEmpty()) {
            return
        }

        logs.addAll(pendingLogs)
        pendingLogs.clear()

        val overflow = logs.size - MAX_LOG_LINES
        repeat(overflow.coerceAtLeast(0)) {
            logs.removeAt(0)
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun logToService(message: String) {
        nearbyService.logEvent(message)
    }

    override fun onCleared() {
        nearbyService.destroy("ViewModel cleared")
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_LINES = 50
        private const val LOG_FLUSH_INTERVAL_MS = 250L
    }
}

private fun formatAmount(amount: Double): String = String.format(Locale.US, "%.2f", amount)
