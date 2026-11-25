package com.vipstudios.via.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vipstudios.via.ai.CloudClient
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.ChatMessage
import com.vipstudios.via.data.CommandLog
import com.vipstudios.via.data.MemoryItem
import com.vipstudios.via.data.Store
import com.vipstudios.via.service.WakeWordService
import com.vipstudios.via.voice.VoiceController
import kotlinx.coroutines.launch

const val WELCOME = "Hello! I am VIA, your personal AI assistant. How can I help you today?"

class ViaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val store = Store(app)
    private val engine = CommandEngine(app, store)
    val voice = VoiceController(app)

    var messages by mutableStateOf(store.messages())
    var memory by mutableStateOf(store.memory())
    var commandLog by mutableStateOf(store.commandLog())

    var language by mutableStateOf(store.language)
    var themeMode by mutableStateOf(store.themeMode)
    var personality by mutableStateOf(store.personality)
    var backendUrl by mutableStateOf(store.backendUrl)
    var apiToken by mutableStateOf(store.apiToken)
    var adsEnabled by mutableStateOf(store.adsEnabled)
    var continuous by mutableStateOf(store.continuous)

    var thinking by mutableStateOf(false)
    var lastReply by mutableStateOf("")
    var connectionNote by mutableStateOf("")
    var wakeServiceOn by mutableStateOf(WakeWordService.running)
    var voiceScreenActive = false

    private var lastReplyLang = "en"

    init {
        if (messages.isEmpty()) {
            messages = listOf(store.newMessage(false, WELCOME))
            store.saveMessages(messages)
        }
    }

    // ---------- chat ----------
    private fun addMessage(fromUser: Boolean, text: String) {
        messages = messages + store.newMessage(fromUser, text)
        store.saveMessages(messages)
    }

    fun send(text: String, speak: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        addMessage(true, t)
        thinking = true
        viewModelScope.launch {
            val reply = try {
                process(t)
            } catch (e: Exception) {
                lastReplyLang = "en"
                "Sorry, something went wrong: ${e.message ?: "unknown error"}"
            }
            addMessage(false, reply)
            lastReply = reply
            thinking = false
            if (speak) speakReply(reply)
        }
    }

    private suspend fun process(t: String): String {
        when (val r = engine.execute(t, language)) {
            is CommandResult.Handled -> {
                lastReplyLang = "en" // built-in command replies are English in Stage 1
                store.addCommandLog(t, r.reply)
                refresh()
                return r.reply
            }
            CommandResult.NotACommand -> {}
        }
        lastReplyLang = language
        if (backendUrl.isBlank()) {
            lastReplyLang = "en"
            return "My cloud brain isn't connected yet, so I can only do phone commands for now " +
                "(open apps, battery, time, alarms, notes, calls, maps and more). " +
                "The AI backend is built in Stage 2. See the Commands screen for what works."
        }
        return CloudClient.chat(backendUrl, apiToken, t, language, personality, messages.dropLast(1))
            .getOrElse {
                lastReplyLang = "en"
                "I couldn't reach my cloud brain (${it.message}). Check your connection and the backend address in Settings."
            }
    }

    /** Runs a command shortcut without opening the chat. */
    fun runQuick(command: String) = send(command, speak = false)

    private fun refresh() {
        memory = store.memory()
        commandLog = store.commandLog()
    }

    // ---------- voice ----------
    fun startListening() {
        voice.listen(language) { text -> send(text, speak = true) }
    }

    fun speakReply(text: String) {
        if (text.isBlank()) return
        voice.speak(text, lastReplyLang) {
            if (continuous && voiceScreenActive) {
                viewModelScope.launch { startListening() }
            }
        }
    }

    fun repeatLast() = speakReply(lastReply)

    // ---------- memory ----------
    fun addMemory(text: String, kind: String = "memory") {
        if (text.isBlank()) return
        store.addMemory(text, kind); refresh()
    }

    fun updateMemory(id: Long, text: String) {
        if (text.isBlank()) return
        store.updateMemory(id, text); refresh()
    }

    fun deleteMemory(id: Long) {
        store.deleteMemory(id); refresh()
    }

    // ---------- data controls ----------
    fun clearChat() {
        store.clearMessages()
        messages = listOf(store.newMessage(false, WELCOME))
        store.saveMessages(messages)
    }

    fun clearCommands() {
        store.clearCommandLog(); refresh()
    }

    fun clearEverything() {
        store.clearMessages(); store.clearMemory(); store.clearCommandLog()
        messages = listOf(store.newMessage(false, WELCOME))
        store.saveMessages(messages)
        refresh()
    }

    // ---------- settings ----------
    fun setLanguage(v: String) { language = v; store.language = v }
    fun setTheme(v: String) { themeMode = v; store.themeMode = v }
    fun setPersonality(v: String) { personality = v; store.personality = v }
    fun setBackend(url: String, token: String) {
        backendUrl = url.trim(); apiToken = token.trim()
        store.backendUrl = backendUrl; store.apiToken = apiToken
    }
    fun setAds(v: Boolean) { adsEnabled = v; store.adsEnabled = v }
    fun setContinuous(v: Boolean) { continuous = v; store.continuous = v }

    fun testConnection() {
        viewModelScope.launch {
            connectionNote = "Testing..."
            connectionNote = CloudClient.health(backendUrl).fold(
                onSuccess = { "Connected." },
                onFailure = { "Failed: ${it.message}" }
            )
        }
    }

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun refreshServiceState() { wakeServiceOn = WakeWordService.running }

    override fun onCleared() {
        voice.shutdown()
        super.onCleared()
    }
}
