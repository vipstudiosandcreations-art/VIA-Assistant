package com.vipstudios.via.service

import android.Manifest
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.MainActivity
import com.vipstudios.via.R
import com.vipstudios.via.ai.AiStreamEvent
import com.vipstudios.via.ai.CloudClient
import com.vipstudios.via.commands.CommandResult
import com.vipstudios.via.data.SheetsFetchOutcome
import com.vipstudios.via.data.Store
import com.vipstudios.via.data.ViaRuntime
import com.vipstudios.via.voice.VoiceController
import com.vipstudios.via.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "WakeWordService"

sealed interface WakeEvent {
    val id: Long
    data class StartVoice(override val id: Long) : WakeEvent
    data class WakeCommand(override val id: Long, val text: String) : WakeEvent
}

interface WakeWordEngine {
    val isSupported: Boolean
    fun start(onWake: (trailingCommand: String?) -> Unit)
    fun stop()
}

/**
 * Local-only wake-word listener using Android's on-device SpeechRecognizer (API 31+).
 * Never uploads continuous microphone audio to the cloud and never secretly records.
 */
class LocalWakeWordEngine(
    private val context: Context,
    private val languageProvider: () -> String,
    private val onStateChanged: (active: Boolean, note: String) -> Unit
) : WakeWordEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var wakeCallback: ((String?) -> Unit)? = null
    private var restartDelayMs = 300L
    private var fallbackToEnglish = false

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(onWake: (trailingCommand: String?) -> Unit) {
        mainHandler.post {
            wakeCallback = onWake
            if (!isSupported) {
                active = false
                onStateChanged(false, "On-device wake word is unavailable on this device. Tap 'Ask VIA' in the notification to speak.")
                return@post
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                active = false
                onStateChanged(false, "Microphone permission is required for local \"Hey VIA\" listening.")
                return@post
            }
            if (active) return@post
            active = true
            restartDelayMs = 300L
            fallbackToEnglish = false
            beginListeningPass()
        }
    }

    override fun stop() {
        mainHandler.post {
            active = false
            mainHandler.removeCallbacksAndMessages(null)
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (_: Exception) {
            }
            recognizer = null
            onStateChanged(false, "Microphone idle.")
        }
    }

    private fun beginListeningPass() {
        if (!active || Build.VERSION.SDK_INT < 31) return
        if (WakeWordService.pausedForVoiceSession) {
            onStateChanged(false, "Paused while active voice interaction is running.")
            return
        }
        try {
            recognizer?.destroy()
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStateChanged(true, "Listening locally for \"Hey VIA\"")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    checkMatches(matches, isFinal = false)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (!checkMatches(matches, isFinal = true) && active) {
                        scheduleNextPass(200L)
                    }
                }

                override fun onError(error: Int) {
                    if (!active) return
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            active = false
                            onStateChanged(false, "Microphone permission was revoked.")
                        }
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            fallbackToEnglish = true
                            restartDelayMs = (restartDelayMs * 2).coerceAtMost(4000L)
                            onStateChanged(false, "Selected offline pack unavailable; listening in English for \"Hey VIA\".")
                            scheduleNextPass(restartDelayMs)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            scheduleNextPass(1200L)
                        }
                        else -> {
                            restartDelayMs = 300L
                            scheduleNextPass(restartDelayMs)
                        }
                    }
                }
            })

            val langTag = if (fallbackToEnglish) {
                "en-US"
            } else {
                when (languageProvider()) {
                    "kn" -> "kn-IN"
                    "hi" -> "hi-IN"
                    else -> "en-US"
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            }
            sr.startListening(intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "On-device wake-word pass failed", e)
            } else {
                Log.w(TAG, "On-device wake-word pass failed: ${e.javaClass.simpleName}")
            }
            onStateChanged(false, "Local wake word paused: ${e.message ?: "device restriction"}")
            scheduleNextPass(2500L)
        }
    }

    private fun checkMatches(matches: List<String>, isFinal: Boolean): Boolean {
        for (raw in matches) {
            val lower = raw.lowercase(Locale.ROOT).trim()
            val wakeMatch = Regex("\\b(?:hey|hi|ok|okay)\\s+via\\b[,:\\s-]*(.*)$").find(lower)
            if (wakeMatch != null) {
                val trailing = wakeMatch.groupValues[1].trim()
                if (isFinal || trailing.isEmpty()) {
                    active = false
                    mainHandler.removeCallbacksAndMessages(null)
                    try {
                        recognizer?.cancel()
                        recognizer?.destroy()
                    } catch (_: Exception) {
                    }
                    recognizer = null
                    onStateChanged(false, "\"Hey VIA\" detected!")
                    wakeCallback?.invoke(trailing.ifBlank { null })
                    return true
                }
            }
        }
        return false
    }

    private fun scheduleNextPass(delayMs: Long) {
        if (!active) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ beginListeningPass() }, delayMs)
    }
}

