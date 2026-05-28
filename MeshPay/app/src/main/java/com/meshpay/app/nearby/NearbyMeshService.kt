package com.meshpay.app.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

class NearbyMeshService private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectionsClient = Nearby.getConnectionsClient(appContext)
    private val serviceId = "com.meshpay.app"
    private val localEndpointName = "MeshNode-" + android.os.Build.MODEL

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _receivedPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 10)
    val receivedPackets: SharedFlow<MeshPacket> = _receivedPackets.asSharedFlow()

    private val _connectedEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectedEndpoints: StateFlow<Set<String>> = _connectedEndpoints.asStateFlow()

    private val _latestReceivedPacket = MutableStateFlow<MeshPacket?>(null)
    val latestReceivedPacket: StateFlow<MeshPacket?> = _latestReceivedPacket.asStateFlow()

    companion object {
        private const val TAG = "NearbyMeshService"

        @Volatile
        private var INSTANCE: NearbyMeshService? = null

        fun getInstance(context: Context): NearbyMeshService {
            return INSTANCE ?: synchronized(this) {
                val instance = NearbyMeshService(context)
                INSTANCE = instance
                instance
            }
        }
    }

    fun logEvent(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _logs.value = _logs.value + "[$timestamp] $message"
    }

    fun startAdvertising() {
        logEvent("Starting advertising...")
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            localEndpointName,
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            logEvent("Advertising started successfully")
        }.addOnFailureListener { e ->
            logEvent("Failed to start advertising: ${e.message}")
        }
    }

    fun startDiscovery() {
        logEvent("Starting discovery...")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            logEvent("Discovery started successfully")
        }.addOnFailureListener { e ->
            logEvent("Failed to start discovery: ${e.message}")
        }
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _connectedEndpoints.value = emptySet()
        logEvent("Stopped Nearby services and disconnected all endpoints")
    }

    fun connectToEndpoint(endpointId: String) {
        logEvent("connection initiated to $endpointId")
        connectionsClient.requestConnection(
            localEndpointName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            logEvent("connection initiated with $endpointId")
        }.addOnFailureListener { e ->
            logEvent("Failed to initiate connection to $endpointId: ${e.message}")
        }
    }

    fun sendMeshPacket(endpointId: String, packet: MeshPacket) {
        val json = MeshPacketSerializer.serialize(packet)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        
        logEvent("Sending payload to $endpointId: $json")
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                logEvent("payload sent to $endpointId")
            }
            .addOnFailureListener { e ->
                logEvent("Failed to send payload to $endpointId: ${e.message}")
            }
    }

    fun broadcastMeshPacket(packet: MeshPacket) {
        val endpoints = _connectedEndpoints.value
        if (endpoints.isEmpty()) {
            logEvent("No connected peers to send packet to")
            return
        }
        endpoints.forEach { endpointId ->
            sendMeshPacket(endpointId, packet)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            logEvent("connection initiated with $endpointId (${info.endpointName})")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    logEvent("Accepted connection from $endpointId")
                }
                .addOnFailureListener { e ->
                    logEvent("Failed to accept connection from $endpointId: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    logEvent("connected to $endpointId")
                    _connectedEndpoints.value = _connectedEndpoints.value + endpointId
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    logEvent("connection rejected by $endpointId")
                }
                else -> {
                    logEvent("connection failed with $endpointId: ${result.status.statusMessage}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            logEvent("disconnected from $endpointId")
            _connectedEndpoints.value = _connectedEndpoints.value - endpointId
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            logEvent("discovered endpoint: $endpointId (${info.endpointName})")
            // Automatically connect to discovered peers in the mesh
            connectToEndpoint(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            logEvent("lost endpoint: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val json = String(bytes, StandardCharsets.UTF_8)
                logEvent("payload received from $endpointId")
                val packet = MeshPacketSerializer.deserialize(json)
                if (packet != null) {
                    _latestReceivedPacket.value = packet
                    _receivedPackets.tryEmit(packet)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used for checking transfer states
        }
    }
}
