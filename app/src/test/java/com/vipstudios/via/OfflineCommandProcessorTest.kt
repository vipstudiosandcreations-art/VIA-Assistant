package com.vipstudios.via

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.Store
import com.vipstudios.via.device.CallControlManager
import com.vipstudios.via.device.DeviceStatusMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineCommandProcessorTest {

    private fun buildEngine(): CommandEngine {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = Store(ctx)
        val monitor = DeviceStatusMonitor(ctx, store)
        monitor.refreshNow(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 76)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                putExtra(BatteryManager.EXTRA_TEMPERATURE, 305)
                putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
            }
        )
        return CommandEngine(ctx, store, monitor, CallControlManager(ctx))
    }

    @Test
    fun offlineCommands_handleCameraVariationsAndBatteryStatusDirectly() {
        val engine = buildEngine()

        val cam1 = engine.execute("Open Camera", "en")
        assertTrue(cam1 is CommandResult.Navigate)
        assertEquals("camera", (cam1 as CommandResult.Navigate).route)

        val cam2 = engine.execute("Launch the VIA camera", "en")
        assertTrue(cam2 is CommandResult.Navigate)
        assertEquals("camera", (cam2 as CommandResult.Navigate).route)

        val batt1 = engine.execute("Battery Status", "en")
        assertTrue(batt1 is CommandResult.Handled)
        assertTrue((batt1 as CommandResult.Handled).reply.contains("76 percent"))

        val batt2 = engine.execute("Is my phone charging?", "en")
        assertTrue(batt2 is CommandResult.Handled)
        assertTrue((batt2 as CommandResult.Handled).reply.contains("Charging"))

        val dateRes = engine.execute("Date", "en")
        assertTrue(dateRes is CommandResult.Handled)
        assertTrue((dateRes as CommandResult.Handled).reply.contains("Today is"))

        val modelRes = engine.execute("What is my phone model?", "en")
        assertTrue(modelRes is CommandResult.Handled)
        assertTrue((modelRes as CommandResult.Handled).reply.contains("running Android"))
    }

    @Test
    fun offlineFallback_handlesLooseIntentsAndGeneralAiQueriesGracefully() {
        val engine = buildEngine()

        // Loose natural request when offline -> opens camera
        val photoFallback = engine.executeOfflineFallback("I want to take a selfie", "en")
        assertTrue(photoFallback is CommandResult.Navigate)
        assertEquals("camera", (photoFallback as CommandResult.Navigate).route)

        // Loose power phrasing when offline -> returns battery info
        val powerFallback = engine.executeOfflineFallback("how much power left", "en")
        assertTrue(powerFallback is CommandResult.Handled)
        assertTrue((powerFallback as CommandResult.Handled).reply.contains("76 percent"))

        // Offline greeting -> helpful offline intro
        val helloFallback = engine.executeOfflineFallback("hello", "en")
        assertTrue(helloFallback is CommandResult.Handled)
        assertTrue((helloFallback as CommandResult.Handled).reply.contains("offline mode"))

        // General AI knowledge question while offline -> fast local guidance instead of timing out
        val aiQuestionFallback = engine.executeOfflineFallback("Explain quantum entanglement", "en")
        assertTrue(aiQuestionFallback is CommandResult.Handled)
        val reply = (aiQuestionFallback as CommandResult.Handled).reply
        assertTrue(reply.contains("offline"))
        assertTrue(reply.contains("Open Camera"))
        assertTrue(reply.contains("Battery Status"))
    }
}
