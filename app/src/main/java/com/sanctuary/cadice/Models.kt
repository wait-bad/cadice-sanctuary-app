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
    val lastMid: Long = 0L,
    val unreadCount: Int = 0,
    val avatarUpdatedAt: Long = 0L,
    val isOnline: Boolean = false,
    val badge: String = if (isGroup) "群聊" else "好友",
    val draftText: String = ""
)

data class ChatMessage(
    val mid: Long,
    val fromUid: Int,
    val fromName: String,
    val fromAvatarUpdatedAt: Long = 0L,
    val content: String,
    val createdAt: Long,
    val isSelf: Boolean,
    val contentType: String = "text/plain",
    val syncStatus: Int = STATUS_SENT,
    val localId: Long = 0L,
    val replyToMid: Long = 0L,
    val replyPreview: String = "",
    val filePath: String = "",
    val localFileUri: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
) {
    val isImageMessage: Boolean
        get() = contentType == "vocechat/file" && (filePath.isNotBlank() || localFileUri.isNotBlank()) &&
            (content == "[图片]" || imageWidth > 0 || localFileUri.isNotBlank())

    companion object {
        const val STATUS_SENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_FAILED = 2
        const val TEMP_MID_BASE = 9_000_000_000_000_000L
    }
}

sealed class ChatListRow {
    abstract val stableId: String

    data class DateDivider(
        val dayKey: String,
        val label: String
    ) : ChatListRow() {
        override val stableId: String = "date_$dayKey"
    }

    data class MessageRow(
        val message: ChatMessage
    ) : ChatListRow() {
        override val stableId: String = "msg_${message.mid}"
    }
}

data class IncomingChatEvent(
    val mid: Long,
    val fromUid: Int,
    val isGroup: Boolean,
    val targetId: Int,
    val content: String,
    val contentType: String,
    val createdAt: Long,
    val isRealtime: Boolean = true,
    val replyToMid: Long = 0L,
    val filePath: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)
