package com.meshpay.app.nearby

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Serializes and deserializes [MeshMessage] envelopes for the sync protocol.
 *
 * Deserialization is backward-compatible: a payload that has no "type" field is a
 * legacy bare [MeshPacket] JSON, so it is parsed via [MeshPacketSerializer] and
 * promoted to a PACKET message. This preserves existing payload handling - a node
 * that sends a raw MeshPacket is still understood.
 */
object MeshMessageSerializer {
    private val gson = Gson()

    fun serialize(message: MeshMessage): String = gson.toJson(message)

    fun deserialize(json: String): MeshMessage? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            if (obj != null && obj.has("type")) {
                gson.fromJson(json, MeshMessage::class.java)
            } else {
                // Legacy bare MeshPacket (no envelope): promote to a PACKET message.
                MeshPacketSerializer.deserialize(json)?.let { MeshMessage.packet(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
