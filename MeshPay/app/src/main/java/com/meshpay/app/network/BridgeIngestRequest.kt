package com.meshpay.app.network

/**
 * Maps to the backend's MeshPacket model.
 * The backend expects: { packetId, ttl, createdAt, ciphertext }
 *
 * Since encryption is not yet implemented in the APK, we serialize the plain
 * MeshPacket JSON as the "ciphertext" field. The backend accepts this mobile
 * demo shape and settles it, while real RSA-OAEP + AES-GCM ciphertext can use
 * the same request envelope later.
 */
data class BridgeIngestRequest(
    val packetId: String,
    val ttl: Int = 5,
    val createdAt: String,
    val ciphertext: String   // plain JSON for now; encrypted blob later
)
