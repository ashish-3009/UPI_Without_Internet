package com.meshpay.app.data

import android.content.Context
import java.util.Locale

object UserSession {
    private const val PREFS_NAME = "meshpay_user_session"
    private const val KEY_REGISTERED_VPA = "registered_vpa"

    fun saveRegisteredVpa(context: Context, vpa: String) {
        val normalizedVpa = vpa.trim().lowercase(Locale.ROOT)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGISTERED_VPA, normalizedVpa)
            .apply()
    }

    fun getRegisteredVpa(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REGISTERED_VPA, null)
            ?.takeIf { it.isNotBlank() }
    }
}
