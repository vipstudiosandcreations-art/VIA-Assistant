package com.vipstudios.via.commands

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.vipstudios.via.data.Store
import com.vipstudios.via.device.CallControlManager
import com.vipstudios.via.device.DeviceStatusMonitor
import com.vipstudios.via.time.TimeQueryResult
import com.vipstudios.via.time.WorldTimeEngine
import java.util.Locale

/**
 * Offline command & fallback processor that keeps core VIA device controls, status queries,
 * local memory/tasks, world clock, and friendly guidance responsive whenever network connectivity
 * is unavailable (without waiting on Gemini AI network timeouts).
 */
class OfflineCommandProcessor(
    private val ctx: Context,
    private val store: Store,
    private val deviceMonitor: DeviceStatusMonitor? = null,
    private val callManager: CallControlManager? = null
) {

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Evaluates strict device/system commands that should execute immediately offline or online
     * before falling through to AI chat.
     */
    fun processDirectDeviceQuery(raw: String): CommandResult {
        val text = raw.trim()
        if (text.isEmpty()) return CommandResult.NotACommand
        val l = text.lowercase(Locale.ROOT)
            .replace(Regex("[?!.]+$"), "")
            .trim()

        // Camera screen triggers
        if (l == "camera" ||
            l == "via camera" ||
            Regex("^(?:please\\s+)?(?:open|launch|start|activate|show|turn\\s+on)\\s+(?:the\\s+)?(?:via\\s+)?camera\\b").containsMatchIn(l) ||
            Regex("\\b(take\\s+a?\\s*photo|take\\s+a?\\s*picture|capture\\s+a?\\s*photo)\\b|कैमरा|ಕ್ಯಾಮೆರಾ").containsMatchIn(l)
        ) {
            return CommandResult.Navigate("camera", "Opening VIA camera.")
        }

        // In-app navigation screens
        if (Regex("^(?:please\\s+)?(?:open|show|launch)?\\s*(?:via\\s+)?(?:device\\s+controls|phone\\s+controls|controls)$").containsMatchIn(l)) {
            return CommandResult.Navigate("controls", "Opening VIA Device Controls.")
        }
        if (Regex("^(?:please\\s+)?(?:open|show|launch)?\\s*(?:world\\s+clock|clock)$").containsMatchIn(l)) {
            return CommandResult.Navigate("clock", "Opening real-time world clock.")
        }
        if (Regex("^(?:please\\s+)?(?:open|show)\\s+(?:via\\s+)?(?:custom\\s+(?:voice\\s+)?commands)$").containsMatchIn(l)) {
            return CommandResult.Navigate("custom_commands", "Opening Custom Voice Commands.")
        }
        if (Regex("^(?:please\\s+)?(?:open|show)\\s+(?:via\\s+)?settings$").containsMatchIn(l)) {
            return CommandResult.Navigate("settings", "Opening VIA Settings.")
        }
        if (Regex("^(?:please\\s+)?(?:open|show)\\s+(?:my\\s+)?memory$").containsMatchIn(l)) {
            return CommandResult.Navigate("memory", "Opening local Memory.")
        }
        if (Regex("^(?:please\\s+)?(?:open|show)\\s+(?:command\\s+history|history)$").containsMatchIn(l)) {
            return CommandResult.Navigate("history", "Opening local History.")
        }

        // Battery status & charging checks (handles "Battery Status", "check battery", "is phone charging", etc.)
        val isBatteryStatusQuery = l == "battery" ||
            l == "battery status" ||
            Regex("\\bbattery\\s+(?:status|level|percent(?:age)?|health|temp(?:erature)?|info|check|report)\\b").containsMatchIn(l) ||
            Regex("^(?:check|show|get|what(?:'s| is)?|how much|tell me)\\s+.*\\bbattery\\b").containsMatchIn(l) ||
            Regex("\\b(?:is\\s+(?:the\\s+|my\\s+)?(?:phone|battery|device)\\s+charging|charging\\s+status)\\b").containsMatchIn(l)
        if (isBatteryStatusQuery) {
            return CommandResult.Handled(buildBatteryReply())
        }

        // Device model & OS version
        if (Regex("\\b(device\\s+model|phone\\s+model|what\\s+phone\\s+is\\s+this|android\\s+version)\\b").containsMatchIn(l)) {
            val snap = deviceMonitor?.refreshNow()
            val model = snap?.deviceModel ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            val os = snap?.androidVersion ?: "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            return CommandResult.Handled("This device is $model running $os.")
        }

        // Bare time/date shortcuts
        if (l == "time" || l == "date" || l == "date and time") {
            val mapped = if (l == "date") "what is today's date" else "what time is it"
            when (val tRes = WorldTimeEngine.resolveQuery(mapped, store.use24HourClock)) {
                is TimeQueryResult.Handled -> return CommandResult.Handled(tRes.reply)
                TimeQueryResult.NotATimeQuery -> {}
            }
        }

        return CommandResult.NotACommand
    }

    /**
     * Handles queries when the device has no internet connectivity so VIA stays genuinely useful
     * instead of failing after a Gemini timeout.
     */
    fun processOfflineFallback(raw: String, lang: String): CommandResult {
        val text = raw.trim()
        if (text.isEmpty()) return CommandResult.NotACommand
        val l = text.lowercase(Locale.ROOT)
            .replace(Regex("[?!.]+$"), "")
            .trim()

        // 1. Re-run direct device queries first
        when (val direct = processDirectDeviceQuery(text)) {
            is CommandResult.Handled, is CommandResult.Navigate -> return direct
            CommandResult.NotACommand -> {}
        }

        // 2. Broad offline-only keywords when AI isn't reachable anyway
        if (l.contains("camera") || l.contains("photo") || l.contains("picture") || l.contains("selfie")) {
            return CommandResult.Navigate("camera", "Opening VIA camera (offline mode).")
        }
        if (l.contains("battery") || l.contains("charge") || l.contains("charging") || l.contains("power")) {
            return CommandResult.Handled(buildBatteryReply())
        }
        if (l.contains("wifi") || l.contains("wi-fi") || l.contains("network") || l.contains("internet") || l.contains("connection")) {
            val snap = deviceMonitor?.refreshNow()
            val msg = if (snap != null) {
                "You're offline. Network: ${snap.networkLabel}. Wi-Fi: ${snap.wifiStatus}."
            } else {
                "Your device is currently offline."
            }
            return CommandResult.Handled(msg)
        }
        if (l.contains("bluetooth")) {
            val snap = deviceMonitor?.refreshNow()
            return CommandResult.Handled(
                if (snap != null) "Bluetooth is ${snap.bluetoothStatus}."
                else "Bluetooth status is available in Device Controls."
            )
        }
        if (l.contains("status") || l.contains("device info") || l.contains("phone info")) {
            return CommandResult.Handled(
                deviceMonitor?.buildSummarySpeech() ?: buildBatteryReply()
            )
        }
        if (l.contains("time") || l.contains("clock") || l.contains("date") || l.contains("today")) {
            val query = if (l.contains("date") && !l.contains("time")) "what is today's date" else "what time is it"
            when (val res = WorldTimeEngine.resolveQuery(query, store.use24HourClock)) {
                is TimeQueryResult.Handled -> return CommandResult.Handled(res.reply)
                TimeQueryResult.NotATimeQuery -> {}
            }
        }
        if (l.contains("device controls") || l.contains("controls")) {
            return CommandResult.Navigate("controls", "Opening VIA Device Controls.")
        }
        if (l.contains("call") && callManager != null) {
            val call = callManager.state.value
            if (call.isRinging) {
                return CommandResult.Handled(
                    "Incoming call (${call.callerDisplay}). Say \"answer the call\" or \"decline the call\", or tap Accept/Decline on screen."
                )
            }
        }

        // 3. Offline greetings & local assistant identity / help
        if (Regex("^(?:hi|hello|hey|good morning|good afternoon|good evening|namaste|namaskara)\\b").containsMatchIn(l)) {
            val reply = when (lang) {
                "kn" -> "ನಮಸ್ಕಾರ! ನಾನು ಈಗ ಆಫ್‌ಲೈನ್ ಮೋಡ್‌ನಲ್ಲಿದ್ದೇನೆ. ಕ್ಯಾಮೆರಾ, ಬ್ಯಾಟರಿ ಸ್ಥಿತಿ, ಸಮಯ, ಫ್ಲ್ಯಾಶ್‌ಲೈಟ್ ಮತ್ತು ಫೋನ್ ನಿಯಂತ್ರಣಗಳು ಇನ್ನೂ ಕೆಲಸ ಮಾಡುತ್ತವೆ."
                "hi" -> "नमस्ते! मैं अभी ऑफ़लाइन मोड में हूँ। कैमरा, बैटरी स्टेटस, समय, फ़्लैशलाइट और फ़ोन कंट्रोल अभी भी काम कर रहे हैं।"
                else -> "Hello! I'm currently in offline mode, so local device commands like \"Open Camera\", \"Battery Status\", \"Time\", and \"Flashlight\" are ready to help."
            }
            return CommandResult.Handled(reply)
        }

        if (Regex("\\b(thank you|thanks|thx)\\b").containsMatchIn(l)) {
            return CommandResult.Handled("You're welcome! Let me know if you need any device command.")
        }

        if (Regex("\\b(help|what can you do|offline|commands|features|who are you)\\b").containsMatchIn(l)) {
            return CommandResult.Handled(
                "I'm VIA running in offline mode. Without internet I can handle system tasks locally: " +
                    "\"Open Camera\", \"Battery Status\", \"Phone Status\", \"Wi-Fi / Bluetooth status\", " +
                    "\"Flashlight on/off\", \"Volume up/down\", \"What time is it?\", \"Set an alarm\", " +
                    "\"Add task / Save note\", and your Custom Voice Commands."
            )
        }

        // 4. General AI query while offline: fail fast with a helpful offline message
        val offlineMsg = when (lang) {
            "kn" -> "ಇಂಟರ್ನೆಟ್ ಸಂಪರ್ಕವಿಲ್ಲದ ಕಾರಣ Gemini AI ಲಭ್ಯವಿಲ್ಲ. ನೀವು \"Open Camera\", \"Battery Status\", \"Time\", ಅಥವಾ \"Flashlight\" ನಂತಹ ಆಫ್‌ಲೈನ್ ಆಜ್ಞೆಗಳನ್ನು ಬಳಸಬಹುದು."
            "hi" -> "इंटरनेट कनेक्शन नहीं होने के कारण Gemini AI उपलब्ध नहीं है। आप \"Open Camera\", \"Battery Status\", \"Time\", या \"Flashlight\" जैसे ऑफ़लाइन कमांड चला सकते हैं।"
            else -> "You're currently offline, so Gemini AI isn't available right now. " +
                "VIA can still handle core offline tasks—try \"Open Camera\", \"Battery Status\", " +
                "\"Phone Status\", \"Flashlight On\", \"What time is it?\", or \"Show my tasks\"."
        }
        return CommandResult.Handled(offlineMsg)
    }

    private fun buildBatteryReply(): String {
        val snap = deviceMonitor?.refreshNow()
        if (snap != null) {
            val temp = snap.batteryTempC?.let { String.format(Locale.US, ", temperature %.1f°C", it) } ?: ""
            return "Battery is at ${snap.batteryPercent} percent (${snap.batteryStatusLabel}, ${snap.chargingSource}$temp, health ${snap.batteryHealth})."
        }
        return "Battery status is available in Device Controls."
    }
}
