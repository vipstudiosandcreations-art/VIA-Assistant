package com.vipstudios.via.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.R
import com.vipstudios.via.data.BatterySample
import com.vipstudios.via.data.Store
import com.vipstudios.via.time.WorldTimeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "DeviceStatusMonitor"

data class PhoneStatusSnapshot(
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val chargingSource: String = "Unknown",
    val batteryTempC: Float? = null,
    val batteryHealth: String = "Unknown",
    val batteryStatusLabel: String = "Unknown",
    val networkLabel: String = "Checking...",
    val isOnline: Boolean = false,
    val wifiStatus: String = "Unknown",
    val bluetoothStatus: String = "Unknown",
    val deviceDateTime: String = "",
    val deviceModel: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val torchOn: Boolean = false
)

data class BatteryAlertEvent(
    val key: String,
    val title: String,
    val message: String,
    val urgent: Boolean
)

/**
 * Monitors read-only device status (battery, charging, temp, health, Wi-Fi, network, Bluetooth,
 * flashlight, model) and emits deduplicated battery alerts without repeating the same alert.
 */
class DeviceStatusMonitor(
    context: Context,
    private val store: Store? = null
) {
    private val ctx = context.applicationContext

    val status = MutableStateFlow(PhoneStatusSnapshot())
    val batteryHistory = MutableStateFlow<List<BatterySample>>(store?.batteryHistory().orEmpty())
    val alerts = MutableSharedFlow<BatteryAlertEvent>(extraBufferCapacity = 8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sampleTickerJob: Job? = null
    private var started = false
    private var torchOn = false
    private var lastBatteryIntent: Intent? = null

    // Alert deduplication state
    private var lastAlertKey: String? = null
    private var wasCharging: Boolean? = null
    private var lastChargingMilestone: Int = -1
    private var lowAlertSent = false
    private var veryLowAlertSent = false
    private var fullAlertSent = false

    private val batteryAndStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshNow(batteryIntent = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) intent else null)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refreshNow() }
        override fun onLost(network: Network) { refreshNow() }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { refreshNow() }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, batteryAndStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to register NetworkCallback", e)
            else Log.w(TAG, "Unable to register NetworkCallback: ${e.javaClass.simpleName}")
        }
        refreshNow()
        sampleTickerJob?.cancel()
        sampleTickerJob = scope.launch {
            while (isActive && started) {
                delay(15_000L)
                if (started) refreshNow()
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sampleTickerJob?.cancel()
        sampleTickerJob = null
        try {
            ctx.unregisterReceiver(batteryAndStateReceiver)
        } catch (_: Exception) {
        }
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    fun refreshNow(batteryIntent: Intent? = null): PhoneStatusSnapshot {
        if (batteryIntent != null) {
            lastBatteryIntent = batteryIntent
        }
        val bIntent = batteryIntent
            ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: lastBatteryIntent
        val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
        }

        val battStatus = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = bIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = battStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            battStatus == BatteryManager.BATTERY_STATUS_FULL ||
            plugged > 0

        val chargingSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Connected" else "Not charging"
        }

        val rawTemp = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (rawTemp != Int.MIN_VALUE && rawTemp > -100) rawTemp / 10f else null

        val healthInt = bIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthLabel = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }

        val statusLabel = when (battStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> if (isCharging) "Charging" else "On battery"
        }

        val (online, netLabel, wifiConnected) = readNetworkSummary()
        val wifiStatus = readWifiStatus(wifiConnected)
        val btStatus = readBluetoothStatus()

        val timeSnap = WorldTimeEngine.snapshotForZone(
            zoneIdStr = java.time.ZoneId.systemDefault().id,
            label = "Local",
            use24Hour = false,
            includeSeconds = false
        )

        val snap = PhoneStatusSnapshot(
            batteryPercent = pct,
            isCharging = isCharging,
            chargingSource = chargingSource,
            batteryTempC = tempC,
            batteryHealth = healthLabel,
            batteryStatusLabel = statusLabel,
            networkLabel = netLabel,
            isOnline = online,
            wifiStatus = wifiStatus,
            bluetoothStatus = btStatus,
            deviceDateTime = "${timeSnap.formattedTime} · ${timeSnap.formattedDate}",
            torchOn = torchOn
        )
        status.value = snap
        recordBatterySample(pct = pct, isCharging = isCharging, tempC = tempC)

        evaluateBatteryAlerts(pct = pct, isCharging = isCharging, battStatus = battStatus)
        return snap
    }

    private fun recordBatterySample(pct: Int, isCharging: Boolean, tempC: Float?) {
        val now = System.currentTimeMillis()
        val current = batteryHistory.value
        val last = current.lastOrNull()
        val shouldRecord = last == null ||
            last.percent != pct ||
            last.isCharging != isCharging ||
            (now - last.timestampMs) >= 15_000L
        if (!shouldRecord) return

        val updated = (current + BatterySample(now, pct, isCharging, tempC)).takeLast(48)
        batteryHistory.value = updated
        store?.saveBatteryHistory(updated)
    }

    private fun evaluateBatteryAlerts(pct: Int, isCharging: Boolean, battStatus: Int) {
        val prevCharging = wasCharging
        wasCharging = isCharging

        // Reset opposite cycle flags cleanly
        if (isCharging) {
            lowAlertSent = false
            veryLowAlertSent = false
        } else {
            fullAlertSent = false
            lastChargingMilestone = -1
        }

        if (isCharging) {
            val isFull = battStatus == BatteryManager.BATTERY_STATUS_FULL || pct >= 100
            if (isFull && !fullAlertSent) {
                fullAlertSent = true
                emitAlert("full_$pct", "Battery Full", "Battery is fully charged.", urgent = false)
                return
            }

            if (prevCharging == false && !isFull) {
                lastChargingMilestone = (pct / 10) * 10
                emitAlert("charge_start_$pct", "Charging Started", "Charging started. Battery is at $pct percent.", urgent = false)
                return
            }

            if (!isFull && prevCharging == true) {
                val milestone = when {
                    pct >= 90 -> 90
                    pct >= 80 -> 80
                    pct >= 50 -> 50
                    else -> -1
                }
                if (milestone > 0 && milestone > lastChargingMilestone) {
                    lastChargingMilestone = milestone
                    emitAlert("charge_progress_$milestone", "Charging Progress", "Charging progress: battery reached $pct percent.", urgent = false)
                }
            }
        } else {
            if (pct in 1..10 && !veryLowAlertSent) {
                veryLowAlertSent = true
                lowAlertSent = true
                emitAlert(
                    "very_low_$pct",
                    "Very Low Battery",
                    "Battery is critically low at $pct percent. Please connect your charger immediately.",
                    urgent = true
                )
            } else if (pct in 11..20 && !lowAlertSent) {
                lowAlertSent = true
                emitAlert(
                    "low_20",
                    "Low Battery",
                    "Battery is low. Please connect your charger.",
                    urgent = true
                )
            }
        }
    }

    private fun emitAlert(key: String, title: String, message: String, urgent: Boolean) {
        if (key == lastAlertKey) return
        lastAlertKey = key
        val event = BatteryAlertEvent(key, title, message, urgent)
        alerts.tryEmit(event)
        postBatteryNotification(title, message, urgent)
    }

    private fun postBatteryNotification(title: String, message: String, urgent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BATTERY,
                    "VIA Battery Alerts",
                    if (urgent) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Battery charging and low-power alerts from VIA"
                }
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_BATTERY)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("VIA · $title")
                .setContentText(message)
                .setPriority(if (urgent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_BATTERY_ID, notif)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to post battery notification", e)
            else Log.w(TAG, "Unable to post battery notification: ${e.javaClass.simpleName}")
        }
    }

    private fun readNetworkSummary(): Triple<Boolean, String, Boolean> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
            ?: return Triple(false, "Unavailable", false)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return Triple(false, "Offline", false)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val transport = when {
            isWifi -> "Wi-Fi"
            isCell -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
        val label = if (validated) "Online ($transport)" else "Connected ($transport · checking internet)"
        return Triple(validated, label, isWifi)
    }

    private fun readWifiStatus(wifiConnected: Boolean): String {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?: return if (wifiConnected) "Connected" else "Unavailable"
        return try {
            when {
                wifiConnected -> "Connected"
                wm.isWifiEnabled -> "On (Not connected)"
                else -> "Off"
            }
        } catch (_: SecurityException) {
            if (wifiConnected) "Connected" else "Permission restricted"
        }
    }

    private fun readBluetoothStatus(): String {
        val bm = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter ?: return "Not supported"
        return try {
            val stateStr = if (adapter.isEnabled) "On" else "Off"
            if (Build.VERSION.SDK_INT >= 31) {
                val hasBtPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasBtPerm) {
                    val count = adapter.bondedDevices?.size ?: 0
                    if (adapter.isEnabled) "On ($count paired)" else "Off"
                } else {
                    "$stateStr (grant Bluetooth for device list)"
                }
            } else {
                stateStr
            }
        } catch (_: SecurityException) {
            "Permission required (Android 12+)"
        } catch (_: Exception) {
            "Unavailable"
        }
    }

    fun toggleFlashlight(desired: Boolean? = null): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
            ?: return "Camera service unavailable."
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return "This device doesn't report a controllable flashlight."

        val target = desired ?: !torchOn
        return try {
            cm.setTorchMode(id, target)
            torchOn = target
            refreshNow()
            if (target) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            "Couldn't change flashlight: ${e.message ?: "device restriction"}"
        }
    }

    fun buildSummarySpeech(): String {
        val s = refreshNow()
        val temp = s.batteryTempC?.let { String.format(Locale.US, ", temperature %.1f°C", it) } ?: ""
        return "Battery is at ${s.batteryPercent} percent (${s.batteryStatusLabel}$temp, health ${s.batteryHealth}). " +
            "Network is ${s.networkLabel}, Wi-Fi is ${s.wifiStatus}, and Bluetooth is ${s.bluetoothStatus}."
    }

    companion object {
        private const val CHANNEL_BATTERY = "via_battery_alerts"
        private const val NOTIF_BATTERY_ID = 77
    }
}
