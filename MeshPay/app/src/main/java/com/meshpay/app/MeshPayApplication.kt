package com.meshpay.app

import android.app.Application
import com.meshpay.app.data.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Custom Application class for MeshPay.
 * Initializes the Room persistence infrastructure at application startup.
 * No Room queries execute here — only the database builder is configured.
 */
class MeshPayApplication : Application() {

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Warm the SharedPreferences in-memory cache now, before StrictMode is armed
        // in MainActivity, so later main-thread reads (Navigation / ViewModels) don't
        // trigger a disk-read violation on cold start.
        UserSession.prewarm(this)
        // Restores pending packets from Room to PacketStore in-memory cache on startup
        applicationScope.launch {
            ServiceLocator.packetStore.restorePendingPackets()
        }
    }
}
