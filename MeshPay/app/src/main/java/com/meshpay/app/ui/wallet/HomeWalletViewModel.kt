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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import java.util.UUID
import com.meshpay.app.ServiceLocator
import com.meshpay.app.data.entity.PacketEntity
import com.meshpay.app.data.entity.PacketStatus
import com.meshpay.app.data.local.PacketStore

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
    private val protocolHandler = ServiceLocator.meshProtocolHandler
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
        // Reliable retransmission: run the ACK retry scheduler on this scope while the
        // mesh screen is active (Task 16). Idempotent across ViewModel recreation.
        protocolHandler.startRetryScheduler(viewModelScope)
        refreshWalletBalance()

        viewModelScope.launch {
            nearbyService.logEvents.collect { line ->
                enqueueLog(line)
            }
        }

        viewModelScope.launch {
            // Pump raw inbound transport messages into the protocol handler, which owns
            // deserialization and ADVERTISEMENT/REQUEST/PACKET processing.
            nearbyService.receivedMessages.collect { inbound ->
                // Isolate per-message failures: a transient Room/transport exception in
                // handleInbound must not cancel this collector, or the node would silently
                // stop processing all inbound mesh traffic until the ViewModel is recreated.
                try {
                    protocolHandler.handleInbound(inbound.endpointId, inbound.message)
                } catch (e: CancellationException) {
                    throw e // never swallow cancellation - keep structured concurrency intact
                } catch (e: Exception) {
                    logToService("inbound message failed from ${inbound.endpointId}: ${e.localizedMessage ?: e.javaClass.simpleName}")
                }
            }
        }

        viewModelScope.launch {
            // React to genuinely new packets the handler accepted (duplicates never
            // reach here). UI-state only - persistence/cache/logging live in the handler.
            protocolHandler.receivedPackets.collect {
                refreshLastReceivedPacket()
                if (_uploadState.value !is UploadUiState.Uploading) {
                    _uploadState.value = UploadUiState.Idle
                }
            }
        }

        viewModelScope.launch {
            var attempts = 0
            while (ServiceLocator.packetStore.getCachedPackets().isEmpty() && attempts < 5) {
                delay(50)
                attempts++
            }
            refreshLastReceivedPacket()
        }

        viewModelScope.launch {
            // Selective sync (Task 14): when a new peer connects, advertise this node's
            // pending packet ids; the handler/peer negotiate which packets to transfer.
            // We diff the connected-endpoint set so we advertise only to newly added
            // endpoints. Nearby connection management is untouched - we only observe it.
            var knownEndpoints = emptySet<String>()
            nearbyService.connectedEndpoints.collect { current ->
                val newlyConnected = current - knownEndpoints
                knownEndpoints = current
                newlyConnected.forEach { endpointId ->
                    viewModelScope.launch { protocolHandler.advertiseTo(endpointId) }
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
            amount = 500.0,
            timestamp = Instant.now().toString()
        )

        viewModelScope.launch(Dispatchers.IO) {
            val packetEntity = PacketEntity(
                packetId = testPacket.packetId,
                senderVPA = testPacket.sender,
                receiverVPA = testPacket.receiver,
                amount = testPacket.amount,
                createdAt = Instant.parse(testPacket.timestamp).toEpochMilli(),
                lastSeenAt = Instant.parse(testPacket.timestamp).toEpochMilli(),
                remainingHopCount = PacketStore.DEFAULT_MAX_HOPS,
                status = PacketStatus.CREATED,
                uploadedBy = null
            )
            ServiceLocator.packetRepository.insertPacket(packetEntity)
            ServiceLocator.packetStore.addPacket(testPacket)

            val sent = protocolHandler.broadcastPacket(testPacket)
            if (sent) {
                // Broadcast succeeded: the packet is now relaying through the mesh.
                ServiceLocator.packetStore.transition(testPacket.packetId, PacketStatus.RELAYING)
            }

            withContext(Dispatchers.Main) {
                refreshLastReceivedPacket()
            }
        }
    }

    fun uploadPacketToBridge() {
        val packet = protocolHandler.latestReceivedPacket.value
            ?: _lastReceivedPacket.value
        if (packet == null) {
            _uploadState.value = UploadUiState.Error("No packet to upload. Receive one via mesh first.")
            return
        }

        _uploadState.value = UploadUiState.Uploading
        logToService("upload started for packet ${packet.packetId}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Room is the source of truth for whether a packet is still pending.
                // If the packet was already moved past CREATED/RELAYING (e.g. UPLOADED),
                // it must not be uploaded again - this survives process restarts.
                val existing = ServiceLocator.packetRepository.getPacketById(packet.packetId)
                if (existing != null &&
                    existing.status != PacketStatus.CREATED &&
                    existing.status != PacketStatus.RELAYING
                ) {
                    logToService(
                        "upload skipped - packet ${packet.packetId} already processed (status ${existing.status})"
                    )
                    _uploadState.value = UploadUiState.Error(
                        "This packet was already uploaded. Receive or create a fresh packet before uploading again."
                    )
                    return@launch
                }

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

                // Identify this device as the uploading bridge node and report how many
                // local forwards this packet has consumed so the backend records real
                // provenance instead of defaulting to "unknown" / 0. Hops travelled is
                // derived from the single forwarding budget: DEFAULT_MAX_HOPS minus the
                // budget still remaining (a never-forwarded packet reports 0).
                val bridgeNodeId = UserSession.getOrCreateDeviceId(getApplication())
                val remaining = existing?.remainingHopCount ?: PacketStore.DEFAULT_MAX_HOPS
                val hopCount = PacketStore.DEFAULT_MAX_HOPS - remaining
                val response = RetrofitClient.apiService.bridgeIngest(request, bridgeNodeId, hopCount)

                if (response.isSuccessful) {
                    val body = response.body()
                    val status = body?.status ?: "UNKNOWN"
                    val message = body?.message ?: "No details"

                    // Only SETTLED (money moved) and DUPLICATE_DROPPED (already
                    // ingested by the bridge) are terminal. INVALID / REJECTED /
                    // unknown mean nothing settled, so the packet must stay pending
                    // and remain retryable instead of being retired locally.
                    val terminal = status == "SETTLED" || status == "DUPLICATE_DROPPED"
                    if (terminal) {
                        logToService("upload complete - status: $status")

                        // Advance the lifecycle through the central state machine so
                        // Room, the PacketStore cache and the UI stay in sync and every
                        // transition is validated and logged. Both terminal outcomes mean
                        // the packet is settled: SETTLED is this device's upload moving the
                        // money, DUPLICATE_DROPPED means the bridge already ingested it (so
                        // it is settled elsewhere in the mesh). We do not distinguish the
                        // two in the lifecycle - both go UPLOADED -> SETTLED. PacketStore
                        // .transition evicts the packet from the in-memory cache once it is
                        // terminal, so the explicit removePacket call is no longer needed.
                        ServiceLocator.packetStore.transition(packet.packetId, PacketStatus.UPLOADED)
                        ServiceLocator.packetStore.transition(packet.packetId, PacketStatus.SETTLED)

                        _uploadedPacketIds.value = _uploadedPacketIds.value + packet.packetId
                        protocolHandler.clearLatestPacket()

                        // Show only this packet's result; do not pin it above a
                        // different, not-yet-uploaded packet. The next pending packet
                        // is surfaced when the user dismisses via resetUploadState().
                        withContext(Dispatchers.Main) {
                            _lastReceivedPacket.value = null
                        }
                        _uploadState.value = UploadUiState.Success(status, message)
                        if (status == "SETTLED") {
                            refreshSettlementWallets(packet)
                        }
                    } else {
                        // Not settled: keep the packet pending/selectable for retry.
                        logToService("upload not settled - status: $status ($message)")
                        _uploadState.value = UploadUiState.Error("Not settled ($status): $message")
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
        // After a terminal upload the selected packet was cleared; surface the next
        // pending packet (if any) now that the result card is dismissed.
        refreshLastReceivedPacket()
    }

    fun refreshWalletBalance() {
        refreshWalletBalance(_registeredVpa.value, showLoading = true)
    }

    fun stopNearbyServices() {
        nearbyService.stopAll("explicit disconnect requested")
        protocolHandler.clearLatestPacket()
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

    private fun refreshLastReceivedPacket() {
        val received = protocolHandler.latestReceivedPacket.value
        if (received != null && !_uploadedPacketIds.value.contains(received.packetId)) {
            _lastReceivedPacket.value = received
            return
        }

        val pendingPackets = ServiceLocator.packetStore.getCachedPackets()
        val latestPending = pendingPackets
            .filter { it.packetId !in _uploadedPacketIds.value }
            .maxByOrNull {
                try {
                    Instant.parse(it.timestamp).toEpochMilli()
                } catch (e: Exception) {
                    0L
                }
            }
        _lastReceivedPacket.value = latestPending
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
