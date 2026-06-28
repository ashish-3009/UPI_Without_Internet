package com.meshpay.app.nearby

data class MeshPacket(
    val packetId: String,
    val sender: String,
    val receiver: String,
    // Rupee amount with up to two decimal places (paise). Kept as Double so the
    // value matches the backend's BigDecimal(scale=2) ledger instead of being
    // truncated to whole rupees.
    val amount: Double,
    val timestamp: String
)
