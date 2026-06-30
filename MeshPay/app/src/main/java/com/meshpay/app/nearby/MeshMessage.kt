package com.meshpay.app.nearby

/**
 * Message-type discriminator for the MeshPay sync-protocol envelope.
 * Kept as constants (not an enum) so the wire value is an explicit, stable string.
 */
object MessageType {
    const val ADVERTISEMENT = "ADVERTISEMENT"
    const val REQUEST = "REQUEST"
    const val PACKET = "PACKET"
    const val ACK = "ACK"
}

/**
 * Lightweight summary of a pending packet for advertisement - identifiers only.
 * Carries no payment data (no sender / receiver / amount).
 */
data class PacketSummary(
    val packetId: String,
    val status: String
)

/**
 * Wire envelope for the sync protocol. Exactly one payload field is populated,
 * selected by [type]:
 *  - PACKET        -> [packet]
 *  - ADVERTISEMENT -> [summaries]
 *  - REQUEST       -> [packetIds]
 *  - ACK           -> [packetId]   (single id the receiver has persisted)
 *
 * Serialized / deserialized by [MeshMessageSerializer].
 */
data class MeshMessage(
    val type: String,
    val packet: MeshPacket? = null,
    val summaries: List<PacketSummary>? = null,
    val packetIds: List<String>? = null,
    val packetId: String? = null
) {
    companion object {
        fun packet(packet: MeshPacket): MeshMessage =
            MeshMessage(type = MessageType.PACKET, packet = packet)

        fun advertisement(summaries: List<PacketSummary>): MeshMessage =
            MeshMessage(type = MessageType.ADVERTISEMENT, summaries = summaries)

        fun request(packetIds: List<String>): MeshMessage =
            MeshMessage(type = MessageType.REQUEST, packetIds = packetIds)

        fun ack(packetId: String): MeshMessage =
            MeshMessage(type = MessageType.ACK, packetId = packetId)
    }
}

/**
 * A raw inbound transport message: the endpoint it arrived from and its UTF-8 payload.
 * This is the only "message" type [NearbyMeshService] (pure transport) knows about -
 * protocol meaning is assigned by [MeshProtocolHandler].
 */
data class InboundMessage(
    val endpointId: String,
    val message: String
)
