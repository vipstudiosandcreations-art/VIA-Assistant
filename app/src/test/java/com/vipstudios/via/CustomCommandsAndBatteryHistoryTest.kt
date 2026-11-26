package com.vipstudios.via

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.CustomActionType
import com.vipstudios.via.data.Store
import com.vipstudios.via.device.DeviceStatusMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomCommandsAndBatteryHistoryTest {

    private lateinit var ctx: Context
    private lateinit var store: Store

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("via_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = Store(ctx)
    }

    private fun batteryIntent(level: Int, status: Int, plugged: Int = 0): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_STATUS, status)
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            putExtra(BatteryManager.EXTRA_TEMPERATURE, 305)
            putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
        }

    @Test
    fun store_customCommandCrudWorks() {
        assertTrue(store.customCommands().isEmpty())

        store.addCustomCommand("Show my photos", CustomActionType.OPEN_GALLERY.id)
        store.addCustomCommand("Text Mom", CustomActionType.SEND_MESSAGE.id, "Running late")
        val added = store.customCommands()
        assertEquals(2, added.size)
        assertEquals("Show my photos", added[0].phrase)
        assertEquals(CustomActionType.OPEN_GALLERY.id, added[0].actionId)
        assertEquals("Running late", added[1].actionArg)

        store.updateCustomCommand(added[0].id, "Open Album", CustomActionType.OPEN_CAMERA.id, "")
        val updated = store.customCommands()
        assertEquals("Open Album", updated[0].phrase)
        assertEquals(CustomActionType.OPEN_CAMERA.id, updated[0].actionId)

        store.deleteCustomCommand(added[0].id)
        val remaining = store.customCommands()
        assertEquals(1, remaining.size)
        assertEquals("Text Mom", remaining[0].phrase)
    }

    @Test
    fun commandEngine_matchesPersonalizedPhrasesCaseInsensitively() {
        val monitor = DeviceStatusMonitor(ctx, store)
        val engine = CommandEngine(ctx, store, monitor)

        store.addCustomCommand("Show my photos", CustomActionType.OPEN_GALLERY.id)
        store.addCustomCommand("Text Mom", CustomActionType.SEND_MESSAGE.id, "On my way")
        store.addCustomCommand("Camera snap", CustomActionType.OPEN_CAMERA.id)
        store.addCustomCommand("Battery check please", CustomActionType.CHECK_BATTERY.id)

        val galleryRes = engine.execute("show my photos.", "en")
        assertTrue(galleryRes is CommandResult.Handled)

        val msgRes = engine.execute("please text mom!", "en")
        assertTrue(msgRes is CommandResult.Handled)

        val camRes = engine.execute("Camera Snap", "en")
        assertTrue(camRes is CommandResult.Navigate)
        assertEquals("camera", (camRes as CommandResult.Navigate).route)

        val battRes = engine.execute("battery check please?", "en")
        assertTrue(battRes is CommandResult.Handled)
        assertTrue((battRes as CommandResult.Handled).reply.contains("Battery is at"))
    }

    @Test
    fun deviceStatusMonitor_recordsAndPersistsBatteryTrendSamples() {
        val monitor = DeviceStatusMonitor(ctx, store)
        assertTrue(monitor.batteryHistory.value.isEmpty())

        monitor.refreshNow(batteryIntent(72, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(1, monitor.batteryHistory.value.size)
        assertEquals(72, monitor.batteryHistory.value.last().percent)
        assertFalse(monitor.batteryHistory.value.last().isCharging)

        // Same percent & state immediately -> deduplicated
        monitor.refreshNow(batteryIntent(72, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(1, monitor.batteryHistory.value.size)

        // Percent drop -> recorded
        monitor.refreshNow(batteryIntent(70, BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(2, monitor.batteryHistory.value.size)
        assertEquals(70, monitor.batteryHistory.value.last().percent)

        // Charging started at same percent -> recorded
        monitor.refreshNow(batteryIntent(70, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(3, monitor.batteryHistory.value.size)
        assertTrue(monitor.batteryHistory.value.last().isCharging)

        // Persisted in Store for future restarts
        val reloaded = Store(ctx).batteryHistory()
        assertEquals(3, reloaded.size)
        assertEquals(72, reloaded.first().percent)
        assertTrue(reloaded.last().isCharging)
    }

    @Test
    fun store_defaultsToVoiceIdK24eC7JpUgk8zMtQYrpV() {
        assertEquals("K24eC7JpUgk8zMtQYrpV", Store.DEFAULT_VOICE_ID)
        assertEquals("K24eC7JpUgk8zMtQYrpV", com.vipstudios.via.voice.ElevenLabsTtsClient.DEFAULT_VOICE_ID)
        assertEquals("K24eC7JpUgk8zMtQYrpV", store.voiceId)
        assertTrue(store.neuralVoiceEnabled)

        store.voiceId = "  "
        assertEquals("K24eC7JpUgk8zMtQYrpV", store.voiceId)

        store.voiceId = "customVoice123"
        assertEquals("customVoice123", Store(ctx).voiceId)
    }
}
