package com.meshpay.app.ui.wallet

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshpay.app.nearby.MeshPacket
import com.meshpay.app.nearby.MeshPacketSerializer
import com.meshpay.app.nearby.NearbyMeshService
import com.meshpay.app.network.BridgeIngestRequest
import com.meshpay.app.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Uploading : UploadUiState()
    data class Success(val status: String, val message: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}

class HomeWalletViewModel(application: Application) : AndroidViewModel(application) {

    private val nearbyService = NearbyMeshService.getInstance(application)

    val logs: StateFlow<List<String>> = nearbyService.logs
    val connectedEndpoints: StateFlow<Set<String>> = nearbyService.connectedEndpoints

    private val _lastReceivedPacket = MutableStateFlow<MeshPacket?>(null)
    val lastReceivedPacket: StateFlow<MeshPacket?> = _lastReceivedPacket.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uploadState: StateFlow<UploadUiState> = _uploadState.asStateFlow()

    init {
        // Collect received packets from NearbyMeshService
        viewModelScope.launch {
            nearbyService.receivedPackets.collect { packet ->
                _lastReceivedPacket.value = packet
            }
        }
    }

    fun startMesh() {
        nearbyService.startAdvertising()
    }

    fun discoverPeers() {
        nearbyService.startDiscovery()
    }

    fun sendTestPacket() {
        val testPacket = MeshPacket(
            packetId = UUID.randomUUID().toString(),
            sender = "a@meshpay",
            receiver = "b@meshpay",
            amount = 500,
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        )
        nearbyService.broadcastMeshPacket(testPacket)
    }

    fun uploadPacketToBridge() {
        val packet = nearbyService.latestReceivedPacket.value
            ?: _lastReceivedPacket.value
        if (packet == null) {
            _uploadState.value = UploadUiState.Error("No packet to upload. Receive one via mesh first.")
            return
        }

        // Internet availability check
        if (!isInternetAvailable()) {
            nearbyService.clearLogs()  // don't clear, just log
            logToService("upload failed — no internet connection")
            _uploadState.value = UploadUiState.Error("No internet connection. Turn on Wi-Fi or mobile data first.")
            return
        }

        _uploadState.value = UploadUiState.Uploading
        logToService("upload started for packet ${packet.packetId}")

        viewModelScope.launch {
            try {
                // Convert MeshPacket → BridgeIngestRequest
                // The backend expects { packetId, ttl, createdAt, ciphertext }
                // Since we have no encryption yet, we pack the plain JSON as "ciphertext"
                val plainPayload = MeshPacketSerializer.serialize(packet)
                val request = BridgeIngestRequest(
                    packetId = packet.packetId,
                    ttl = 5,
                    createdAt = packet.timestamp,
                    ciphertext = plainPayload
                )

                val response = RetrofitClient.apiService.bridgeIngest(request)

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.status ?: "UNKNOWN"
                    val message = body?.message ?: "No details"
                    logToService("upload success — status: $status")
                    _uploadState.value = UploadUiState.Success(status, message)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    val code = response.code()
                    logToService("upload failed — server returned $code: $errorBody")
                    // Try to parse error body for status
                    _uploadState.value = UploadUiState.Error("Server error ($code): $errorBody")
                }
            } catch (e: Exception) {
                logToService("upload failed — ${e.localizedMessage ?: "Network error"}")
                _uploadState.value = UploadUiState.Error(
                    "Upload failed: ${e.localizedMessage ?: "Could not reach server"}"
                )
            }
        }
    }

    fun resetUploadState() {
        _uploadState.value = UploadUiState.Idle
    }

    fun stopNearbyServices() {
        nearbyService.stopAll()
        _lastReceivedPacket.value = null
    }

    fun clearLogs() {
        nearbyService.clearLogs()
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun logToService(message: String) {
        nearbyService.logEvent(message)
    }

    override fun onCleared() {
        super.onCleared()
        nearbyService.stopAll()
    }
}
