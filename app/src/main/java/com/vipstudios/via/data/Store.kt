package com.vipstudios.via.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple on-device storage (SharedPreferences + JSON).
 * Everything stays on the phone. Stage 2 will add Keystore-encrypted storage for the API token.
 */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("via_store", Context.MODE_PRIVATE)
    private var seq = 0L

    private fun newId(): Long = System.currentTimeMillis() * 10 + (seq++ % 10)

    // ---------- settings ----------
    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

    var themeMode: String
        get() = sp.getString("theme", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme", v).apply()

    var personality: String
        get() = sp.getString("personality", "Friendly, clear and concise.") ?: ""
        set(v) = sp.edit().putString("personality", v).apply()

    var backendUrl: String
        get() = sp.getString("backend_url", "") ?: ""
        set(v) = sp.edit().putString("backend_url", v.trim()).apply()

    var apiToken: String
        get() = sp.getString("api_token", "") ?: ""
        set(v) = sp.edit().putString("api_token", v.trim()).apply()

    var adsEnabled: Boolean
        get() = sp.getBoolean("ads", true)
        set(v) = sp.edit().putBoolean("ads", v).apply()

    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    var voiceResponseEnabled: Boolean
        get() = sp.getBoolean("voice_response", true)
        set(v) = sp.edit().putBoolean("voice_response", v).apply()

    var backgroundAssistantEnabled: Boolean
        get() = sp.getBoolean("bg_assistant_enabled", false)
        set(v) = sp.edit().putBoolean("bg_assistant_enabled", v).apply()

    var localWakeWordEnabled: Boolean
        get() = sp.getBoolean("local_wake_word_enabled", false)
        set(v) = sp.edit().putBoolean("local_wake_word_enabled", v).apply()

    var sheetsEnabled: Boolean
        get() = sp.getBoolean("sheets_enabled", false)
        set(v) = sp.edit().putBoolean("sheets_enabled", v).apply()

    var use24HourClock: Boolean
        get() = sp.getBoolean("use_24h_clock", false)
        set(v) = sp.edit().putBoolean("use_24h_clock", v).apply()

    var batteryVoiceAlertsEnabled: Boolean
        get() = sp.getBoolean("battery_voice_alerts", true)
        set(v) = sp.edit().putBoolean("battery_voice_alerts", v).apply()

    var callMonitoringEnabled: Boolean
        get() = sp.getBoolean("call_monitoring_enabled", false)
        set(v) = sp.edit().putBoolean("call_monitoring_enabled", v).apply()

    // ---------- chat ----------
    fun messages(): List<ChatMessage> = read("messages") {
        ChatMessage(it.getLong("id"), it.getBoolean("u"), it.getString("t"), it.getLong("ts"))
    }

    fun saveMessages(list: List<ChatMessage>) = write("messages", list.takeLast(500)) {
        JSONObject().put("id", it.id).put("u", it.fromUser).put("t", it.text).put("ts", it.time)
    }

    fun newMessage(fromUser: Boolean, text: String) =
        ChatMessage(newId(), fromUser, text, System.currentTimeMillis())

    // ---------- memory / notes / tasks ----------
    fun memory(): List<MemoryItem> = read("memory") {
        MemoryItem(it.getLong("id"), it.getString("t"), it.getString("k"), it.getLong("ts"))
    }

    private fun saveMemory(list: List<MemoryItem>) = write("memory", list) {
        JSONObject().put("id", it.id).put("t", it.text).put("k", it.kind).put("ts", it.time)
    }

    fun addMemory(text: String, kind: String) {
        saveMemory(memory() + MemoryItem(newId(), text.trim(), kind, System.currentTimeMillis()))
    }

    fun updateMemory(id: Long, text: String) {
        saveMemory(memory().map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    fun deleteMemory(id: Long) {
        saveMemory(memory().filter { it.id != id })
    }

    // ---------- command history ----------
    fun commandLog(): List<CommandLog> = read("commands") {
        CommandLog(it.getLong("id"), it.getString("t"), it.getString("r"), it.getLong("ts"))
    }

    fun addCommandLog(text: String, result: String) {
        val list = (commandLog() + CommandLog(newId(), text, result, System.currentTimeMillis())).takeLast(200)
        write("commands", list) {
            JSONObject().put("id", it.id).put("t", it.text).put("r", it.result).put("ts", it.time)
        }
    }

    fun clearCommandLog() = sp.edit().remove("commands").apply()
    fun clearMessages() = sp.edit().remove("messages").apply()
    fun clearMemory() = sp.edit().remove("memory").apply()

    // ---------- helpers ----------
    private fun <T> read(key: String, f: (JSONObject) -> T): List<T> {
        val s = sp.getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { f(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun <T> write(key: String, list: List<T>, f: (T) -> JSONObject) {
        val a = JSONArray()
        list.forEach { a.put(f(it)) }
        sp.edit().putString(key, a.toString()).apply()
    }
}
