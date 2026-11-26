package com.vipstudios.via.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.data.ChatMessage
import com.vipstudios.via.data.MemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "ViaAiRepository"
private const val MODEL_NAME = "gemini-3.8-flash"
private const val FAST_MODEL_NAME = "gemini-3.5-flash-lite"

sealed interface AiStreamEvent {
    data class Chunk(val accumulatedText: String) : AiStreamEvent
    data class Completed(val fullText: String) : AiStreamEvent
}

/**
 * Connects VIA to Google Gemini via Firebase AI Logic (`Firebase.ai`) and App Check.
 * Caches configured model instances and routes voice turns to low-latency Flash-Lite
 * with automatic fallback so Gemini AI replies stay fast and reliable.
 */
class ViaAiRepository {

    private val defaultModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 650
            }
        )
    }

    private val diagnosticModel by lazy {
        Firebase.ai.generativeModel(
            modelName = FAST_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 80
            }
        )
    }

    private val modelCacheLock = Any()
    private val cachedModels = LinkedHashMap<String, com.google.firebase.ai.GenerativeModel>(8, 0.75f, true)

    private fun getOrCreateChatModel(
        modelName: String,
        maxTokens: Int,
        language: String,
        personality: String,
        memory: List<MemoryItem>,
        sheetsKnowledge: String?,
        sheetsStatusNote: String?
    ): com.google.firebase.ai.GenerativeModel {
        val memKey = memory.takeLast(10).joinToString("|") { "${it.id}:${it.text.hashCode()}" }
        val sheetKey = "${sheetsKnowledge?.hashCode() ?: 0}:${sheetsStatusNote?.hashCode() ?: 0}"
        val cacheKey = "$modelName:$maxTokens:$language:${personality.hashCode()}:$memKey:$sheetKey"

        synchronized(modelCacheLock) {
            cachedModels[cacheKey]?.let { return it }
        }

        val built = Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.65f
                maxOutputTokens = maxTokens
            },
            systemInstruction = buildSystemInstruction(
                language = language,
                personality = personality,
                memory = memory,
                sheetsKnowledge = sheetsKnowledge,
                sheetsStatusNote = sheetsStatusNote,
                conciseSpeechMode = maxTokens <= 360
            )
        )

        synchronized(modelCacheLock) {
            if (cachedModels.size >= 8) {
                val eldest = cachedModels.keys.firstOrNull()
                if (eldest != null) cachedModels.remove(eldest)
            }
            cachedModels[cacheKey] = built
        }
        return built
    }

    private fun buildSystemInstruction(
        language: String,
        personality: String,
        memory: List<MemoryItem>,
        sheetsKnowledge: String?,
        sheetsStatusNote: String?,
        conciseSpeechMode: Boolean = false
    ): Content {
        val languageName = when (language) {
            "kn" -> "Kannada (ಕನ್ನಡ). Reply naturally in Kannada script unless the user explicitly asks for another language."
            "hi" -> "Hindi (हिन्दी). Reply naturally in Hindi (Devanagari script) unless the user explicitly asks for another language."
            else -> "English."
        }
        val memorySummary = if (memory.isEmpty()) {
            "None saved yet."
        } else {
            memory.takeLast(10).joinToString("; ") { "[${it.kind}] ${it.text}" }
        }
        val systemText = buildString {
            appendLine("You are VIA (VIP Intelligent Assistant), a fast, friendly, intelligent personal AI assistant developed by VIP Studios And Creations.")
            appendLine("Personality: ${personality.ifBlank { "Friendly, clear, intelligent, and concise." }}")
            appendLine("Preferred response language: $languageName")
            appendLine("User's saved local memory/notes/tasks: $memorySummary")
            if (!sheetsKnowledge.isNullOrBlank()) {
                appendLine()
                appendLine("Connected Google Sheet Knowledge Source (LIVE, STRICTLY READ-ONLY):")
                appendLine(sheetsKnowledge)
                appendLine()
                appendLine("Google Sheet Rules:")
                appendLine("- Use the latest Google Sheet data above to answer relevant questions accurately.")
                appendLine("- If the user asks about something that is NOT present in the Google Sheet data above, clearly tell the user that the information was not found in the spreadsheet. Never invent, guess, or extrapolate missing spreadsheet values.")
                appendLine("- Your Google Sheets access is strictly READ-ONLY. If the user asks to edit, add, update, overwrite, or delete spreadsheet rows or cells, clearly explain that VIA has read-only access and cannot modify the spreadsheet.")
            } else if (!sheetsStatusNote.isNullOrBlank()) {
                appendLine()
                appendLine("Google Sheet Status: $sheetsStatusNote")
                appendLine("- If the user asks for information from the Google Sheet or spreadsheet, do not guess any data; clearly explain this status and suggest tapping Connect/Refresh Google Sheets in Settings or on the dashboard.")
                appendLine("- Google Sheets access is strictly READ-ONLY and never modifies spreadsheet data.")
            }
            if (conciseSpeechMode) {
                appendLine("Guidelines: Give a direct, conversational spoken reply in 1-3 short sentences unless the user explicitly requests detail. Avoid markdown asterisks or bullet symbols.")
            } else {
                appendLine("Guidelines: Be helpful, warm, direct, and easy to read aloud via text-to-speech. Keep answers concise (2-4 sentences) unless the user asks for detail. Avoid markdown asterisks so speech output sounds natural.")
            }
        }
        return content { text(systemText) }
    }

    /**
     * Ensures valid Gemini chat history: starts with a "user" turn, strictly alternates user/model,
     * and ends with a "model" turn before sending the next user prompt.
     */
    internal fun buildHistory(history: List<ChatMessage>): List<Content> {
        val raw = history.takeLast(10).filter { it.text.isNotBlank() }
        val normalized = mutableListOf<Pair<String, String>>()
        for (msg in raw) {
            val role = if (msg.fromUser) "user" else "model"
            if (normalized.isEmpty() && role == "model") continue
            if (normalized.isNotEmpty() && normalized.last().first == role) {
                val prev = normalized.removeAt(normalized.lastIndex)
                normalized.add(role to "${prev.second}\n${msg.text}")
            } else {
                normalized.add(role to msg.text)
            }
        }
        if (normalized.isNotEmpty() && normalized.last().first == "user") {
            normalized.removeAt(normalized.lastIndex)
        }
        return normalized.map { (role, msgText) ->
            content(role) { text(msgText) }
        }
    }

    /**
     * Streaming multi-turn Gemini chat response via Firebase AI Logic `startChat` + `sendMessageStream`.
     * Uses ultra-fast `gemini-3.5-flash-lite` for voice queries (`preferFast = true`) with automatic
     * cross-model fallback to maximize speed and reliability.
     */
    fun streamReply(
        prompt: String,
        language: String,
        personality: String,
        history: List<ChatMessage>,
        memory: List<MemoryItem>,
        sheetsKnowledge: String? = null,
        sheetsStatusNote: String? = null,
        preferFast: Boolean = false
    ): Flow<AiStreamEvent> = flow {
        val primaryName = if (preferFast) FAST_MODEL_NAME else MODEL_NAME
        val fallbackName = if (preferFast) MODEL_NAME else FAST_MODEL_NAME
        val maxTokens = if (preferFast) 320 else 650
        val normalizedHistory = buildHistory(history)

        suspend fun runStream(modelName: String, timeoutMs: Long): String {
            val model = getOrCreateChatModel(
                modelName = modelName,
                maxTokens = maxTokens,
                language = language,
                personality = personality,
                memory = memory,
                sheetsKnowledge = sheetsKnowledge,
                sheetsStatusNote = sheetsStatusNote
            )
            val chat = model.startChat(history = normalizedHistory)
            val accumulated = StringBuilder()
            withTimeout(timeoutMs) {
                chat.sendMessageStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(AiStreamEvent.Chunk(accumulated.toString()))
                    }
                }
            }
            return accumulated.toString().trim()
        }

        try {
            val primaryText = try {
                runStream(primaryName, timeoutMs = 45_000L)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "$primaryName timed out; trying fallback $fallbackName")
                    runStream(fallbackName, timeoutMs = 35_000L)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "$primaryName failed (${e.javaClass.simpleName}); trying fallback $fallbackName")
                runStream(fallbackName, timeoutMs = 35_000L)
            }

            if (primaryText.isBlank()) {
                throw IllegalStateException("VIA received an empty response from Gemini.")
            }
            emit(AiStreamEvent.Completed(primaryText))
        } catch (e: TimeoutCancellationException) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Firebase AI generation timed out", e)
            } else {
                Log.e(TAG, "Firebase AI generation timed out")
            }
            throw IllegalStateException("Gemini timed out. Please check your internet connection and try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Firebase AI generation failed", e)
            } else {
                Log.e(TAG, "Firebase AI generation failed: ${e.javaClass.simpleName}")
            }
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fast streaming Gemini connectivity check using `gemini-3.5-flash-lite`
     * with `gemini-3.8-flash` fallback.
     */
    suspend fun testConnection(language: String): String = withContext(Dispatchers.IO) {
        val prompt = when (language) {
            "kn" -> "Reply in 1 short Kannada sentence confirming VIA is connected to Gemini."
            "hi" -> "Reply in 1 short Hindi sentence confirming VIA is connected to Gemini."
            else -> "Reply in 1 short sentence confirming VIA is connected to Gemini and ready."
        }
        try {
            val text = try {
                streamDiagnostic(diagnosticModel, prompt, timeoutMs = 25_000L)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) "" else throw e
            } catch (_: Exception) {
                ""
            }
            if (text.isNotBlank()) return@withContext text
            val fallbackText = streamDiagnostic(defaultModel, prompt, timeoutMs = 35_000L)
            if (fallbackText.isBlank()) {
                throw IllegalStateException("Gemini returned an empty diagnostic response.")
            }
            fallbackText
        } catch (e: TimeoutCancellationException) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Test Gemini timed out", e)
            } else {
                Log.e(TAG, "Test Gemini timed out")
            }
            throw IllegalStateException(
                "Gemini connection timed out. Check internet access and Firebase App Check, then retry.",
                e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Test Gemini failed", e)
            } else {
                Log.e(TAG, "Test Gemini failed: ${e.javaClass.simpleName}")
            }
            throw e
        }
    }

    private suspend fun streamDiagnostic(
        model: com.google.firebase.ai.GenerativeModel,
        prompt: String,
        timeoutMs: Long
    ): String {
        val accumulated = StringBuilder()
        withTimeout(timeoutMs) {
            model.generateContentStream(prompt).collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) accumulated.append(piece)
            }
        }
        return accumulated.toString().trim()
    }
}
