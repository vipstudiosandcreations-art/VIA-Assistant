package com.vipstudios.via.data

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val time: Long)

/** kind is one of: "memory", "note", "task" */
data class MemoryItem(val id: Long, val text: String, val kind: String, val time: Long)

data class CommandLog(val id: Long, val text: String, val result: String, val time: Long)
