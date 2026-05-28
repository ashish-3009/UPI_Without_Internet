package com.meshpay.app.network

/**
 * Response from POST /api/bridge/ingest.
 *
 * The backend returns a status string indicating the outcome:
 *   - SETTLED          → payment was successfully processed
 *   - DUPLICATE_DROPPED → same packet was already ingested
 *   - INVALID          → decryption or freshness check failed
 *   - REJECTED         → insufficient balance
 *
 * The message field carries a human-readable explanation.
 */
data class BridgeIngestResponse(
    val status: String? = null,
    val message: String? = null,
    val packetId: String? = null,
    val hash: String? = null
)
