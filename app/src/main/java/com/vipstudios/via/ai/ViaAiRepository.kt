package com.vipstudios.via.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.vipstudios.via.data.ChatMessage
import com.vipstudios.via.data.MemoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "ViaAiRepository"
private const val MODEL_NAME = "gemini-3.8-flash"

sealed interface AiStreamEvent {
    data class Chunk(val accumulatedText: String) : AiStreamEvent
    data class Completed(val fullText: String) : AiStreamEvent
}

/**
 * Connects VIA to Google Gemini via Firebase AI Logic (`Firebase.ai`) and App Check.
 * No client-side Gemini API key is needed or stored in the APK.
 */
class ViaAiRepository {

    private val defaultModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 700
            }
        )
    }

    private fun buildSystemInstruction(
        language: String,
        personality: String,
        memory: List<MemoryItem>,
        sheetsKnowledge: String?,
        sheetsStatusNote: String?
    ): Content {
        val languageName = when (language) {
            "kn" -> "Kannada (ಕನ್ನಡ). Reply naturally in Kannada script unless the user explicitly asks for another language."
            "hi" -> "Hindi (हिन्दी). Reply naturally in Hindi (Devanagari script) unless the user explicitly asks for another language."
            else -> "English."
        }
        val memorySummary = if (memory.isEmpty()) {
            "None saved yet."
        } else {
            memory.takeLast(12).joinToString("; ") { "[${it.kind}] ${it.text}" }
        }
        val systemText = buildString {
            appendLine("You are VIA (VIP Intelligent Assistant), a friendly, intelligent, futuristic personal AI assistant developed by VIP Studios And Creations.")
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
            appendLine("Guidelines: Be helpful, warm, direct, and easy to read aloud via text-to-speech. Keep answers concise (2-5 sentences) unless the user asks for detail. Avoid markdown asterisks or heavy formatting so speech output sounds natural.")
        }
        return content { text(systemText) }
    }

    private fun buildHistory(history: List<ChatMessage>): List<Content> {
        // Ensure alternating/valid conversation context from recent chat messages
        return history.takeLast(10)
            .filter { it.text.isNotBlank() }
            .map { msg ->
                content(if (msg.fromUser) "user" else "model") {
                    text(msg.text)
                }
            }
    }

    /** Streaming multi-turn Gemini chat response via Firebase AI Logic `startChat` + `sendMessageStream`. */
    fun streamReply(
        prompt: String,
        language: String,
        personality: String,
        history: List<ChatMessage>,
        memory: List<MemoryItem>,
        sheetsKnowledge: String? = null,
        sheetsStatusNote: String? = null
    ): Flow<AiStreamEvent> = flow {
        val model = Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 700
            },
            systemInstruction = buildSystemInstruction(
                language = language,
                personality = personality,
                memory = memory,
                sheetsKnowledge = sheetsKnowledge,
                sheetsStatusNote = sheetsStatusNote
            )
        )
        val chat = model.startChat(history = buildHistory(history))

        val accumulated = StringBuilder()
        try {
            withTimeout(60_000L) {
                chat.sendMessageStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(AiStreamEvent.Chunk(accumulated.toString()))
                    }
                }
            }
            val finalText = accumulated.toString().trim()
            if (finalText.isBlank()) {
                throw IllegalStateException("VIA received an empty response from Gemini.")
            }
            emit(AiStreamEvent.Completed(finalText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI generation timed out", e)
            throw IllegalStateException("Gemini timed out after 60 seconds. Please check your connection and try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI generation failed", e)
            throw e
        }
    }

    /** Direct single-turn Gemini connectivity check for the "Test Gemini" diagnostic action. */
    suspend fun testConnection(language: String): String {
        val prompt = when (language) {
            "kn" -> "Reply in 1 short Kannada sentence confirming VIA is connected to Gemini."
            "hi" -> "Reply in 1 short Hindi sentence confirming VIA is connected to Gemini."
            else -> "Reply in 1 short sentence confirming VIA is connected to Gemini and ready."
        }
        return try {
            val response = withTimeout(30_000L) {
                defaultModel.generateContent(prompt)
            }
            val text = response.text?.trim()
            if (text.isNullOrBlank()) {
                throw IllegalStateException("Gemini returned an empty diagnostic response.")
            }
            text
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Test Gemini timed out", e)
            throw IllegalStateException("Gemini diagnostic timed out after 30 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Test Gemini failed", e)
            throw e
        }
    }
}
