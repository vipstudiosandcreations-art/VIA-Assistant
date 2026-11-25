package com.vipstudios.via.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "VoiceController"

enum class VoiceState { Idle, Listening, Speaking }

/**
 * Reliable Speech-to-Text (Kannada, English, Hindi) and Text-to-Speech controller
 * with Main-thread enforcement, duplicate-submission prevention, silence/partial recovery,
 * and real-time lip-sync envelope.
 */
class VoiceController(context: Context) {
    private val ctx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var lipJob: Job? = null
    private var listenWatchdogJob: Job? = null

    private val listenSessionSeq = AtomicInteger(0)
    @Volatile private var activeSessionId = 0
    @Volatile private var sessionDelivered = false
    @Volatile private var userStoppedManually = false
    @Volatile private var usedOnDeviceFallback = false

    @Volatile private var doneCb: (() -> Unit)? = null
    @Volatile private var activeUtteranceText: String = ""
    @Volatile private var wordPulseTarget: Float = 0.55f
    @Volatile private var wordPulseTime: Long = 0L

    val state = MutableStateFlow(VoiceState.Idle)
    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val mouthLevel = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } else {
                    Log.w(TAG, "TextToSpeech initialization failed with status=$status")
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    startLipSyncDriver()
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val src = activeUtteranceText
                    val word = if (start in 0..src.length && end in start..src.length) {
                        src.substring(start, end)
                    } else ""
                    val vowels = word.count { it.lowercaseChar() in "aeiouāīūēōअआइईउऊएऐओऔಅಆಇಈಉಊಎಏಐಒಓಔ" }
                    wordPulseTarget = (0.45f + (vowels.coerceAtMost(4) * 0.13f)).coerceIn(0.4f, 1.0f)
                    wordPulseTime = System.currentTimeMillis()
                }

                override fun onDone(utteranceId: String?) = finishSpeaking()

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS utterance error code=$errorCode")
                    error.value = "Voice output encountered an error (code $errorCode)."
                    finishSpeaking()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS utterance error")
                    finishSpeaking()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Unable to construct TextToSpeech", e)
            ttsReady = false
        }
    }

    private fun startLipSyncDriver() {
        lipJob?.cancel()
        lipJob = scope.launch {
            var phase = 0f
            while (isActive && state.value == VoiceState.Speaking) {
                val sinceWord = System.currentTimeMillis() - wordPulseTime
                val wordBoost = if (sinceWord < 220L) wordPulseTarget else 0.5f
                val syllableWave = 0.35f + 0.65f * abs(sin(phase))
                mouthLevel.value = (wordBoost * syllableWave).coerceIn(0.12f, 1.0f)
                phase += 0.52f
                delay(32L)
            }
            mouthLevel.value = 0f
        }
    }

    private fun finishSpeaking() {
        mainHandler.post {
            lipJob?.cancel()
            mouthLevel.value = 0f
            if (state.value == VoiceState.Speaking) {
                state.value = VoiceState.Idle
            }
            val cb = doneCb
            doneCb = null
            cb?.invoke()
        }
    }

    fun tag(lang: String) = when (lang) {
        "kn" -> "kn-IN"
        "hi" -> "hi-IN"
        else -> "en-IN"
    }

    fun label(lang: String) = when (lang) {
        "kn" -> "Kannada"
        "hi" -> "Hindi"
        else -> "English"
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(ctx) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

    fun isTtsReady(): Boolean = ttsReady

    fun isTtsLanguageAvailable(lang: String): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val code = engine.isLanguageAvailable(Locale.forLanguageTag(tag(lang)))
        return code >= TextToSpeech.LANG_AVAILABLE
    }

    fun listen(
        lang: String,
        onCancelledOrFailed: () -> Unit = {},
        onText: (String) -> Unit
    ) {
        mainHandler.post {
            if (!hasMicPermission()) {
                error.value = "Microphone permission is required for voice input."
                state.value = VoiceState.Idle
                onCancelledOrFailed()
                return@post
            }
            if (!isSpeechRecognitionAvailable()) {
                error.value = "Speech recognition isn't available on this device. Install or enable Google Speech Services."
                state.value = VoiceState.Idle
                onCancelledOrFailed()
                return@post
            }

            // Prevent overlapping sessions
            stopSpeakingInternal(invokeCallback = false)
            listenWatchdogJob?.cancel()
            destroyRecognizerSafely()

            val sessionId = listenSessionSeq.incrementAndGet()
            activeSessionId = sessionId
            sessionDelivered = false
            userStoppedManually = false
            usedOnDeviceFallback = false

            startRecognizerSession(sessionId, lang, preferOnDevice = false, onCancelledOrFailed = onCancelledOrFailed, onText = onText)
        }
    }

    private fun startRecognizerSession(
        sessionId: Int,
        lang: String,
        preferOnDevice: Boolean,
        onCancelledOrFailed: () -> Unit,
        onText: (String) -> Unit
    ) {
        if (sessionId != activeSessionId || sessionDelivered) return

        val r = try {
            if (preferOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            error.value = "Could not start speech recognizer on this device."
            state.value = VoiceState.Idle
            onCancelledOrFailed()
            return
        }
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionId != activeSessionId) return
                error.value = null
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionId != activeSessionId) return
                level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (sessionId != activeSessionId) return
                level.value = 0f
                // Watchdog in case OEM speech service stalls without calling onResults/onError
                listenWatchdogJob?.cancel()
                listenWatchdogJob = scope.launch {
                    delay(6500L)
                    if (sessionId == activeSessionId && !sessionDelivered && state.value == VoiceState.Listening) {
                        val fallbackPartial = partial.value.trim()
                        if (fallbackPartial.isNotEmpty()) {
                            deliverResult(sessionId, fallbackPartial, onText)
                        } else {
                            endListeningWithError(sessionId, "Timed out waiting for speech result. Tap the mic to try again.", onCancelledOrFailed)
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != activeSessionId || sessionDelivered) return
                val t = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { !it.isNullOrBlank() }
                    ?.trim()
                if (!t.isNullOrBlank()) {
                    partial.value = t
                }
            }

            override fun onResults(results: Bundle?) {
                if (sessionId != activeSessionId || sessionDelivered) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val recognized = matches.firstOrNull { !it.isNullOrBlank() }?.trim()
                    ?: partial.value.trim().takeIf { it.isNotEmpty() }

                if (recognized.isNullOrBlank()) {
                    endListeningWithError(sessionId, "I didn't catch that. Tap the mic and speak clearly.", onCancelledOrFailed)
                } else {
                    deliverResult(sessionId, recognized, onText)
                }
            }

            override fun onError(code: Int) {
                if (sessionId != activeSessionId || sessionDelivered) return
                Log.w(TAG, "SpeechRecognizer onError code=$code (onDeviceFallback=$usedOnDeviceFallback)")

                // If network speech fails and on-device recognition exists, transparently retry offline once.
                if ((code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) &&
                    !usedOnDeviceFallback &&
                    Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                ) {
                    usedOnDeviceFallback = true
                    destroyRecognizerSafely()
                    startRecognizerSession(sessionId, lang, preferOnDevice = true, onCancelledOrFailed = onCancelledOrFailed, onText = onText)
                    return
                }

                // If user already spoke partial text and tapped Stop or hit end-of-speech timeout, recover it cleanly.
                val partialText = partial.value.trim()
                if (partialText.isNotEmpty() && (userStoppedManually ||
                        code == SpeechRecognizer.ERROR_NO_MATCH ||
                        code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    deliverResult(sessionId, partialText, onText)
                    return
                }

                val msg = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech detected. Tap the mic and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone permission is needed for voice input."
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition needs an internet connection for ${label(lang)} right now."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Microphone audio error. Check that no other app is using the mic."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Voice recognizer was busy. Please tap the mic again."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "${label(lang)} speech recognition isn't available on this device yet."
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> "Speech recognition error ($code). Please try again."
                }
                endListeningWithError(sessionId, msg, onCancelledOrFailed)
            }
        })

        val langTag = tag(lang)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (preferOnDevice) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        error.value = null
        partial.value = ""
        level.value = 0f
        state.value = VoiceState.Listening
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw exception", e)
            endListeningWithError(sessionId, "Could not start microphone listening.", onCancelledOrFailed)
        }
    }

    private fun deliverResult(sessionId: Int, text: String, onText: (String) -> Unit) {
        if (sessionId != activeSessionId || sessionDelivered) return
        sessionDelivered = true
        listenWatchdogJob?.cancel()
        level.value = 0f
        partial.value = ""
        state.value = VoiceState.Idle
        destroyRecognizerSafely()
        onText(text)
    }

    private fun endListeningWithError(sessionId: Int, message: String?, onCancelledOrFailed: () -> Unit = {}) {
        if (sessionId != activeSessionId || sessionDelivered) return
        sessionDelivered = true
        listenWatchdogJob?.cancel()
        level.value = 0f
        state.value = VoiceState.Idle
        if (!message.isNullOrBlank()) {
            error.value = message
        }
        destroyRecognizerSafely()
        onCancelledOrFailed()
    }

    fun stopListening() {
        mainHandler.post {
            userStoppedManually = true
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
                endListeningWithError(activeSessionId, null)
            }
        }
    }

    fun speak(text: String, lang: String, onDone: () -> Unit = {}) {
        mainHandler.post {
            val cleanText = text.replace("*", "").replace("#", "").trim().take(3500)
            if (cleanText.isEmpty()) {
                onDone()
                return@post
            }
            val engine = tts
            if (engine == null || !ttsReady) {
                error.value = "Voice output is not ready on this device yet."
                state.value = VoiceState.Idle
                onDone()
                return@post
            }

            val requestedLocale = Locale.forLanguageTag(tag(lang))
            val res = engine.setLanguage(requestedLocale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fall back to default English voice if Kannada/Hindi TTS pack isn't installed so speech still completes gracefully
                val fallback = if (lang != "en") engine.setLanguage(Locale.forLanguageTag("en-IN")) else res
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    error.value = "${label(lang)} voice isn't installed. Open phone Settings → Text-to-speech to install it."
                    state.value = VoiceState.Idle
                    onDone()
                    return@post
                } else {
                    error.value = "${label(lang)} TTS voice pack isn't installed; using fallback voice."
                }
            } else {
                error.value = null
            }

            activeUtteranceText = cleanText
            wordPulseTime = System.currentTimeMillis()
            doneCb = onDone
            state.value = VoiceState.Speaking
            startLipSyncDriver()

            val speakCode = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "via-utt-${System.currentTimeMillis()}")
            if (speakCode != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech.speak returned error code=$speakCode")
                error.value = "Voice output failed to start."
                finishSpeaking()
            }
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            stopSpeakingInternal(invokeCallback = false)
        }
    }

    private fun stopSpeakingInternal(invokeCallback: Boolean) {
        lipJob?.cancel()
        mouthLevel.value = 0f
        val cb = doneCb
        doneCb = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (state.value == VoiceState.Speaking) {
            state.value = VoiceState.Idle
        }
        if (invokeCallback) cb?.invoke()
    }

    fun cancel() {
        mainHandler.post {
            activeSessionId = listenSessionSeq.incrementAndGet()
            sessionDelivered = true
            listenWatchdogJob?.cancel()
            try {
                recognizer?.cancel()
            } catch (_: Exception) {
            }
            destroyRecognizerSafely()
            stopSpeakingInternal(invokeCallback = false)
            level.value = 0f
            partial.value = ""
            state.value = VoiceState.Idle
        }
    }

    private fun destroyRecognizerSafely() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    fun shutdown() {
        listenWatchdogJob?.cancel()
        lipJob?.cancel()
        scope.cancel()
        destroyRecognizerSafely()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }
}
