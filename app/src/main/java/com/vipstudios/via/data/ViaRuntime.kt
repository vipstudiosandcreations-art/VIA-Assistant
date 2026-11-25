package com.vipstudios.via.data

import android.content.Context
import com.vipstudios.via.ai.ViaAiRepository
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.device.CallControlManager
import com.vipstudios.via.device.DeviceStatusMonitor
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide singleton coordinator shared by the UI ViewModel and WakeWordService
 * so background voice processing, battery alerts, call monitoring, and local store updates
 * stay consistent whether the VIA screen is open, minimized, or closed.
 */
class ViaRuntime private constructor(context: Context) {
    private val appCtx = context.applicationContext

    val store = Store(appCtx)
    val deviceMonitor = DeviceStatusMonitor(appCtx)
    val callManager = CallControlManager(appCtx)
    val aiRepo = ViaAiRepository()
    val sheetsRepo = GoogleSheetsRepository()
    val commandEngine = CommandEngine(appCtx, store, deviceMonitor, callManager)

    val storeUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    @Volatile
    var isUiForeground: Boolean = false

    fun ensureMonitorsActive() {
        deviceMonitor.start()
        if (store.callMonitoringEnabled && callManager.hasPhoneStatePermission()) {
            callManager.startMonitoring()
        } else {
            callManager.refreshPermissions()
        }
    }

    fun notifyStoreUpdated() {
        storeUpdates.tryEmit(Unit)
    }

    companion object {
        @Volatile
        private var instance: ViaRuntime? = null

        fun get(context: Context): ViaRuntime {
            return instance ?: synchronized(this) {
                instance ?: ViaRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
