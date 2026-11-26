package com.vipstudios.via.commands

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.vipstudios.via.data.CustomActionType
import com.vipstudios.via.data.CustomVoiceCommand
import com.vipstudios.via.data.Store
import com.vipstudios.via.device.CallControlManager
import com.vipstudios.via.device.DeviceStatusMonitor
import com.vipstudios.via.time.TimeQueryResult
import com.vipstudios.via.time.WorldTimeEngine
import java.util.Locale

sealed class CommandResult {
    data class Handled(val reply: String) : CommandResult()
    data class Navigate(val route: String, val reply: String) : CommandResult()
    object NotACommand : CommandResult()
}

/**
 * Understands phone commands (English, with basic Hindi and Kannada keywords) and runs them
 * through official Android APIs and intents only.
 *
 * Safety: messages and calls are only PREPARED unless the user grants explicit Android call
 * permissions and invokes Accept/Decline. VIA never calls or records on its own.
 */
class CommandEngine(
    private val ctx: Context,
    private val store: Store,
    private val deviceMonitor: DeviceStatusMonitor? = null,
    private val callManager: CallControlManager? = null
) {
    private val offlineProcessor = OfflineCommandProcessor(ctx, store, deviceMonitor, callManager)
    private var torchOn = false

    private fun h(s: String) = CommandResult.Handled(s)

    fun execute(raw: String, lang: String): CommandResult {
        val text = raw.trim()
        if (text.isEmpty()) return CommandResult.NotACommand
        val l = text.lowercase(Locale.ROOT)

        // ----- user-defined custom voice command mappings -----
        matchCustomCommand(l)?.let { custom ->
            return executeCustomAction(custom)
        }

        // ----- memory, notes, tasks -----
        Regex("^(?:please )?remember (?:that )?(.+)$").find(l)?.let {
            val body = text.takeLast(it.groupValues[1].length)
            store.addMemory(body, "memory")
            return h("Okay, I will remember: $body")
        }
        Regex("^(?:please )?(?:create |take |add |make |save )?(?:a |new )?note(?: that| to)?[:,]?\\s+(.+)$").find(l)?.let {
            val body = text.takeLast(it.groupValues[1].length)
            store.addMemory(body, "note")
            return h("Note saved: $body")
        }
        Regex("^(?:please )?add (?:a )?task[:,]?\\s+(.+)$").find(l)?.let {
            val body = text.takeLast(it.groupValues[1].length)
            store.addMemory(body, "task")
            return h("Task added: $body")
        }
        Regex("^(?:please )?add (.+) to my (?:task|todo|to-do) list$").find(l)?.let {
            val body = text.substring(it.groups[1]!!.range)
            store.addMemory(body, "task")
            return h("Task added: $body")
        }
        if (Regex("(show|list|what are) (my )?(tasks|to-?do)|my task list|my tasks").containsMatchIn(l)) {
            val tasks = store.memory().filter { it.kind == "task" }
            return h(
                if (tasks.isEmpty()) "Your task list is empty."
                else "Your tasks: " + tasks.joinToString("; ") { it.text } + ". You can delete finished tasks in the Memory screen."
            )
        }
        if (Regex("what do you (remember|know about me)|show (my )?memor").containsMatchIn(l)) {
            val items = store.memory().filter { it.kind == "memory" }.takeLast(5)
            return h(
                if (items.isEmpty()) "I don't have any saved memories yet. Say \"remember that...\" to add one."
                else "I remember: " + items.joinToString("; ") { it.text }
            )
        }

        // ----- direct offline-capable VIA device & status commands -----
        when (val direct = offlineProcessor.processDirectDeviceQuery(text)) {
            is CommandResult.Handled, is CommandResult.Navigate -> return direct
            CommandResult.NotACommand -> {}
        }

        // ----- incoming call safe actions -----
        if (Regex("\\b(answer|accept|pick up)\\s+(?:the\\s+)?call\\b").containsMatchIn(l)) {
            val mgr = callManager ?: return h("Call controls aren't active right now. Open Device Controls to enable call permissions.")
            val res = mgr.answerIncomingCall()
            if (!res.succeeded && res.offerSystemAction) {
                mgr.openSupportedPhoneFallback()
            }
            return h(res.message)
        }
        if (Regex("\\b(decline|reject|end)\\s+(?:the\\s+)?call\\b").containsMatchIn(l)) {
            val mgr = callManager ?: return h("Call controls aren't active right now. Open Device Controls to enable call permissions.")
            return h(mgr.declineIncomingCall().message)
        }

        // ----- open apps / settings -----
        val openTarget = Regex("^(?:please )?(?:open|launch|start)\\s+(.+)$").find(l)?.groupValues?.get(1)
            ?: Regex("^(.+?)\\s+(?:खोलो|खोलिए|खोलें)$").find(l)?.groupValues?.get(1)
            ?: Regex("^(.+?)\\s*(?:ತೆರೆಯಿರಿ|ತೆರೆ|ಓಪನ್ ಮಾಡಿ)$").find(l)?.groupValues?.get(1)
        if (openTarget != null) return h(openTarget(openTarget.trim()))

        // ----- device -----
        if (Regex("\\b(phone status|device status|device info|system status)\\b").containsMatchIn(l)) {
            return h(deviceMonitor?.buildSummarySpeech() ?: "${battery()} ${network()}")
        }
        if (Regex("\\bbluetooth\\b").containsMatchIn(l)) {
            val bt = deviceMonitor?.refreshNow()?.bluetoothStatus
            return h(if (bt != null) "Bluetooth is $bt." else settings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings"))
        }
        if (Regex("flashlight|torch|फ्लैशलाइट|टॉर्च|ಟಾರ್ಚ್|ಫ್ಲ್ಯಾಶ್").containsMatchIn(l)) return h(torch(l))
        if (Regex("\\bbattery\\b|बैटरी|ಬ್ಯಾಟರಿ").containsMatchIn(l)) return h(battery())
        if (Regex("\\balarm\\b|remind me|अलार्म|ಅಲಾರಂ").containsMatchIn(l)) return h(alarm(l))
        if (Regex("\\bvolume\\b|\\bmute\\b|ವಾಲ್ಯೂಮ್|वॉल्यूम|आवाज़").containsMatchIn(l)) return h(volume(l))
        when (val timeRes = WorldTimeEngine.resolveQuery(text, store.use24HourClock)) {
            is TimeQueryResult.Handled -> return h(timeRes.reply)
            TimeQueryResult.NotATimeQuery -> {}
        }
        if (Regex("internet|network|wi-?fi|connectivity|इंटरनेट|ಇಂಟರ್ನೆಟ್").containsMatchIn(l)) return h(network())

        // ----- communication -----
        if (l.contains("whatsapp") || l.contains("व्हाट्सएप") || l.contains("व्हाट्सऐप")) return h(whatsapp(text))
        Regex("^(?:please )?(?:call|dial|phone)\\s+(.+)$").find(l)?.let { return h(call(it.groupValues[1])) }
        Regex("^(?:please )?share\\s+(.+)$").find(l)?.let {
            val body = text.takeLast(it.groupValues[1].length)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            }
            return h(if (start(Intent.createChooser(send, "Share with")))
                "Opening the share menu. Pick where to send it." else "I couldn't open the share menu.")
        }

        // ----- navigation -----
        Regex("(?:navigate|directions?|take me|drive)(?: me)? to\\s+(.+)$").find(l)?.let {
            val place = text.takeLast(it.groupValues[1].length)
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            return h(if (start(i)) "Starting navigation to $place in your maps app. Check the route before you drive."
            else "I couldn't find a maps app that can navigate.")
        }
        val mapQuery = Regex("^(?:please )?(?:find|search for|search|show me|show|locate)\\s+(.+?)\\s+(?:on|in|using)\\s+(?:google\\s+)?maps?$").find(l)?.groupValues?.get(1)
            ?: Regex("^(?:please )?(?:find|show me)\\s+(.+?\\s+(?:near me|nearby))$").find(l)?.groupValues?.get(1)
        if (mapQuery != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(mapQuery)))
            return h(if (start(i)) "Searching maps for $mapQuery." else "I couldn't open a maps app.")
        }

        // ----- web search (opens the browser; in-app sourced answers come with the backend) -----
        Regex("^(?:please )?(?:google|search(?: the web| online)?(?: for)?)\\s+(.+)$").find(l)?.let {
            val q = text.takeLast(it.groupValues[1].length)
            val i = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, q) }
            val ok = start(i) || start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            return h(if (ok) "Searching the web for $q in your browser." else "I couldn't open a browser.")
        }

        return CommandResult.NotACommand
    }

    /**
     * Fallback processor invoked when network connectivity is unavailable so core system tasks
     * and helpful local responses remain available without Gemini AI.
     */
    fun executeOfflineFallback(raw: String, lang: String): CommandResult {
        when (val primary = execute(raw, lang)) {
            is CommandResult.Handled, is CommandResult.Navigate -> return primary
            CommandResult.NotACommand -> {}
        }
        return offlineProcessor.processOfflineFallback(raw, lang)
    }

    // ================= helpers =================

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun launchPackage(pkg: String, name: String): String {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "$name doesn't seem to be installed on this phone."
        return if (start(i)) "Opening $name." else "I couldn't open $name."
    }

    private fun openTarget(t: String): String {
        fun any(vararg k: String) = k.any { t.contains(it) }
        return when {
            any("youtube", "यूट्यूब", "ಯೂಟ್ಯೂಬ್") -> launchPackage("com.google.android.youtube", "YouTube")
            any("instagram", "इंस्टाग्राम", "ಇನ್ಸ್ಟಾಗ್ರಾಂ") -> launchPackage("com.instagram.android", "Instagram")
            any("whatsapp", "व्हाट्सएप", "व्हाट्सऐप", "ವಾಟ್ಸಾಪ್") -> launchPackage("com.whatsapp", "WhatsApp")
            any("chrome", "क्रोम", "ಕ್ರೋಮ್") -> launchPackage("com.android.chrome", "Chrome")
            any("maps", "मैप्स", "ನಕ್ಷೆ") -> launchPackage("com.google.android.apps.maps", "Google Maps")
            any("wifi", "wi-fi") -> settings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            any("bluetooth") -> settings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            any("display", "brightness") -> settings(Settings.ACTION_DISPLAY_SETTINGS, "Display settings")
            any("sound") -> settings(Settings.ACTION_SOUND_SETTINGS, "Sound settings")
            any("gallery", "photos") -> {
                val galleryIntent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                val altIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
                if (start(altIntent) || start(galleryIntent)) "Opening Gallery." else "I couldn't find a Gallery app on this phone."
            }
            any("phone", "dialer") -> if (start(Intent(Intent.ACTION_DIAL))) "Opening Phone dialer." else "I couldn't open the Phone dialer."
            any("settings", "सेटिंग", "ಸೆಟ್ಟಿಂಗ್") -> settings(Settings.ACTION_SETTINGS, "Settings")
            else -> openInstalled(t)
        }
    }

    private fun settings(action: String, name: String) =
        if (start(Intent(action))) "Opening $name." else "I couldn't open $name."

    private fun openInstalled(name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == name }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(name) }
            ?: return "I couldn't find an app called \"$name\" on this phone."
        val label = match.loadLabel(pm).toString()
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I couldn't open $label."
        return if (start(i)) "Opening $label." else "I couldn't open $label."
    }

    private fun torch(l: String): String {
        val off = Regex("\\boff\\b|बंद|ऑफ|ಆಫ್").containsMatchIn(l)
        val on = Regex("\\bon\\b|चालू|ऑन|ಆನ್").containsMatchIn(l)
        val desired: Boolean? = when {
            off -> false
            on -> true
            else -> null
        }
        if (deviceMonitor != null) {
            return deviceMonitor.toggleFlashlight(desired)
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        } ?: return "This phone doesn't report a flashlight I can control."
        val target = desired ?: !torchOn
        return try {
            cm.setTorchMode(id, target)
            torchOn = target
            if (target) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't change the flashlight: ${e.message}"
        }
    }

    private fun battery(): String {
        if (deviceMonitor != null) {
            val s = deviceMonitor.refreshNow()
            val temp = s.batteryTempC?.let { String.format(Locale.US, ", temperature %.1f°C", it) } ?: ""
            return "Battery is at ${s.batteryPercent} percent (${s.batteryStatusLabel}, ${s.chargingSource}$temp, health ${s.batteryHealth})."
        }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return "Battery is at $pct percent" + if (charging) " and charging." else "."
    }

    private fun network(): String {
        if (deviceMonitor != null) {
            val s = deviceMonitor.refreshNow()
            return "Network is ${s.networkLabel}. Wi-Fi is ${s.wifiStatus}."
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return "You're offline. No network connection."
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "a network"
        }
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (ok) "You're online through $type." else "You're connected to $type, but Android reports no working internet."
    }

    private fun volume(l: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when {
            Regex("\\bmute\\b").containsMatchIn(l) -> AudioManager.ADJUST_MUTE
            Regex("\\bup\\b|increase|raise|louder|higher|बढ़ा|ಹೆಚ್ಚಿಸಿ").containsMatchIn(l) -> AudioManager.ADJUST_RAISE
            Regex("\\bdown\\b|decrease|lower|reduce|quieter|कम|ಕಡಿಮೆ").containsMatchIn(l) -> AudioManager.ADJUST_LOWER
            else -> null
        }
        if (dir == null) {
            val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            return "Media volume is at $pct percent. Say volume up or volume down to change it."
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return when (dir) {
            AudioManager.ADJUST_MUTE -> "Muted."
            AudioManager.ADJUST_RAISE -> "Volume up."
            else -> "Volume down."
        }
    }

    private fun parseTime(l: String): Pair<Int, Int>? {
        Regex("(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?m\\.?").find(l)?.let { m ->
            var hr = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hr !in 1..12 || min !in 0..59) return null
            val pm = m.groupValues[3] == "p"
            if (pm && hr < 12) hr += 12
            if (!pm && hr == 12) hr = 0
            return hr to min
        }
        Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(l)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        Regex("\\b(?:at|for)\\s+(\\d{1,2})\\b").find(l)?.let {
            val hr = it.groupValues[1].toInt()
            if (hr in 0..23) return hr to 0
        }
        return null
    }

    private fun alarm(l: String): String {
        val t = parseTime(l) ?: return "What time? For example: \"set an alarm for 6:30 am\"."
        val label = Regex("remind me (?:to )?(.+?)(?:\\s+(?:at|for|by)\\s+.*)?$").find(l)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() } ?: "VIA alarm"
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, t.first)
            putExtra(AlarmClock.EXTRA_MINUTES, t.second)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val tm = String.format(Locale.ROOT, "%02d:%02d", t.first, t.second)
        return if (start(i)) "I asked your Clock app to set \"$label\" for $tm. Please check the Clock app to confirm it."
        else "I couldn't find a Clock app that can set alarms."
    }

    private fun whatsapp(text: String): String {
        val phone = Regex("(\\+?\\d[\\d\\s-]{8,14}\\d)").find(text)?.value?.filter { it.isDigit() }
        val body = Regex("(?:saying|say|that|:)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: ""
        val uri = if (phone != null) "https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(body)}"
        else "https://api.whatsapp.com/send?text=${Uri.encode(body)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.whatsapp")
        if (!start(i)) return "WhatsApp doesn't seem to be installed."
        return if (phone != null) "WhatsApp is open with your message ready. Check it and tap Send yourself. I never send for you."
        else "WhatsApp is open. Choose a contact and tap Send yourself. I never send for you. (Contact names aren't supported yet. Say a number with country code to open a chat directly.)"
    }

    private fun matchCustomCommand(normalizedInput: String): CustomVoiceCommand? {
        val cleanInput = normalizedInput.trim().trimEnd('.', '!', '?', ',', ';')
        if (cleanInput.isEmpty()) return null
        val customList = store.customCommands()
        if (customList.isEmpty()) return null

        // Exact phrase match first, then longest word-boundary phrase match
        val exact = customList.firstOrNull {
            it.phrase.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?') == cleanInput
        }
        if (exact != null) return exact

        return customList
            .sortedByDescending { it.phrase.length }
            .firstOrNull { cmd ->
                val p = cmd.phrase.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
                p.isNotBlank() && Regex("\\b${Regex.escape(p)}\\b").containsMatchIn(cleanInput)
            }
    }

    private fun executeCustomAction(cmd: CustomVoiceCommand): CommandResult {
        return when (CustomActionType.fromId(cmd.actionId)) {
            CustomActionType.OPEN_GALLERY -> {
                val galleryIntent = Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                val altIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
                h(
                    if (start(altIntent) || start(galleryIntent)) "Opening your Gallery."
                    else "I couldn't find a Gallery app on this device."
                )
            }
            CustomActionType.SEND_MESSAGE -> {
                val arg = cmd.actionArg.trim()
                val isPhone = arg.isNotBlank() && arg.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
                val smsUri = if (isPhone) Uri.parse("smsto:${arg.filter { it.isDigit() || it == '+' }}") else Uri.parse("smsto:")
                val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    if (!isPhone && arg.isNotBlank()) putExtra("sms_body", arg)
                }
                h(
                    if (start(smsIntent)) "Messages is open so you can review and tap Send yourself. VIA never sends messages automatically."
                    else "I couldn't open a messaging app on this device."
                )
            }
            CustomActionType.OPEN_CAMERA -> CommandResult.Navigate("camera", "Opening VIA camera.")
            CustomActionType.OPEN_DEVICE_CONTROLS -> CommandResult.Navigate("controls", "Opening VIA Device Controls.")
            CustomActionType.OPEN_WORLD_CLOCK -> CommandResult.Navigate("clock", "Opening real-time world clock.")
            CustomActionType.TOGGLE_FLASHLIGHT -> h(torch("toggle"))
            CustomActionType.CHECK_BATTERY -> h(battery())
            CustomActionType.CHECK_PHONE_STATUS -> h(deviceMonitor?.buildSummarySpeech() ?: "${battery()} ${network()}")
            CustomActionType.OPEN_SETTINGS -> h(settings(Settings.ACTION_SETTINGS, "Settings"))
            CustomActionType.OPEN_WIFI_SETTINGS -> h(settings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings"))
            CustomActionType.OPEN_BLUETOOTH_SETTINGS -> h(settings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings"))
            CustomActionType.OPEN_APP_BY_NAME -> {
                val appName = cmd.actionArg.trim().lowercase(Locale.ROOT)
                h(
                    if (appName.isBlank()) "Please specify an app name for \"${cmd.phrase}\" in Custom Voice Commands."
                    else openInstalled(appName)
                )
            }
        }
    }

    private fun call(target: String): String {
        val num = Regex("\\+?\\d[\\d\\s-]{3,}\\d").find(target)?.value?.filter { it.isDigit() || it == '+' }
            ?: return "I can't look up contact names yet because contact access isn't built in. Say a phone number instead."
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
        return if (start(i)) "Your dialer is open with $num. Tap the call button yourself to place the call."
        else "I couldn't open the dialer."
    }
}
