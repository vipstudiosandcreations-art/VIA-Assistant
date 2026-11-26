package com.vipstudios.via.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.MainActivity
import com.vipstudios.via.ai.AiStreamEvent
import com.vipstudios.via.ai.CloudClient
import com.vipstudios.via.ai.ViaAiRepository
import com.vipstudios.via.commands.CommandEngine
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.GoogleSheetsRepository
import com.vipstudios.via.data.SheetsAuthOutcome
import com.vipstudios.via.data.SheetsFetchOutcome
import com.vipstudios.via.data.SpreadsheetSnapshot
import com.vipstudios.via.data.Store
import com.vipstudios.via.data.ViaRuntime
import com.vipstudios.via.service.WakeEvent
import com.vipstudios.via.service.WakeWordService
import com.vipstudios.via.voice.VoiceController
import com.vipstudios.via.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "ViaViewModel"

const val WELCOME = "Hello! I am VIA, your VIP Intelligent Assistant. Tap the mic, say \"Hey VIA\", or type a question below."

enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class ViaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val runtime = ViaRuntime.get(app)
    private val store: Store = runtime.store
    private val deviceMonitor = runtime.deviceMonitor
    private val callManager = runtime.callManager
    private val engine = runtime.commandEngine
    private val aiRepo = runtime.aiRepo
    private val sheetsRepo: GoogleSheetsRepository = runtime.sheetsRepo
    val voice = VoiceController(app)

    var messages by mutableStateOf(store.messages())
        private set
    var memory by mutableStateOf(store.memory())
        private set
    var commandLog by mutableStateOf(store.commandLog())
        private set
    var customCommands by mutableStateOf(store.customCommands())
        private set

    var language by mutableStateOf(store.language)
        private set
    var themeMode by mutableStateOf(store.themeMode)
    var personality by mutableStateOf(store.personality)
        private set
    var backendUrl by mutableStateOf(store.backendUrl)
    var apiToken by mutableStateOf(store.apiToken)
    var adsEnabled by mutableStateOf(store.adsEnabled)
    var continuous by mutableStateOf(store.continuous)
        private set
    var voiceResponseEnabled by mutableStateOf(store.voiceResponseEnabled)
        private set
    var voiceId by mutableStateOf(store.voiceId)
        private set
    var neuralVoiceEnabled by mutableStateOf(store.neuralVoiceEnabled)
        private set

    var thinking by mutableStateOf(false)
    var streamingReply by mutableStateOf("")
    var lastReply by mutableStateOf("")
    var aiError by mutableStateOf<String?>(null)
    var connectionNote by mutableStateOf("")
    var wakeServiceOn by mutableStateOf(WakeWordService.running)
    var wakeMicActive by mutableStateOf(WakeWordService.listeningActive.value)
        private set
    var wakeStatusText by mutableStateOf(WakeWordService.statusMessage.value)
        private set
    var localWakeWordEnabled by mutableStateOf(store.localWakeWordEnabled)
        private set
    var voiceScreenActive = false

    // Google Sheets (strictly read-only) state
    var sheetsConnected by mutableStateOf(store.sheetsEnabled)
        private set
    var sheetsLoading by mutableStateOf(false)
        private set
    var sheetsNeedsReauth by mutableStateOf(false)
        private set
    var sheetsTitle by mutableStateOf("")
        private set
    var sheetsRowCount by mutableIntStateOf(0)
        private set
    var sheetsLastSyncText by mutableStateOf("")
        private set
    var sheetsStatusText by mutableStateOf(
        if (store.sheetsEnabled) "Connected (Read-Only) — syncing on next question" else "Not connected"
    )
        private set

    // Diagnostics: separate Test Gemini & Test Voice states
    var geminiTesting by mutableStateOf(false)
        private set
    var geminiTestStatus by mutableStateOf("")
        private set
    var voiceTesting by mutableStateOf(false)
        private set
    var voiceTestStatus by mutableStateOf("")
        private set
    var use24HourClock by mutableStateOf(store.use24HourClock)
        private set

    // Device controls, battery alerts, and incoming call state
    val phoneStatus = deviceMonitor.status
    val batteryHistory = deviceMonitor.batteryHistory
    val callState = callManager.state
    var batteryVoiceAlertsEnabled by mutableStateOf(store.batteryVoiceAlertsEnabled)
        private set
    var latestBatteryAlertText by mutableStateOf("")
        private set
    var pendingNavigationRoute by mutableStateOf<String?>(null)
        private set

    private var lastReplyLang = "en"
    private var lastHandledWakeEventId = -1L
    private var lastVoiceSubmissionText = ""
    private var lastVoiceSubmissionAtMs = 0L

    init {
        if (messages.isEmpty()) {
            messages = listOf(store.newMessage(false, WELCOME))
            store.saveMessages(messages)
        }
        runtime.ensureMonitorsActive()
        viewModelScope.launch {
            runtime.storeUpdates.collect {
                syncFromStore()
            }
        }
        viewModelScope.launch {
            deviceMonitor.alerts.collect { alert ->
                latestBatteryAlertText = alert.message
                if (runtime.isUiForeground && batteryVoiceAlertsEnabled && voiceResponseEnabled && voice.state.value == VoiceState.Idle && !thinking) {
                    speakCustom(alert.message, "en")
                }
            }
        }
        viewModelScope.launch {
            callManager.incomingAnnouncements.collect { announcement ->
                if (runtime.isUiForeground && store.callMonitoringEnabled && voiceResponseEnabled && voice.state.value == VoiceState.Idle && !thinking) {
                    speakCustom(announcement, "en")
                }
            }
        }
        viewModelScope.launch {
            WakeWordService.runningState.collect { wakeServiceOn = it }
        }
        viewModelScope.launch {
            WakeWordService.listeningActive.collect { wakeMicActive = it }
        }
        viewModelScope.launch {
            WakeWordService.statusMessage.collect { wakeStatusText = it }
        }
        viewModelScope.launch {
            WakeWordService.events.collect { event ->
                if (event.id == lastHandledWakeEventId) return@collect
                lastHandledWakeEventId = event.id
                when (event) {
                    is WakeEvent.StartVoice -> startListening()
                    is WakeEvent.WakeCommand -> send(event.text, forceSpeak = true, fromVoice = true)
                }
            }
        }
        viewModelScope.launch {
            voice.state.collect { vs ->
                if (wakeServiceOn) {
                    if (vs == VoiceState.Listening || vs == VoiceState.Speaking) {
                        sendServiceAction(WakeWordService.ACTION_PAUSE_FOR_VOICE)
                    } else if (!thinking) {
                        sendServiceAction(WakeWordService.ACTION_RESUME_AFTER_VOICE)
                    }
                }
            }
        }
        if (store.sheetsEnabled && isOnline()) {
            refreshSheets()
        }
    }

    fun assistantState(voiceState: VoiceState): AssistantState = when {
        voiceState == VoiceState.Listening -> AssistantState.LISTENING
        voiceState == VoiceState.Speaking -> AssistantState.SPEAKING
        thinking -> AssistantState.THINKING
        else -> AssistantState.IDLE
    }

    // ---------- background assistant & wake word ----------
    fun startBackgroundAssistant() {
        store.backgroundAssistantEnabled = true
        val intent = Intent(ctx, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Could not start background assistant service", e)
            } else {
                Log.w(TAG, "Could not start background assistant service: ${e.javaClass.simpleName}")
            }
            wakeStatusText = "Could not start background service on this device."
        }
    }

    fun stopBackgroundAssistant() {
        store.backgroundAssistantEnabled = false
        val intent = Intent(ctx, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP)
        try {
            ctx.startService(intent)
        } catch (_: Exception) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }
        wakeServiceOn = false
        wakeMicActive = false
    }

    fun setLocalWakeWord(enabled: Boolean) {
        localWakeWordEnabled = enabled
        store.localWakeWordEnabled = enabled
        if (wakeServiceOn) {
            sendServiceAction(WakeWordService.ACTION_REFRESH)
        }
    }

    /** Called when MainActivity enters foreground to safely restore user-enabled service after process recreation. */
    fun restoreBackgroundAssistantIfNeeded() {
        if (!store.backgroundAssistantEnabled || WakeWordService.running) return
        val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            startBackgroundAssistant()
        }
    }

    private fun sendServiceAction(action: String) {
        try {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action))
        } catch (_: Exception) {
        }
    }

    fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val eventId = intent.getLongExtra(MainActivity.EXTRA_WAKE_EVENT_ID, -1L)
        if (eventId > 0L) {
            intent.removeExtra(MainActivity.EXTRA_WAKE_EVENT_ID)
            if (eventId == lastHandledWakeEventId) {
                intent.removeExtra(MainActivity.EXTRA_WAKE_COMMAND)
                intent.removeExtra(MainActivity.EXTRA_START_VOICE)
                return
            }
            lastHandledWakeEventId = eventId
        }
        val cmd = intent.getStringExtra(MainActivity.EXTRA_WAKE_COMMAND)
        if (!cmd.isNullOrBlank()) {
            intent.removeExtra(MainActivity.EXTRA_WAKE_COMMAND)
            send(cmd, forceSpeak = true, fromVoice = true)
            return
        }
        if (intent.getBooleanExtra(MainActivity.EXTRA_START_VOICE, false)) {
            intent.removeExtra(MainActivity.EXTRA_START_VOICE)
            val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                startListening()
            } else {
                aiError = "Microphone permission is needed to use voice input."
            }
        }
    }

    // ---------- chat & AI ----------
    private fun addMessage(fromUser: Boolean, text: String) {
        messages = messages + store.newMessage(fromUser, text)
        store.saveMessages(messages)
    }

    private fun stripWakePhrase(input: String): Pair<Boolean, String> {
        val trimmed = input.trim()
        val match = Regex("^(?:hey|hi|ok|okay|hello)[,\\s]+via\\b[,:\\s-]*", RegexOption.IGNORE_CASE).find(trimmed)
        return if (match != null) {
            true to trimmed.removeRange(match.range).trim()
        } else {
            false to trimmed
        }
    }

    fun send(text: String, forceSpeak: Boolean = false, fromVoice: Boolean = false) {
        val raw = text.trim()
        if (raw.isEmpty() || thinking) return

        val now = System.currentTimeMillis()
        if (fromVoice && raw.equals(lastVoiceSubmissionText, ignoreCase = true) && (now - lastVoiceSubmissionAtMs) < 2500L) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Ignored duplicate voice submission")
            }
            return
        }
        if (fromVoice) {
            lastVoiceSubmissionText = raw
            lastVoiceSubmissionAtMs = now
        }

        val (hadWakePhrase, cleaned) = stripWakePhrase(raw)
        addMessage(true, raw)
        aiError = null

        if (hadWakePhrase && cleaned.isEmpty()) {
            val greet = when (language) {
                "kn" -> "ನಮಸ್ಕಾರ! ನಾನು ವಿಯಾ (VIA). ನಾನು ನಿಮಗೆ ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
                "hi" -> "नमस्ते! मैं VIA हूँ। बताइए, मैं आपकी क्या मदद कर सकती हूँ?"
                else -> "Yes, I'm here! How can I assist you?"
            }
            lastReplyLang = language
            addMessage(false, greet)
            lastReply = greet
            if (forceSpeak || voiceResponseEnabled) {
                speakReply(greet, autoListenAfter = fromVoice)
            } else if (fromVoice) {
                startListening()
            }
            return
        }

        val query = cleaned.ifEmpty { raw }
        thinking = true
        streamingReply = ""

        viewModelScope.launch {
            try {
                val reply = process(query, fromVoice = fromVoice)
                if (reply != null) {
                    addMessage(false, reply)
                    lastReply = reply
                    streamingReply = ""
                    thinking = false
                    if (forceSpeak || voiceResponseEnabled) {
                        speakReply(reply)
                    } else if (wakeServiceOn) {
                        sendServiceAction(WakeWordService.ACTION_RESUME_AFTER_VOICE)
                    }
                } else {
                    thinking = false
                    streamingReply = ""
                }
            } catch (e: CancellationException) {
                thinking = false
                streamingReply = ""
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Error handling user message", e)
                } else {
                    Log.e(TAG, "Error handling user message: ${e.javaClass.simpleName}")
                }
                val errMsg = e.localizedMessage ?: "Failed to generate a response."
                aiError = errMsg
                lastReplyLang = "en"
                thinking = false
                streamingReply = ""
                if (wakeServiceOn) {
                    sendServiceAction(WakeWordService.ACTION_RESUME_AFTER_VOICE)
                }
            }
        }
    }

    private suspend fun process(query: String, fromVoice: Boolean = false): String? {
        when (val r = engine.execute(query, language)) {
            is CommandResult.Handled -> {
                lastReplyLang = "en"
                store.addCommandLog(query, r.reply)
                refresh()
                return r.reply
            }
            is CommandResult.Navigate -> {
                lastReplyLang = "en"
                store.addCommandLog(query, r.reply)
                refresh()
                pendingNavigationRoute = r.route
                return r.reply
            }
            CommandResult.NotACommand -> {}
        }

        if (!isOnline()) {
            return when (val offlineRes = engine.executeOfflineFallback(query, language)) {
                is CommandResult.Navigate -> {
                    lastReplyLang = "en"
                    store.addCommandLog(query, offlineRes.reply)
                    refresh()
                    pendingNavigationRoute = offlineRes.route
                    offlineRes.reply
                }
                is CommandResult.Handled -> {
                    lastReplyLang = if (offlineRes.reply.any { it.code > 0x7F }) language else "en"
                    store.addCommandLog(query, offlineRes.reply)
                    refresh()
                    offlineRes.reply
                }
                CommandResult.NotACommand -> null
            }
        }

        lastReplyLang = language

        if (backendUrl.isNotBlank()) {
            return CloudClient.chat(
                baseUrl = backendUrl,
                token = apiToken,
                message = query,
                language = language,
                personality = personality,
                history = messages.dropLast(1)
            ).getOrElse {
                aiError = "Custom backend error: ${it.message}"
                return null
            }
        }

        var sheetsKnowledge: String? = null
        var sheetsStatusNote: String? = null
        if (store.sheetsEnabled) {
            when (val res = sheetsRepo.fetchLatestOrCached(ctx)) {
                is SheetsFetchOutcome.Success -> {
                    applySnapshotState(res.snapshot)
                    sheetsKnowledge = res.snapshot.toKnowledgeText()
                }
                SheetsFetchOutcome.NeedsReauth -> {
                    sheetsNeedsReauth = true
                    sheetsStatusText = "Google Sheets authorization needed. Tap Connect Sheets to re-authorize."
                    sheetsStatusNote = "Google Sheets authorization needs to be refreshed by the user."
                }
                is SheetsFetchOutcome.Error -> {
                    sheetsStatusText = res.userMessage
                    sheetsStatusNote = "Could not load latest Google Sheet data: ${res.userMessage}"
                }
            }
        } else {
            sheetsStatusNote = "Google Sheets is not connected yet."
        }

        var completedText: String? = null
        aiRepo.streamReply(
            prompt = query,
            language = language,
            personality = personality,
            history = messages.dropLast(1),
            memory = memory,
            sheetsKnowledge = sheetsKnowledge,
            sheetsStatusNote = sheetsStatusNote,
            preferFast = fromVoice
        ).collect { event ->
            when (event) {
                is AiStreamEvent.Chunk -> streamingReply = event.accumulatedText
                is AiStreamEvent.Completed -> completedText = event.fullText
            }
        }
        return completedText
    }

    // ---------- Google Sheets (read-only) ----------
    private fun applySnapshotState(snapshot: SpreadsheetSnapshot) {
        sheetsConnected = true
        sheetsNeedsReauth = false
        sheetsTitle = snapshot.title
        sheetsRowCount = snapshot.totalRows
        sheetsLastSyncText = snapshot.formattedSyncTime()
        sheetsStatusText = "Live (${snapshot.tabs.size} tab(s), ${snapshot.totalRows} rows)"
    }

    fun connectSheets(activity: Activity, onNeedsConsent: (IntentSender) -> Unit) {
        if (sheetsLoading) return
        sheetsLoading = true
        sheetsStatusText = "Authorizing Google Sheets (read-only)..."
        viewModelScope.launch {
            when (val outcome = sheetsRepo.beginConnect(activity)) {
                is SheetsAuthOutcome.Authorized -> {
                    store.sheetsEnabled = true
                    applySnapshotState(outcome.snapshot)
                    sheetsLoading = false
                }
                is SheetsAuthOutcome.NeedsConsent -> {
                    sheetsLoading = false
                    onNeedsConsent(outcome.intentSender)
                }
                is SheetsAuthOutcome.Failed -> {
                    sheetsLoading = false
                    sheetsStatusText = outcome.userMessage
                }
            }
        }
    }

    fun handleSheetsConsentResult(activity: Activity, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            sheetsLoading = false
            sheetsStatusText = "Google Sheets sign-in was cancelled."
            return
        }
        sheetsLoading = true
        sheetsStatusText = "Loading spreadsheet data..."
        viewModelScope.launch {
            when (val outcome = sheetsRepo.completeConsentFromIntent(activity, data)) {
                is SheetsAuthOutcome.Authorized -> {
                    store.sheetsEnabled = true
                    applySnapshotState(outcome.snapshot)
                }
                is SheetsAuthOutcome.NeedsConsent -> {
                    sheetsStatusText = "Additional Google consent required. Please tap Connect Sheets again."
                }
                is SheetsAuthOutcome.Failed -> {
                    sheetsStatusText = outcome.userMessage
                }
            }
            sheetsLoading = false
        }
    }

    fun refreshSheets() {
        if (sheetsLoading) return
        sheetsLoading = true
        sheetsStatusText = "Refreshing latest spreadsheet data..."
        viewModelScope.launch {
            when (val res = sheetsRepo.fetchLatest(ctx)) {
                is SheetsFetchOutcome.Success -> applySnapshotState(res.snapshot)
                SheetsFetchOutcome.NeedsReauth -> {
                    sheetsNeedsReauth = true
                    sheetsStatusText = "Authorization needed. Tap Connect Google Sheets."
                }
                is SheetsFetchOutcome.Error -> {
                    sheetsStatusText = res.userMessage
                }
            }
            sheetsLoading = false
        }
    }

    fun disconnectSheets() {
        store.sheetsEnabled = false
        sheetsConnected = false
        sheetsNeedsReauth = false
        sheetsLoading = false
        sheetsTitle = ""
        sheetsRowCount = 0
        sheetsLastSyncText = ""
        sheetsStatusText = "Not connected"
        viewModelScope.launch {
            sheetsRepo.disconnect(ctx)
        }
    }

    fun runQuick(command: String) = send(command, forceSpeak = false)

    private fun refresh() {
        memory = store.memory()
        commandLog = store.commandLog()
        customCommands = store.customCommands()
    }

    // ---------- voice ----------
    fun startListening() {
        aiError = null
        if (wakeServiceOn) {
            sendServiceAction(WakeWordService.ACTION_PAUSE_FOR_VOICE)
        }
        voice.listen(language) { recognized ->
            send(recognized, forceSpeak = voiceResponseEnabled, fromVoice = true)
        }
    }

    fun speakReply(text: String, autoListenAfter: Boolean = false) {
        if (text.isBlank()) return
        voice.speak(text, lastReplyLang) {
            if (autoListenAfter || (continuous && voiceScreenActive)) {
                viewModelScope.launch { startListening() }
            } else if (wakeServiceOn) {
                sendServiceAction(WakeWordService.ACTION_RESUME_AFTER_VOICE)
            }
        }
    }

    fun repeatLast() = speakReply(lastReply)

    fun speakCustom(text: String, lang: String = "en") {
        if (text.isBlank()) return
        voice.speak(text, lang) {
            if (wakeServiceOn) {
                sendServiceAction(WakeWordService.ACTION_RESUME_AFTER_VOICE)
            }
        }
    }

    @JvmName("applyUse24HourClock")
    fun setUse24HourClock(enabled: Boolean) {
        use24HourClock = enabled
        store.use24HourClock = enabled
    }

    fun toggleVoiceResponse() {
        val next = !voiceResponseEnabled
        voiceResponseEnabled = next
        store.voiceResponseEnabled = next
        if (!next) voice.stopSpeaking()
    }

    @JvmName("applyVoiceId")
    fun setVoiceId(id: String) {
        val clean = id.trim().ifEmpty { Store.DEFAULT_VOICE_ID }
        voiceId = clean
        store.voiceId = clean
    }

    fun resetDefaultVoiceId() {
        setVoiceId(Store.DEFAULT_VOICE_ID)
    }

    @JvmName("applyNeuralVoiceEnabled")
    fun setNeuralVoiceEnabled(enabled: Boolean) {
        neuralVoiceEnabled = enabled
        store.neuralVoiceEnabled = enabled
    }

    // ---------- diagnostics ----------
    fun testGemini() {
        if (geminiTesting) return
        if (!isOnline()) {
            geminiTestStatus = "Failed: No active internet connection."
            return
        }
        geminiTesting = true
        geminiTestStatus = "Testing Gemini AI connection..."
        val started = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val reply = aiRepo.testConnection(language)
                val elapsedMs = System.currentTimeMillis() - started
                geminiTestStatus = "Gemini OK (${elapsedMs} ms): $reply"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "testGemini failed", e)
                } else {
                    Log.e(TAG, "testGemini failed: ${e.javaClass.simpleName}")
                }
                geminiTestStatus = "Gemini failed: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                geminiTesting = false
            }
        }
    }

    fun testVoice() {
        if (voiceTesting) return
        voiceTesting = true
        val hasMic = voice.hasMicPermission()
        val sttOk = voice.isSpeechRecognitionAvailable()
        val ttsOk = voice.isTtsReady()
        val langOk = voice.isTtsLanguageAvailable(language)
        val langLabel = voice.label(language)

        val neuralActive = voice.isNeuralVoiceActive()
        val summary = buildString {
            append("Mic permission: ${if (hasMic) "Granted" else "Not granted"} · ")
            append("STT: ${if (sttOk) "Ready" else "Unavailable"} · ")
            append("TTS: ${if (ttsOk) "Ready" else "Starting/Unavailable"} · ")
            append(
                if (neuralActive) "Neural Voice: $voiceId"
                else "Voice profile: $voiceId (using $langLabel system TTS)"
            )
        }
        voiceTestStatus = summary

        val sample = when (language) {
            "kn" -> "ನಮಸ್ಕಾರ, ವಿಯಾ ಧ್ವನಿ ವ್ಯವಸ್ಥೆ ಸಿದ್ಧವಾಗಿದೆ."
            "hi" -> "नमस्ते, VIA की आवाज़ प्रणाली तैयार है।"
            else -> "Hello! VIA voice output and microphone check are ready."
        }
        if (ttsOk) {
            voice.speak(sample, language) {
                voiceTesting = false
            }
        } else {
            voiceTesting = false
        }
    }

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

    // ---------- custom voice command mappings ----------
    fun addCustomCommand(phrase: String, actionId: String, actionArg: String = "") {
        if (phrase.isBlank()) return
        store.addCustomCommand(phrase, actionId, actionArg)
        refresh()
    }

    fun updateCustomCommand(id: Long, phrase: String, actionId: String, actionArg: String = "") {
        if (phrase.isBlank()) return
        store.updateCustomCommand(id, phrase, actionId, actionArg)
        refresh()
    }

    fun deleteCustomCommand(id: Long) {
        store.deleteCustomCommand(id)
        refresh()
    }

    // ---------- data controls ----------
    fun clearChat() {
        store.clearMessages()
        messages = listOf(store.newMessage(false, WELCOME))
        store.saveMessages(messages)
        aiError = null
    }

    fun clearCommands() {
        store.clearCommandLog(); refresh()
    }

    fun clearEverything() {
        store.clearMessages(); store.clearMemory(); store.clearCommandLog()
        messages = listOf(store.newMessage(false, WELCOME))
        store.saveMessages(messages)
        aiError = null
        refresh()
    }

    // ---------- settings ----------
    @JvmName("applyLanguage")
    fun setLanguage(v: String) {
        language = v
        store.language = v
        if (wakeServiceOn && localWakeWordEnabled) {
            sendServiceAction(WakeWordService.ACTION_REFRESH)
        }
    }
    fun setTheme(v: String) { themeMode = v; store.themeMode = v }
    @JvmName("applyPersonality")
    fun setPersonality(v: String) { personality = v; store.personality = v }
    fun setBackend(url: String, token: String) {
        backendUrl = url.trim(); apiToken = token.trim()
        store.backendUrl = backendUrl; store.apiToken = apiToken
    }
    fun setAds(v: Boolean) { adsEnabled = v; store.adsEnabled = v }
    @JvmName("applyContinuous")
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
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun refreshServiceState() {
        wakeServiceOn = WakeWordService.running
        wakeMicActive = WakeWordService.listeningActive.value
        wakeStatusText = WakeWordService.statusMessage.value
    }

    // ---------- lifecycle & device controls & safe phone calls ----------
    fun setUiForeground(foreground: Boolean) {
        runtime.isUiForeground = foreground
        if (foreground) {
            syncFromStore()
            refreshDeviceStatus()
            refreshServiceState()
        }
    }

    private fun syncFromStore() {
        messages = store.messages().ifEmpty { listOf(store.newMessage(false, WELCOME)) }
        refresh()
    }

    fun refreshDeviceStatus() {
        deviceMonitor.refreshNow()
        callManager.refreshPermissions()
    }

    @JvmName("applyBatteryVoiceAlerts")
    fun setBatteryVoiceAlerts(enabled: Boolean) {
        batteryVoiceAlertsEnabled = enabled
        store.batteryVoiceAlertsEnabled = enabled
    }

    fun setCallMonitoring(enabled: Boolean) {
        store.callMonitoringEnabled = enabled
        if (enabled) {
            callManager.startMonitoring()
        } else {
            callManager.stopMonitoring()
        }
    }

    fun toggleFlashlight(): String = deviceMonitor.toggleFlashlight()

    fun answerIncomingCall(): String {
        val res = callManager.answerIncomingCall()
        if (!res.succeeded && res.offerSystemAction) {
            callManager.openSupportedPhoneFallback()
        }
        return res.message
    }

    fun declineIncomingCall(): String {
        val res = callManager.declineIncomingCall()
        return res.message
    }

    fun clearPendingNavigation() {
        pendingNavigationRoute = null
    }

    override fun onCleared() {
        runtime.isUiForeground = false
        if (!WakeWordService.running) {
            deviceMonitor.stop()
            callManager.stopMonitoring()
        }
        voice.shutdown()
        super.onCleared()
    }
}
