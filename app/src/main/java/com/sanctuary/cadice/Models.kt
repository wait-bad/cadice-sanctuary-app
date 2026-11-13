package com.sanctuary.cadice

data class ConversationItem(
    val id: Int,
    val isGroup: Boolean,
    val name: String,
    val description: String,
    val lastMsg: String = "",
    val badge: String = if (isGroup) "群组" else "私聊"
)

data class ChatMessage(
    val mid: Long,
    val fromUid: Int,
    val fromName: String,
    val content: String,
    val createdAt: Long,
    val isSelf: Boolean
)
