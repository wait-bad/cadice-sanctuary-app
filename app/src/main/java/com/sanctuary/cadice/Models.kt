package com.sanctuary.cadice

data class UserProfile(
    val uid: Int,
    val name: String,
    val email: String = "",
    val avatarUpdatedAt: Long = 0L,
    val isAdmin: Boolean = false,
    val isBot: Boolean = false,
    val isOnline: Boolean = false
)

data class ConversationItem(
    val id: Int,
    val isGroup: Boolean,
    val name: String,
    val description: String,
    val lastMsg: String = "",
    val lastTime: Long = 0L,
    val unreadCount: Int = 0,
    val avatarUpdatedAt: Long = 0L,
    val isOnline: Boolean = false,
    val badge: String = if (isGroup) "群聊" else "好友"
)

data class ChatMessage(
    val mid: Long,
    val fromUid: Int,
    val fromName: String,
    val fromAvatarUpdatedAt: Long = 0L,
    val content: String,
    val createdAt: Long,
    val isSelf: Boolean,
    val contentType: String = "text/plain"
)

data class IncomingChatEvent(
    val mid: Long,
    val fromUid: Int,
    val isGroup: Boolean,
    val targetId: Int, // 若 isGroup=true 为 gid；若 isGroup=false 为对方 uid
    val content: String,
    val contentType: String,
    val createdAt: Long
)
