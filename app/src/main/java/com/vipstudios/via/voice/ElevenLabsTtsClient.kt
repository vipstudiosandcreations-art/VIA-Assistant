package com.vipstudios.via.voice

import android.content.Context
import android.util.Log
import com.vipstudios.via.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ElevenLabsTtsClient"

/**
 * ElevenLabs neural Text-to-Speech client configured with Voice ID K24eC7JpUgk8zMtQYrpV ("Viraj").
 * Reads ELEVENLABS_API_KEY strictly from BuildConfig (populated via Secrets / .env) and never logs keys.
 */
object ElevenLabsTtsClient {
    const val DEFAULT_VOICE_ID = BuildConfig.ELEVENLABS_DEFAULT_VOICE_ID
    private const val MODEL_MULTILINGUAL_V2 = "eleven_multilingual_v2"

    fun isConfigured(): Boolean = BuildConfig.ELEVENLABS_API_KEY.isNotBlank()

    fun sanitizeVoiceId(raw: String): String =
        raw.trim().ifEmpty { DEFAULT_VOICE_ID }

    suspend fun synthesizeToCacheFile(
        context: Context,
        text: String,
        voiceId: String = DEFAULT_VOICE_ID
    ): Result<File> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.ELEVENLABS_API_KEY.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("ElevenLabs API key is not configured.")
            )
        }
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Text cannot be empty."))
        }
        val effectiveVoiceId = sanitizeVoiceId(voiceId)
        val endpoint = "https://api.elevenlabs.io/v1/text-to-speech/$effectiveVoiceId?output_format=mp3_44100_128&optimize_streaming_latency=3"

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("xi-api-key", apiKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "audio/mpeg")
            }

            val payload = JSONObject()
                .put("text", cleanText.take(2500))
                .put("model_id", MODEL_MULTILINGUAL_V2)
                .put(
                    "voice_settings",
                    JSONObject()
                        .put("stability", 0.50)
                        .put("similarity_boost", 0.78)
                        .put("style", 0.20)
                        .put("use_speaker_boost", true)
                )

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "ElevenLabs TTS returned HTTP $code for voiceId=$effectiveVoiceId")
                return@withContext Result.failure(IOException("ElevenLabs HTTP $code"))
            }

            val outFile = File(context.cacheDir, "via_tts_${System.currentTimeMillis()}.mp3")
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.length() <= 0L) {
                outFile.delete()
                return@withContext Result.failure(IOException("Empty audio response from ElevenLabs"))
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs synthesis failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
