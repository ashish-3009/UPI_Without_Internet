package com.meshpay.app.network

/**
 * Maps to the backend's MeshPacket model.
 * The backend expects: { packetId, ttl, createdAt, ciphertext }
 *
 * Since encryption is not yet implemented, we serialize the plain
 * MeshPacket JSON as the "ciphertext" field. The backend will respond
 * with INVALID (can't decrypt), but the upload connectivity flow is
 * fully exercised. Once hybrid encryption is added, this field will
 * carry real RSA-OAEP + AES-GCM ciphertext.
 */
data class BridgeIngestRequest(
    val packetId: String,
    val ttl: Int = 5,
    val createdAt: String,
    val ciphertext: String   // plain JSON for now; encrypted blob later
)
