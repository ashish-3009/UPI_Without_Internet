package com.meshpay.app.data

import android.content.Context
import java.util.Locale
import java.util.UUID

object UserSession {
    private const val PREFS_NAME = "meshpay_user_session"
    private const val KEY_REGISTERED_VPA = "registered_vpa"
    private const val KEY_DEVICE_ID = "device_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads the SharedPreferences file into the per-process in-memory cache.
     * Call once off the UI critical path (e.g. Application.onCreate, before
     * StrictMode is armed) so later main-thread reads don't hit disk.
     */
    fun prewarm(context: Context) {
        prefs(context)
    }

    fun saveRegisteredVpa(context: Context, vpa: String) {
        val normalizedVpa = vpa.trim().lowercase(Locale.ROOT)
        prefs(context)
            .edit()
            .putString(KEY_REGISTERED_VPA, normalizedVpa)
            .apply()
    }

    fun getRegisteredVpa(context: Context): String? {
        return prefs(context)
            .getString(KEY_REGISTERED_VPA, null)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Returns a stable per-install identifier for this device acting as a bridge
     * node, generating and persisting one on first use. Used as X-Bridge-Node-Id
     * so settlements are attributed to a real node instead of "unknown".
     */
    fun getOrCreateDeviceId(context: Context): String {
        val store = prefs(context)
        store.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val deviceId = "bridge-" + UUID.randomUUID().toString()
        store.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
