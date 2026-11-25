package com.vipstudios.via

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.Store
import com.vipstudios.via.device.BatteryAlertEvent
import com.vipstudios.via.device.CallControlManager
import com.vipstudios.via.device.DeviceStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceControlsTest {

    private fun batteryIntent(level: Int, status: Int, plugged: Int = 0): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, status)
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 315)
            putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        }

    @Test
    fun batteryAlerts_deduplicateLowChargingAndFullStates() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val monitor = DeviceStatusMonitor(ctx)
        val emitted = mutableListOf<BatteryAlertEvent>()
        val job = launch(Dispatchers.Unconfined) {
            monitor.alerts.collect { emitted.add(it) }
        }

        monitor.refreshNow(batteryIntent(45, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(0, emitted.size)

        // Low battery -> emits once
        val lowSnap = monitor.refreshNow(batteryIntent(18, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(18, lowSnap.batteryPercent)
        assertFalse(lowSnap.isCharging)
        assertEquals(31.5f, lowSnap.batteryTempC)
        assertEquals(1, emitted.size)
        assertEquals("Battery is low. Please connect your charger.", emitted.last().message)

        // Still in low range -> does not repeat
        monitor.refreshNow(batteryIntent(15, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(1, emitted.size)

        // Very low battery -> stronger warning once
        monitor.refreshNow(batteryIntent(8, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(2, emitted.size)
        assertTrue(emitted.last().message.contains("critically low"))

        // Repeated very low -> does not repeat
        monitor.refreshNow(batteryIntent(8, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(2, emitted.size)

        // Charging started -> emits once
        val chargingSnap = monitor.refreshNow(
            batteryIntent(10, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC)
        )
        assertTrue(chargingSnap.isCharging)
        assertEquals("AC Charger", chargingSnap.chargingSource)
        assertEquals(3, emitted.size)
        assertTrue(emitted.last().message.startsWith("Charging started."))

        // Charging progress milestone at 50% -> emits once
        monitor.refreshNow(batteryIntent(50, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(4, emitted.size)
        assertTrue(emitted.last().message.contains("Charging progress"))

        // 55% stays in 50% milestone -> does not repeat
        monitor.refreshNow(batteryIntent(55, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(4, emitted.size)

        // Full charge -> emits once
        val fullSnap = monitor.refreshNow(
            batteryIntent(100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC)
        )
        assertEquals("Fully charged", fullSnap.batteryStatusLabel)
        assertEquals(5, emitted.size)
        assertEquals("Battery is fully charged.", emitted.last().message)

        // Repeated full charge -> does not repeat
        monitor.refreshNow(batteryIntent(100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(5, emitted.size)

        job.cancel()
    }

    @Test
    fun commandEngine_routesCameraControlsAndCallsSafely() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val monitor = DeviceStatusMonitor(ctx)
        val callMgr = CallControlManager(ctx)
        val engine = CommandEngine(ctx, Store(ctx), monitor, callMgr)

        val camRes = engine.execute("open camera", "en")
        assertTrue(camRes is CommandResult.Navigate)
        assertEquals("camera", (camRes as CommandResult.Navigate).route)

        val ctrlRes = engine.execute("open device controls", "en")
        assertTrue(ctrlRes is CommandResult.Navigate)
        assertEquals("controls", (ctrlRes as CommandResult.Navigate).route)

        val statusRes = engine.execute("phone status", "en")
        assertTrue(statusRes is CommandResult.Handled)
        assertTrue((statusRes as CommandResult.Handled).reply.contains("Battery is at"))

        // Without runtime permissions granted, answer/decline safely inform user instead of crashing
        val answerRes = engine.execute("answer the call", "en")
        assertTrue(answerRes is CommandResult.Handled)
        assertTrue((answerRes as CommandResult.Handled).reply.contains("permission"))

        val declineRes = engine.execute("decline the call", "en")
        assertTrue(declineRes is CommandResult.Handled)
        assertTrue((declineRes as CommandResult.Handled).reply.contains("permission"))
    }
}
