package com.meshpay.app.nearby

data class MeshPacket(
    val packetId: String,
    val sender: String,
    val receiver: String,
    val amount: Int,
    val timestamp: String
)
