package com.vipstudios.via

import com.vipstudios.via.ai.ViaAiRepository
import com.vipstudios.via.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ViaAiHistoryTest {

    @Test
    fun buildHistory_normalizesLeadingAndConsecutiveRoles() {
        val repo = ViaAiRepository()
        val raw = listOf(
            ChatMessage(1L, fromUser = false, text = "Welcome message", time = 1L),
            ChatMessage(2L, fromUser = true, text = "Hello", time = 2L),
            ChatMessage(3L, fromUser = true, text = "Are you there?", time = 3L),
            ChatMessage(4L, fromUser = false, text = "Yes, I am here.", time = 4L),
            ChatMessage(5L, fromUser = false, text = "How can I help?", time = 5L),
            ChatMessage(6L, fromUser = true, text = "Orphan trailing user", time = 6L)
        )

        val contents = repo.buildHistory(raw)
        assertEquals(2, contents.size)
        assertEquals("user", contents[0].role)
        assertEquals("model", contents[1].role)
    }
}