/**
 * Android Foreground Service keeping VIA available as a background voice assistant when enabled.
 *
 * - Runs only after explicit user consent and microphone permission.
 * - Handles full voice flow (Hey VIA -> STT -> Gemini AI / Device Command -> Store -> TTS -> Idle)
 *   even when the VIA UI is closed or minimized.
 * - Never secretly records audio or attempts to bypass Android force-stop or OEM restrictions.
 */
class WakeWordService : Service() {

    private lateinit var runtime: ViaRuntime
    private lateinit var store: Store
    private lateinit var bgVoice: VoiceController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: LocalWakeWordEngine? = null
    @Volatile private var bgBusy = false

    override fun onCreate() {
        super.onCreate()
        runtime = ViaRuntime.get(applicationContext)
        store = runtime.store
        runtime.ensureMonitorsActive()
        bgVoice = VoiceController(applicationContext)

        engine = LocalWakeWordEngine(
            context = applicationContext,
            languageProvider = { store.language },
            onStateChanged = { micActive, note ->
                listeningActive.value = micActive
                statusMessage.value = note
                if (runningState.value) {
                    updateNotification(micActive, note)
                }
            }
        )

        serviceScope.launch {
            runtime.deviceMonitor.alerts.collect { alert ->
                if (store.batteryVoiceAlertsEnabled &&
                    store.voiceResponseEnabled &&
                    !runtime.isUiForeground &&
                    !pausedForVoiceSession &&
                    !bgBusy
                ) {
                    bgVoice.speak(alert.message, "en")
                }
            }
        }

        serviceScope.launch {
            runtime.callManager.incomingAnnouncements.collect { announcement ->
                if (store.callMonitoringEnabled &&
                    store.voiceResponseEnabled &&
                    !runtime.isUiForeground &&
                    !pausedForVoiceSession &&
                    !bgBusy
                ) {
                    bgVoice.speak(announcement, "en")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            store.backgroundAssistantEnabled = false
            stopAssistantService("Background Assistant stopped.")
            return START_NOT_STICKY
        }

        // Only continue if Android restarted the service while Background Assistant is explicitly enabled.
        if (intent == null && !store.backgroundAssistantEnabled) {
            stopAssistantService("Background Assistant is off.")
            return START_NOT_STICKY
        }

        if (action == ACTION_PAUSE_FOR_VOICE) {
            pausedForVoiceSession = true
            engine?.stop()
            updateNotification(false, "Active voice interaction in progress.")
            return START_STICKY
        }

        if (action == ACTION_RESUME_AFTER_VOICE) {
            pausedForVoiceSession = false
            if (store.backgroundAssistantEnabled && store.localWakeWordEnabled) {
                engine?.start { trailing -> handleWakeDetected(trailing) }
            } else {
                updateNotification(false, "Ready in background — tap 'Ask VIA' to speak.")
            }
            return START_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            store.backgroundAssistantEnabled = false
            stopAssistantService("Microphone permission is required for Background Assistant.")
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val initialMicNote = if (store.localWakeWordEnabled) {
            if (engine?.isSupported == true) "Starting local \"Hey VIA\" listener..."
            else "On-device wake word unsupported here. Tap 'Ask VIA' to speak."
        } else {
            "Background standby (wake word off). Tap 'Ask VIA' to speak."
        }

        val startedForeground = try {
            val notif = buildNotification(micActive = false, detail = initialMicNote)
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Android restricted starting microphone FGS from background: ${e.javaClass.simpleName}")
            statusMessage.value = "Android restricted microphone start in background. Open VIA Settings to allow Background Assistant."
            false
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Unable to start foreground service", e)
            } else {
                Log.w(TAG, "Unable to start foreground service: ${e.javaClass.simpleName}")
            }
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                statusMessage.value = "Android battery/background policy paused Background Assistant. Set VIA battery usage to Unrestricted in Android Settings."
            } else {
                statusMessage.value = "Device restricted background service (${e.javaClass.simpleName})."
            }
            false
        }

        if (!startedForeground) {
            running = false
            runningState.value = false
            listeningActive.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        store.backgroundAssistantEnabled = true
        running = true
        runningState.value = true
        runtime.ensureMonitorsActive()
        statusMessage.value = initialMicNote

        if (action == ACTION_REFRESH || action == ACTION_START || action == null) {
            if (store.localWakeWordEnabled && !pausedForVoiceSession && !bgBusy) {
                engine?.start { trailing -> handleWakeDetected(trailing) }
            } else if (!pausedForVoiceSession && !bgBusy) {
                engine?.stop()
                listeningActive.value = false
                updateNotification(false, initialMicNote)
            }
        }

        return START_STICKY
    }

    private fun handleWakeDetected(trailingCommand: String?) {
        listeningActive.value = false

        // If the VIA UI is visible in foreground, delegate so the animated avatar drives the turn.
        if (runtime.isUiForeground) {
            val eventId = System.currentTimeMillis()
            val event = if (trailingCommand.isNullOrBlank()) {
                WakeEvent.StartVoice(eventId)
            } else {
                WakeEvent.WakeCommand(eventId, trailingCommand)
            }
            events.tryEmit(event)
            return
        }

        // VIA UI is closed or minimized: run the complete hands-free voice loop directly in background.
        runBackgroundVoiceTurn(trailingCommand)
    }

    private fun runBackgroundVoiceTurn(trailingCommand: String?) {
        pausedForVoiceSession = true
        bgBusy = true

        if (!trailingCommand.isNullOrBlank()) {
            processBackgroundQuery(trailingCommand)
            return
        }

        val lang = store.language
        val startListenPass = {
            listeningActive.value = true
            updateNotification(true, "Listening for your question...")
            bgVoice.listen(
                lang = lang,
                onCancelledOrFailed = {
                    listeningActive.value = false
                    resumeWaitingLoop("Ready — listening for \"Hey VIA\"")
                }
            ) { recognized ->
                listeningActive.value = false
                processBackgroundQuery(recognized)
            }
        }

        if (store.voiceResponseEnabled && bgVoice.isTtsReady()) {
            val cue = when (lang) {
                "kn" -> "ಹೇಳಿ."
                "hi" -> "हाँ, बताइए।"
                else -> "Yes?"
            }
            updateNotification(false, "\"Hey VIA\" heard — preparing mic...")
            bgVoice.speak(cue, lang) {
                if (runningState.value) {
                    startListenPass()
                } else {
                    resumeWaitingLoop("Background Assistant stopped.")
                }
            }
        } else {
            startListenPass()
        }
    }

    private fun processBackgroundQuery(rawText: String) {
        val cleaned = rawText.replace(
            Regex("^(?:hey|hi|ok|okay|hello)[,\\s]+via\\b[,:\\s-]*", RegexOption.IGNORE_CASE),
            ""
        ).trim().ifEmpty { rawText.trim() }

        if (cleaned.isBlank()) {
            resumeWaitingLoop("Ready — listening for \"Hey VIA\"")
            return
        }

        val lang = store.language
        updateNotification(false, "Processing: \"$cleaned\"")

        // Persist user prompt so it appears when VIA UI opens.
        store.saveMessages(store.messages() + store.newMessage(true, rawText))
        runtime.notifyStoreUpdated()

        serviceScope.launch {
            var replyLang = "en"
            val reply = try {
                when (val cmd = runtime.commandEngine.execute(cleaned, lang)) {
                    is CommandResult.Handled -> {
                        store.addCommandLog(cleaned, cmd.reply)
                        cmd.reply
                    }
                    is CommandResult.Navigate -> {
                        store.addCommandLog(cleaned, cmd.reply)
                        "${cmd.reply} Open VIA to view it on screen."
                    }
                    CommandResult.NotACommand -> {
                        if (!isNetworkOnline()) {
                            when (val off = runtime.commandEngine.executeOfflineFallback(cleaned, lang)) {
                                is CommandResult.Handled -> {
                                    replyLang = if (off.reply.any { it.code > 0x7F }) lang else "en"
                                    store.addCommandLog(cleaned, off.reply)
                                    off.reply
                                }
                                is CommandResult.Navigate -> {
                                    store.addCommandLog(cleaned, off.reply)
                                    "${off.reply} Open VIA to view it on screen."
                                }
                                CommandResult.NotACommand -> {
                                    "You're currently offline. Try \"Battery Status\", \"Flashlight On\", or \"What time is it?\"."
                                }
                            }
                        } else {
                            replyLang = lang
                            generateBackgroundAiReply(cleaned, lang)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Background query failed", e)
                } else {
                    Log.e(TAG, "Background query failed: ${e.javaClass.simpleName}")
                }
                replyLang = "en"
                e.localizedMessage ?: "Sorry, I couldn't complete that request right now."
            }

            // Persist VIA answer so it displays in chat whenever the UI becomes available.
            store.saveMessages(store.messages() + store.newMessage(false, reply))
            runtime.notifyStoreUpdated()
            updateNotification(false, "VIA: $reply")

            if (store.voiceResponseEnabled) {
                bgVoice.speak(reply, replyLang) {
                    resumeWaitingLoop("Ready — listening for \"Hey VIA\"")
                }
            } else {
                resumeWaitingLoop("VIA answered in notification. Listening for \"Hey VIA\"")
            }
        }
    }

    private suspend fun generateBackgroundAiReply(query: String, lang: String): String {
        val history = store.messages().dropLast(1)
        if (store.backendUrl.isNotBlank()) {
            return CloudClient.chat(
                baseUrl = store.backendUrl,
                token = store.apiToken,
                message = query,
                language = lang,
                personality = store.personality,
                history = history
            ).getOrElse {
                "Custom backend error: ${it.message ?: "Unable to connect"}"
            }
        }

        var sheetsKnowledge: String? = null
        var sheetsStatusNote: String? = null
        if (store.sheetsEnabled) {
            when (val res = runtime.sheetsRepo.fetchLatestOrCached(applicationContext)) {
                is SheetsFetchOutcome.Success -> sheetsKnowledge = res.snapshot.toKnowledgeText()
                SheetsFetchOutcome.NeedsReauth -> {
                    sheetsStatusNote = "Google Sheets authorization needs to be refreshed inside VIA."
                }
                is SheetsFetchOutcome.Error -> {
                    sheetsStatusNote = "Could not load latest Google Sheet data: ${res.userMessage}"
                }
            }
        }

        var completed = ""
        runtime.aiRepo.streamReply(
            prompt = query,
            language = lang,
            personality = store.personality,
            history = history,
            memory = store.memory(),
            sheetsKnowledge = sheetsKnowledge,
            sheetsStatusNote = sheetsStatusNote,
            preferFast = true
        ).collect { ev ->
            if (ev is AiStreamEvent.Completed) {
                completed = ev.fullText
            }
        }
        return completed.ifBlank { "I didn't receive a response. Please try again." }
    }

    private fun isNetworkOnline(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun resumeWaitingLoop(note: String) {
        bgBusy = false
        pausedForVoiceSession = false
        listeningActive.value = false
        if (!runningState.value) return
        if (store.backgroundAssistantEnabled && store.localWakeWordEnabled) {
            engine?.start { trailing -> handleWakeDetected(trailing) }
        } else {
            statusMessage.value = note
            updateNotification(false, note)
        }
    }

    private fun stopAssistantService(reason: String) {
        pausedForVoiceSession = false
        bgBusy = false
        engine?.stop()
        bgVoice.cancel()
        running = false
        runningState.value = false
        listeningActive.value = false
        statusMessage.value = reason
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        bgVoice.shutdown()
        serviceScope.cancel()
        running = false
        runningState.value = false
        listeningActive.value = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "VIA Background Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent indicator while VIA background assistant is active"
            }
        )
    }

    private fun updateNotification(micActive: Boolean, detail: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(micActive, detail))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(micActive: Boolean, detail: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val askIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_START_VOICE, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (micActive) {
            "VIA Background Assistant · Mic Active"
        } else {
            "VIA Background Assistant · Ready"
        }

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_btn_speak_now,
                    "Ask VIA",
                    askIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop Assistant",
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.vipstudios.via.START_WAKE"
        const val ACTION_STOP = "com.vipstudios.via.STOP_WAKE"
        const val ACTION_REFRESH = "com.vipstudios.via.REFRESH_WAKE"
        const val ACTION_PAUSE_FOR_VOICE = "com.vipstudios.via.PAUSE_WAKE"
        const val ACTION_RESUME_AFTER_VOICE = "com.vipstudios.via.RESUME_WAKE"

        private const val CHANNEL = "via_wake"
        private const val NOTIF_ID = 42

        @Volatile var running = false
        @Volatile var pausedForVoiceSession = false

        val runningState = MutableStateFlow(false)
        val listeningActive = MutableStateFlow(false)
        val statusMessage = MutableStateFlow("Background Assistant is off.")
        val events = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 8)

        fun isOnDeviceWakeSupported(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(PowerManager::class.java)
            return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }

        fun isBackgroundRestricted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val am = context.getSystemService(ActivityManager::class.java)
            return am?.isBackgroundRestricted ?: false
        }
    }
}
