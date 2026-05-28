package com.meshpay.app.nearby

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NearbyMeshService private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectionsClient = Nearby.getConnectionsClient(appContext)
    private val serviceId = "com.meshpay.app"
    private val localEndpointName = "MeshNode-" + android.os.Build.MODEL
    private val nearbyStrategy = Strategy.P2P_CLUSTER
    private val nearbyStrategyName = "P2P_CLUSTER"
    private val stateLock = Any()

    private val _logEvents = MutableSharedFlow<String>(
        replay = MAX_LOG_LINES,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logEvents: SharedFlow<String> = _logEvents.asSharedFlow()

    private val _receivedPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 10)
    val receivedPackets: SharedFlow<MeshPacket> = _receivedPackets.asSharedFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints: StateFlow<Set<String>> = _connectedEndpoints.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _latestReceivedPacket = MutableStateFlow<MeshPacket?>(null)
    val latestReceivedPacket: StateFlow<MeshPacket?> = _latestReceivedPacket.asStateFlow()

    @Volatile
    private var callbackScope: CoroutineScope? = null

    private var lifecycleToken = 0L
    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    private val connectingEndpoints = mutableSetOf<String>()
    private val pendingConnectionStartedAt = mutableMapOf<String, Long>()
    private val pendingConnectionTimeoutJobs = mutableMapOf<String, Job>()
    private val endpointCooldownUntil = mutableMapOf<String, Long>()
    private val reconnectAttemptCounts = mutableMapOf<String, Int>()
    private var activeEndpointId: String? = null

    companion object {
        private const val TAG = "NearbyMeshService"
        private const val MAX_LOG_LINES = 50
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val BASE_RECONNECT_COOLDOWN_MS = 12_000L
        private const val MAX_RECONNECT_COOLDOWN_MS = 60_000L

        @Volatile
        private var INSTANCE: NearbyMeshService? = null

        fun getInstance(context: Context): NearbyMeshService {
            return INSTANCE ?: synchronized(this) {
                val instance = NearbyMeshService(context)
                INSTANCE = instance
                instance
            }
        }

        fun getExistingInstance(): NearbyMeshService? = INSTANCE
    }

    fun bindToScope(scope: CoroutineScope) {
        callbackScope = scope
    }

    fun unbindScope(scope: CoroutineScope) {
        if (callbackScope == scope) {
            callbackScope = null
        }
    }

    fun logEvent(message: String) {
        Log.d(TAG, message)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logEvents.tryEmit("[$timestamp] $message")
    }

    suspend fun startAdvertising() {
        val token = synchronized(stateLock) {
            if (_isAdvertising.value || advertisingStartInFlight) {
                null
            } else {
                advertisingStartInFlight = true
                lifecycleToken
            }
        }

        if (token == null) {
            logEvent("Advertising already running")
            return
        }

        logEvent("Advertising starting")
        logEvent("Selected Nearby strategy: $nearbyStrategyName")
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(nearbyStrategy)
            .setLowPower(false)
            .build()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                connectionsClient.startAdvertising(
                    localEndpointName,
                    serviceId,
                    connectionLifecycleCallback,
                    advertisingOptions
                ).awaitUnit()
            }
        }

        var cleanupStaleStart = false
        synchronized(stateLock) {
            advertisingStartInFlight = false
            if (result.isSuccess && token == lifecycleToken) {
                _isAdvertising.value = true
            } else if (result.isSuccess) {
                cleanupStaleStart = true
            }
        }

        when {
            result.isSuccess && !cleanupStaleStart -> logEvent("Advertising started")
            cleanupStaleStart -> {
                withContext(Dispatchers.IO) { connectionsClient.stopAdvertising() }
                logEvent("Advertising start ignored after stop")
            }
            else -> logEvent("Advertising failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}")
        }
    }

    suspend fun startDiscovery() {
        val token = synchronized(stateLock) {
            if (_isDiscovering.value || discoveryStartInFlight) {
                null
            } else {
                discoveryStartInFlight = true
                lifecycleToken
            }
        }

        if (token == null) {
            logEvent("Discovery already running")
            return
        }

        logEvent("Discovery starting")
        logEvent("Selected Nearby strategy: $nearbyStrategyName")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(nearbyStrategy)
            .setLowPower(false)
            .build()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                connectionsClient.startDiscovery(
                    serviceId,
                    endpointDiscoveryCallback,
                    discoveryOptions
                ).awaitUnit()
            }
        }

        var cleanupStaleStart = false
        synchronized(stateLock) {
            discoveryStartInFlight = false
            if (result.isSuccess && token == lifecycleToken) {
                _isDiscovering.value = true
            } else if (result.isSuccess) {
                cleanupStaleStart = true
            }
        }

        when {
            result.isSuccess && !cleanupStaleStart -> logEvent("Discovery started")
            cleanupStaleStart -> {
                withContext(Dispatchers.IO) { connectionsClient.stopDiscovery() }
                logEvent("Discovery start ignored after stop")
            }
            else -> logEvent("Discovery failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}")
        }
    }

    fun stopAll(reason: String = "explicit disconnect requested") {
        val shouldStopAdvertising: Boolean
        val shouldStopDiscovery: Boolean
        val shouldStopEndpoints: Boolean

        synchronized(stateLock) {
            lifecycleToken++
            shouldStopAdvertising = _isAdvertising.value || advertisingStartInFlight
            shouldStopDiscovery = _isDiscovering.value || discoveryStartInFlight
            shouldStopEndpoints = _connectedEndpoints.value.isNotEmpty() || connectingEndpoints.isNotEmpty()
            advertisingStartInFlight = false
            discoveryStartInFlight = false
            activeEndpointId = null
            connectingEndpoints.clear()
            pendingConnectionStartedAt.clear()
            pendingConnectionTimeoutJobs.values.forEach { it.cancel() }
            pendingConnectionTimeoutJobs.clear()
            _isAdvertising.value = false
            _isDiscovering.value = false
            updateConnectedEndpoints(emptySet())
        }

        if (shouldStopAdvertising) {
            connectionsClient.stopAdvertising()
        }
        if (shouldStopDiscovery) {
            connectionsClient.stopDiscovery()
        }
        if (shouldStopEndpoints) {
            connectionsClient.stopAllEndpoints()
        }
        logEvent("Stopped Nearby services: $reason")
    }

    fun destroy(reason: String = "ViewModel cleared") {
        stopAll(reason)
        callbackScope = null
        logEvent("Nearby service destroyed")
    }

    suspend fun connectToEndpoint(endpointId: String) {
        val now = SystemClock.elapsedRealtime()
        val connectionDecision = synchronized(stateLock) {
            val activeEndpoint = activeEndpointId
            val cooldownMs = (endpointCooldownUntil[endpointId] ?: 0L) - now
            when {
                _connectedEndpoints.value.contains(endpointId) -> ConnectionDecision.AlreadyConnected
                activeEndpoint != null && _connectedEndpoints.value.contains(activeEndpoint) -> ConnectionDecision.ActiveEndpointExists(activeEndpoint)
                connectingEndpoints.contains(endpointId) -> ConnectionDecision.AlreadyPending
                cooldownMs > 0 -> ConnectionDecision.CoolingDown(cooldownMs)
                else -> {
                    connectingEndpoints.add(endpointId)
                    pendingConnectionStartedAt[endpointId] = now
                    val attempt = (reconnectAttemptCounts[endpointId] ?: 0) + 1
                    reconnectAttemptCounts[endpointId] = attempt
                    ConnectionDecision.Connect(attempt)
                }
            }
        }

        when (connectionDecision) {
            ConnectionDecision.AlreadyConnected -> {
                logEvent("Connection ignored, endpoint already connected: $endpointId")
                return
            }
            ConnectionDecision.AlreadyPending -> {
                logEvent("Connection ignored, endpoint already pending: $endpointId")
                return
            }
            is ConnectionDecision.ActiveEndpointExists -> {
                logEvent("Connection ignored, stable active endpoint already exists: ${connectionDecision.endpointId}")
                return
            }
            is ConnectionDecision.CoolingDown -> {
                logEvent("Reconnect attempt ignored for $endpointId; cooldown ${connectionDecision.remainingMs / 1000}s")
                return
            }
            is ConnectionDecision.Connect -> {
                logEvent("Reconnect attempt ${connectionDecision.attempt} for $endpointId")
                scheduleConnectionTimeout(endpointId)
            }
        }

        logEvent("Connection initiated: $endpointId")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val connectionOptions = ConnectionOptions.Builder()
                    .setLowPower(false)
                    .build()
                connectionsClient.requestConnection(
                    localEndpointName,
                    endpointId,
                    connectionLifecycleCallback,
                    connectionOptions
                ).awaitUnit()
            }
        }

        if (result.isSuccess) {
            logEvent("Connection request sent: $endpointId")
        } else {
            synchronized(stateLock) {
                connectingEndpoints.remove(endpointId)
                pendingConnectionStartedAt.remove(endpointId)
                pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                applyReconnectCooldownLocked(endpointId)
            }
            logEvent("Connection initiation failed for $endpointId: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}")
        }
    }

    suspend fun sendMeshPacket(endpointId: String, packet: MeshPacket): Boolean {
        val bytes = withContext(Dispatchers.IO) {
            MeshPacketSerializer.serialize(packet).toByteArray(StandardCharsets.UTF_8)
        }
        return sendPayloadBytes(endpointId, bytes, packet.packetId)
    }

    suspend fun broadcastMeshPacket(packet: MeshPacket): Boolean {
        val endpointId = activeEndpoint()
        if (endpointId == null) {
            logEvent("No connected peers to send packet to")
            return false
        }

        val bytes = withContext(Dispatchers.IO) {
            MeshPacketSerializer.serialize(packet).toByteArray(StandardCharsets.UTF_8)
        }

        return sendPayloadBytes(endpointId, bytes, packet.packetId)
    }

    private suspend fun sendPayloadBytes(endpointId: String, bytes: ByteArray, packetId: String): Boolean {
        if (!_connectedEndpoints.value.contains(endpointId)) {
            logEvent("Payload send skipped, endpoint not connected: $endpointId")
            return false
        }

        logEvent("Sending payload ${packetId.take(8)} to $endpointId")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes)).awaitUnit()
            }
        }

        return if (result.isSuccess) {
            logEvent("Payload sent to $endpointId")
            true
        } else {
            logEvent("Payload send failed for $endpointId: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}")
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearLogs() {
        _logEvents.resetReplayCache()
    }

    fun clearLatestPacket() {
        _latestReceivedPacket.value = null
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val shouldAccept = synchronized(stateLock) {
                val activeEndpoint = activeEndpointId
                val activeEndpointAvailable = activeEndpoint != null && _connectedEndpoints.value.contains(activeEndpoint)
                val shouldAccept = !activeEndpointAvailable && (_isAdvertising.value || _isDiscovering.value || connectingEndpoints.contains(endpointId))
                if (shouldAccept) {
                    connectingEndpoints.add(endpointId)
                    pendingConnectionStartedAt.putIfAbsent(endpointId, SystemClock.elapsedRealtime())
                }
                shouldAccept
            }
            if (!shouldAccept) {
                logEvent("Connection ignored after stop or active endpoint reuse: $endpointId")
                launchFromCallback("reject stale connection") {
                    withContext(Dispatchers.IO) {
                        runCatching { connectionsClient.rejectConnection(endpointId).awaitUnit() }
                    }
                }
                return
            }

            logEvent("Connection initiated: $endpointId (${info.endpointName})")
            scheduleConnectionTimeout(endpointId)
            launchFromCallback("accept connection") {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        connectionsClient.acceptConnection(endpointId, payloadCallback).awaitUnit()
                    }
                }
                if (result.isSuccess) {
                    logEvent("Connection accepted: $endpointId")
                } else {
                    synchronized(stateLock) {
                        connectingEndpoints.remove(endpointId)
                        pendingConnectionStartedAt.remove(endpointId)
                        pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                        applyReconnectCooldownLocked(endpointId)
                    }
                    logEvent("Connection accept failed for $endpointId: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}")
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val connectionAction = synchronized(stateLock) {
                        val wasConnecting = connectingEndpoints.remove(endpointId)
                        pendingConnectionStartedAt.remove(endpointId)
                        pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                        val currentActiveEndpoint = activeEndpointId
                        if (currentActiveEndpoint != null && currentActiveEndpoint != endpointId && _connectedEndpoints.value.contains(currentActiveEndpoint)) {
                            ConnectionResultAction.DisconnectDuplicate(currentActiveEndpoint)
                        } else if (wasConnecting) {
                            activeEndpointId = endpointId
                            endpointCooldownUntil.remove(endpointId)
                            reconnectAttemptCounts.remove(endpointId)
                            updateConnectedEndpoints(_connectedEndpoints.value + endpointId)
                            ConnectionResultAction.Keep
                        } else {
                            ConnectionResultAction.DisconnectStale
                        }
                    }
                    when (connectionAction) {
                        ConnectionResultAction.Keep -> {
                            logEvent("Connected: $endpointId")
                            logActiveEndpointCount()
                        }
                        is ConnectionResultAction.DisconnectDuplicate -> {
                            connectionsClient.disconnectFromEndpoint(endpointId)
                            logEvent("Disconnected duplicate endpoint $endpointId; reusing active endpoint ${connectionAction.activeEndpointId}")
                        }
                        ConnectionResultAction.DisconnectStale -> {
                            connectionsClient.disconnectFromEndpoint(endpointId)
                            logEvent("Connection ignored after stop: $endpointId")
                        }
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    synchronized(stateLock) {
                        connectingEndpoints.remove(endpointId)
                        pendingConnectionStartedAt.remove(endpointId)
                        pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                        applyReconnectCooldownLocked(endpointId)
                    }
                    logEvent("Connection rejected by $endpointId")
                }
                else -> {
                    synchronized(stateLock) {
                        connectingEndpoints.remove(endpointId)
                        pendingConnectionStartedAt.remove(endpointId)
                        pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                        applyReconnectCooldownLocked(endpointId)
                    }
                    logEvent("Connection failed with $endpointId: ${result.status.statusMessage ?: result.status.statusCode}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            synchronized(stateLock) {
                connectingEndpoints.remove(endpointId)
                pendingConnectionStartedAt.remove(endpointId)
                pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
                if (activeEndpointId == endpointId) {
                    activeEndpointId = null
                }
                updateConnectedEndpoints(_connectedEndpoints.value - endpointId)
                applyReconnectCooldownLocked(endpointId)
            }
            logEvent("Disconnected from $endpointId")
            logActiveEndpointCount()
        }

        override fun onBandwidthChanged(endpointId: String, bandwidthInfo: BandwidthInfo) {
            logEvent("Connection bandwidth changed for $endpointId: quality=${bandwidthInfo.qualityName()}; medium unavailable from Nearby API")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            logEvent("Endpoint found: $endpointId (${info.endpointName})")
            launchFromCallback("connect to endpoint") {
                connectToEndpoint(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            logEvent("Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                logEvent("Payload received from $endpointId")
                launchFromCallback("process payload") {
                    val packet = withContext(Dispatchers.IO) {
                        val json = String(bytes, StandardCharsets.UTF_8)
                        MeshPacketSerializer.deserialize(json)
                    }
                    if (packet != null) {
                        _latestReceivedPacket.value = packet
                        _receivedPackets.emit(packet)
                    } else {
                        logEvent("Payload parse failed from $endpointId")
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                logEvent("Payload transfer complete: $endpointId")
            }
        }
    }

    private fun updateConnectedEndpoints(endpoints: Set<String>) {
        _connectedEndpoints.value = endpoints
        _isConnected.value = endpoints.isNotEmpty()
    }

    private fun activeEndpoint(): String? = synchronized(stateLock) {
        val activeEndpoint = activeEndpointId
        if (activeEndpoint != null && _connectedEndpoints.value.contains(activeEndpoint)) {
            activeEndpoint
        } else {
            val replacement = _connectedEndpoints.value.firstOrNull()
            activeEndpointId = replacement
            replacement
        }
    }

    private fun scheduleConnectionTimeout(endpointId: String) {
        val scope = callbackScope
        if (scope?.isActive != true) {
            logEvent("Connection timeout not scheduled for $endpointId - no active ViewModel scope")
            return
        }

        synchronized(stateLock) {
            pendingConnectionTimeoutJobs.remove(endpointId)?.cancel()
            pendingConnectionTimeoutJobs[endpointId] = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                cleanupStalePendingConnection(endpointId)
            }
        }
    }

    private fun cleanupStalePendingConnection(endpointId: String) {
        val shouldCleanup = synchronized(stateLock) {
            if (connectingEndpoints.remove(endpointId)) {
                pendingConnectionStartedAt.remove(endpointId)
                pendingConnectionTimeoutJobs.remove(endpointId)
                applyReconnectCooldownLocked(endpointId)
                true
            } else {
                false
            }
        }

        if (shouldCleanup) {
            connectionsClient.disconnectFromEndpoint(endpointId)
            logEvent("Connection timeout cleanup for $endpointId after ${CONNECTION_TIMEOUT_MS / 1000}s")
        }
    }

    private fun applyReconnectCooldownLocked(endpointId: String) {
        val attempts = reconnectAttemptCounts[endpointId] ?: 1
        val multiplier = 1L shl (attempts - 1).coerceAtMost(3)
        val cooldownMs = (BASE_RECONNECT_COOLDOWN_MS * multiplier).coerceAtMost(MAX_RECONNECT_COOLDOWN_MS)
        endpointCooldownUntil[endpointId] = SystemClock.elapsedRealtime() + cooldownMs
        logEvent("Reconnect cooldown for $endpointId: ${cooldownMs / 1000}s")
    }

    private fun logActiveEndpointCount() {
        logEvent("Active endpoint count: ${_connectedEndpoints.value.size}")
    }

    private fun launchFromCallback(actionName: String, block: suspend () -> Unit) {
        val scope = callbackScope
        if (scope?.isActive == true) {
            scope.launch {
                block()
            }
        } else {
            logEvent("Nearby callback skipped ($actionName) - no active ViewModel scope")
        }
    }

    private suspend fun Task<Void>.awaitUnit() {
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener {
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel(CancellationException("Nearby task was cancelled"))
                }
            }
        }
    }

    private fun BandwidthInfo.qualityName(): String {
        return when (quality) {
            BandwidthInfo.Quality.HIGH -> "HIGH"
            BandwidthInfo.Quality.MEDIUM -> "MEDIUM"
            BandwidthInfo.Quality.LOW -> "LOW"
            BandwidthInfo.Quality.UNKNOWN -> "UNKNOWN"
            else -> quality.toString()
        }
    }

    private sealed class ConnectionDecision {
        data object AlreadyConnected : ConnectionDecision()
        data object AlreadyPending : ConnectionDecision()
        data class ActiveEndpointExists(val endpointId: String) : ConnectionDecision()
        data class CoolingDown(val remainingMs: Long) : ConnectionDecision()
        data class Connect(val attempt: Int) : ConnectionDecision()
    }

    private sealed class ConnectionResultAction {
        data object Keep : ConnectionResultAction()
        data object DisconnectStale : ConnectionResultAction()
        data class DisconnectDuplicate(val activeEndpointId: String) : ConnectionResultAction()
    }
}
