package com.meshpay.app.nearby

import com.google.gson.Gson

object MeshPacketSerializer {
    private val gson = Gson()

    fun serialize(packet: MeshPacket): String {
        return gson.toJson(packet)
    }

    fun deserialize(json: String): MeshPacket? {
        return try {
            gson.fromJson(json, MeshPacket::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
