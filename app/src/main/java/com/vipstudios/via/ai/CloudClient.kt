package com.vipstudios.via.ai

import com.vipstudios.via.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the VIA cloud backend (built in Stage 2).
 *
 * API contract the backend must follow:
 *   GET  {base}/health            -> 200
 *   POST {base}/v1/chat           body: {message, language, personality, history:[{role,content}]}
 *                                 reply: {"reply": "text"}
 * Authorization: Bearer <token>   (optional until the backend enforces it)
 *
 * No AI keys are stored in the app. They live only on the backend.
 */
object CloudClient {

    private fun check(base: String): String? =
        if (!base.startsWith("https://")) "The backend address must start with https://" else null

    suspend fun chat(
        baseUrl: String,
        token: String,
        message: String,
        language: String,
        personality: String,
        history: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        check(baseUrl)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                val c = (URL(baseUrl.trimEnd('/') + "/v1/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                val hist = JSONArray()
                history.takeLast(10).forEach {
                    hist.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
                }
                val body = JSONObject()
                    .put("message", message)
                    .put("language", language)
                    .put("personality", personality)
                    .put("history", hist)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) throw IOException("server returned $code")
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                return@withContext Result.success(JSONObject(txt).getString("reply"))
            } catch (e: Exception) {
                last = e
                if (attempt == 0) delay(1500)
            }
        }
        Result.failure(last ?: IOException("unknown error"))
    }

    suspend fun health(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        check(baseUrl)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        try {
            val c = URL(baseUrl.trimEnd('/') + "/health").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode in 200..299) Result.success(Unit)
            else Result.failure(IOException("server returned ${c.responseCode}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
