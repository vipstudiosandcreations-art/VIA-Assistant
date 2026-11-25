package com.vipstudios.via.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class VoiceState { Idle, Listening, Speaking }

/** Speech-to-text and text-to-speech using the phone's own Android engines. Call from the main thread. */
class VoiceController(context: Context) {
    private val ctx = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var doneCb: (() -> Unit)? = null

    val state = MutableStateFlow(VoiceState.Idle)
    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    init {
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) error.value = "Voice output isn't available on this phone."
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finishSpeaking()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishSpeaking()
        })
    }

    private fun finishSpeaking() {
        state.value = VoiceState.Idle
        val cb = doneCb
        doneCb = null
        cb?.invoke()
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

    fun listen(lang: String, onText: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            error.value = "Speech recognition isn't available on this phone. Install or enable the Google app."
            return
        }
        stopSpeaking()
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level.value = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) partial.value = t
            }
            override fun onResults(results: Bundle?) {
                level.value = 0f
                state.value = VoiceState.Idle
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) error.value = "I didn't catch that. Tap the mic and try again."
                else onText(t)
            }
            override fun onError(code: Int) {
                level.value = 0f
                state.value = VoiceState.Idle
                error.value = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "I didn't catch that. Tap the mic and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition needs internet for this language right now."
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> "Speech recognition error ($code)."
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag(lang))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        error.value = null
        partial.value = ""
        state.value = VoiceState.Listening
        r.startListening(i)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun speak(text: String, lang: String, onDone: () -> Unit = {}) {
        val engine = tts
        if (engine == null || !ttsReady) {
            error.value = "Voice output is still starting. Try again in a moment."
            onDone()
            return
        }
        val res = engine.setLanguage(Locale.forLanguageTag(tag(lang)))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            error.value = "${label(lang)} voice isn't installed. Open phone Settings, Text-to-speech, and install it."
            onDone()
            return
        }
        error.value = null
        doneCb = onDone
        state.value = VoiceState.Speaking
        engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "via-utt")
    }

    fun stopSpeaking() {
        doneCb = null
        tts?.stop()
        if (state.value == VoiceState.Speaking) state.value = VoiceState.Idle
    }

    fun cancel() {
        recognizer?.cancel()
        stopSpeaking()
        level.value = 0f
        state.value = VoiceState.Idle
    }

    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
