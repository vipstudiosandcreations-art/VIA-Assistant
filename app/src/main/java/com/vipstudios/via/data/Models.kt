package com.vipstudios.via.data

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val time: Long)

/** kind is one of: "memory", "note", "task" */
data class MemoryItem(val id: Long, val text: String, val kind: String, val time: Long)

data class CommandLog(val id: Long, val text: String, val result: String, val time: Long)

data class BatterySample(
    val timestampMs: Long,
    val percent: Int,
    val isCharging: Boolean,
    val tempC: Float? = null
)

enum class CustomActionType(
    val id: String,
    val label: String,
    val description: String,
    val requiresArg: Boolean = false,
    val argHint: String = ""
) {
    OPEN_GALLERY("open_gallery", "Open Gallery", "Opens your phone's Gallery / Photos app"),
    SEND_MESSAGE("send_message", "Send Message", "Opens SMS / Messaging composer (you confirm Send)", true, "Optional text or phone number"),
    OPEN_CAMERA("open_camera", "Open VIA Camera", "Opens VIA's camera preview and capture screen"),
    OPEN_DEVICE_CONTROLS("open_controls", "Open Device Controls", "Opens VIA Device Controls"),
    OPEN_WORLD_CLOCK("open_clock", "Open World Clock", "Opens VIA's real-time World Clock"),
    TOGGLE_FLASHLIGHT("toggle_flashlight", "Toggle Flashlight", "Turns your device flashlight on or off"),
    CHECK_BATTERY("check_battery", "Check Battery Status", "Reads current battery percentage and charging status"),
    CHECK_PHONE_STATUS("phone_status", "Check Phone Status", "Reads battery, network, Wi-Fi, and Bluetooth status"),
    OPEN_SETTINGS("open_settings", "Open Android Settings", "Opens your device's main Settings screen"),
    OPEN_WIFI_SETTINGS("open_wifi", "Open Wi-Fi Settings", "Opens Android Wi-Fi settings"),
    OPEN_BLUETOOTH_SETTINGS("open_bluetooth", "Open Bluetooth Settings", "Opens Android Bluetooth settings"),
    OPEN_APP_BY_NAME("open_app", "Open Installed App", "Launches an installed app by its name", true, "App name (e.g. YouTube, Music)");

    companion object {
        fun fromId(id: String): CustomActionType =
            entries.firstOrNull { it.id == id } ?: OPEN_GALLERY
    }
}

data class CustomVoiceCommand(
    val id: Long,
    val phrase: String,
    val actionId: String,
    val actionArg: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
