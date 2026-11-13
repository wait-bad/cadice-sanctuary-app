package com.sanctuary.cadice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 🗄️ 账号级永久离线 SQLite 数据库（位于应用私有 databases/ 目录，永不被系统清缓存回收）
 * - 按 (server_url, owner_uid) 严格隔离不同服务器、不同账号的离线聊天记录、会话快照与草稿
 * - v2 新增：messages.sync_status / local_id（支持乐观发送状态机）+ drafts 表（支持跨重启草稿持久化）
 */
class SanctuaryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "violet_sanctuary.db"
        private const val DB_VERSION = 2

        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_CONVERSATIONS = "conversations"
        private const val TABLE_DRAFTS = "drafts"

        @Volatile
        private var instance: SanctuaryDb? = null

        fun getInstance(context: Context): SanctuaryDb {
            return instance ?: synchronized(this) {
                instance ?: SanctuaryDb(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
                account_key TEXT NOT NULL,
                is_group INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                mid INTEGER NOT NULL,
                from_uid INTEGER NOT NULL,
                from_name TEXT NOT NULL,
                from_avatar_ts INTEGER NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                content_type TEXT NOT NULL,
                sync_status INTEGER NOT NULL DEFAULT 1,
                local_id INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (account_key, is_group, target_id, mid)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_msg_conv_mid ON $TABLE_MESSAGES(account_key, is_group, target_id, mid)"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                account_key TEXT NOT NULL,
                is_group INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                last_msg TEXT NOT NULL,
                last_time INTEGER NOT NULL,
                last_mid INTEGER NOT NULL,
                unread_count INTEGER NOT NULL,
                avatar_ts INTEGER NOT NULL,
                badge TEXT NOT NULL,
                PRIMARY KEY (account_key, is_group, target_id)
            )
            """.trimIndent()
        )

        createDraftsTable(db)
    }

    private fun createDraftsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DRAFTS (
                account_key TEXT NOT NULL,
                is_group INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                content TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (account_key, is_group, target_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 平滑升级保留全部旧离线消息
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN sync_status INTEGER NOT NULL DEFAULT 1")
            }
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN local_id INTEGER NOT NULL DEFAULT 0")
            }
            createDraftsTable(db)
        }
    }

    fun accountKey(serverUrl: String, myUid: Int): String {
        val norm = serverUrl.trim().removeSuffix("/").lowercase()
        return "${norm}#$myUid"
    }

    // ==================== ✉️ 消息持久化 & 乐观发送原子替换 ====================

    fun upsertMessages(
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        messages: List<ChatMessage>
    ) {
        if (messages.isEmpty() || myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (m in messages) {
                cv.clear()
                cv.put("account_key", key)
                cv.put("is_group", grp)
                cv.put("target_id", targetId)
                cv.put("mid", m.mid)
                cv.put("from_uid", m.fromUid)
                cv.put("from_name", m.fromName)
                cv.put("from_avatar_ts", m.fromAvatarUpdatedAt)
                cv.put("content", m.content)
                cv.put("created_at", m.createdAt)
                cv.put("content_type", m.contentType)
                cv.put("sync_status", m.syncStatus)
                cv.put("local_id", m.localId)
                db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertSingleMessage(
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        msg: ChatMessage
    ) {
        upsertMessages(serverUrl, myUid, isGroup, targetId, listOf(msg))
    }

    fun promotePendingMessage(
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        tempMid: Long,
        realMsg: ChatMessage
    ) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                TABLE_MESSAGES,
                "account_key = ? AND is_group = ? AND target_id = ? AND mid = ?",
                arrayOf(key, grp.toString(), targetId.toString(), tempMid.toString())
            )
            val cv = ContentValues().apply {
                put("account_key", key)
                put("is_group", grp)
                put("target_id", targetId)
                put("mid", realMsg.mid)
                put("from_uid", realMsg.fromUid)
                put("from_name", realMsg.fromName)
                put("from_avatar_ts", realMsg.fromAvatarUpdatedAt)
                put("content", realMsg.content)
                put("created_at", realMsg.createdAt)
                put("content_type", realMsg.contentType)
                put("sync_status", ChatMessage.STATUS_SENT)
                put("local_id", realMsg.localId)
            }
            db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateMessageSyncStatus(
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        mid: Long,
        syncStatus: Int
    ) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        val cv = ContentValues().apply { put("sync_status", syncStatus) }
        writableDatabase.update(
            TABLE_MESSAGES,
            cv,
            "account_key = ? AND is_group = ? AND target_id = ? AND mid = ?",
            arrayOf(key, grp.toString(), targetId.toString(), mid.toString())
        )
    }

    fun getMessagesPage(
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        beforeMid: Long = Long.MAX_VALUE,
        limit: Int = 50
    ): List<ChatMessage> {
        if (myUid <= 0) return emptyList()
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        val result = ArrayList<ChatMessage>()
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT mid, from_uid, from_name, from_avatar_ts, content, created_at, content_type, sync_status, local_id
            FROM $TABLE_MESSAGES
            WHERE account_key = ? AND is_group = ? AND target_id = ? AND mid < ?
            ORDER BY mid DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(key, grp.toString(), targetId.toString(), beforeMid.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val fromUid = c.getInt(1)
                val cachedName = c.getString(2)
                val resolvedName = if (fromUid == myUid) cachedName else {
                    SanctuaryCore.userMap[fromUid]?.name?.takeIf { it.isNotBlank() } ?: cachedName
                }
                val resolvedAv = SanctuaryCore.getAvatarUpdatedAt(fromUid).takeIf { it > 0L } ?: c.getLong(3)
                // 若上次 App 在 STATUS_SENDING 期间被强杀，重启后自动标为 FAILED 方便点击重发
                val rawStatus = c.getInt(7)
                val restoredStatus = if (rawStatus == ChatMessage.STATUS_SENDING) {
                    ChatMessage.STATUS_FAILED
                } else {
                    rawStatus
                }
                result.add(
                    ChatMessage(
                        mid = c.getLong(0),
                        fromUid = fromUid,
                        fromName = resolvedName.ifBlank { "用户 #$fromUid" },
                        fromAvatarUpdatedAt = resolvedAv,
                        content = c.getString(4),
                        createdAt = c.getLong(5),
                        isSelf = (fromUid == myUid),
                        contentType = c.getString(6),
                        syncStatus = restoredStatus,
                        localId = c.getLong(8)
                    )
                )
            }
        }
        result.reverse()
        return result
    }

    fun getMaxMidForConversation(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int): Long {
        if (myUid <= 0) return 0L
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        readableDatabase.rawQuery(
            "SELECT MAX(mid) FROM $TABLE_MESSAGES WHERE account_key = ? AND is_group = ? AND target_id = ? AND sync_status = ?",
            arrayOf(key, grp.toString(), targetId.toString(), ChatMessage.STATUS_SENT.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun deleteMessage(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int, mid: Long) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        writableDatabase.delete(
            TABLE_MESSAGES,
            "account_key = ? AND is_group = ? AND target_id = ? AND mid = ?",
            arrayOf(key, grp.toString(), targetId.toString(), mid.toString())
        )
    }

    fun clearConversationMessages(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        writableDatabase.delete(
            TABLE_MESSAGES,
            "account_key = ? AND is_group = ? AND target_id = ?",
            arrayOf(key, grp.toString(), targetId.toString())
        )
    }

    // ==================== 📝 跨重启草稿持久化 ====================

    fun saveDraft(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int, content: String) {
        if (myUid <= 0) return
        if (content.isBlank()) {
            deleteDraft(serverUrl, myUid, isGroup, targetId)
            return
        }
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        val cv = ContentValues().apply {
            put("account_key", key)
            put("is_group", grp)
            put("target_id", targetId)
            put("content", content)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_DRAFTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDraft(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int): String {
        if (myUid <= 0) return ""
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        readableDatabase.rawQuery(
            "SELECT content FROM $TABLE_DRAFTS WHERE account_key = ? AND is_group = ? AND target_id = ?",
            arrayOf(key, grp.toString(), targetId.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else ""
        }
    }

    fun getAllDrafts(serverUrl: String, myUid: Int): Map<Pair<Boolean, Int>, String> {
        if (myUid <= 0) return emptyMap()
        val key = accountKey(serverUrl, myUid)
        val map = HashMap<Pair<Boolean, Int>, String>()
        readableDatabase.rawQuery(
            "SELECT is_group, target_id, content FROM $TABLE_DRAFTS WHERE account_key = ?",
            arrayOf(key)
        ).use { c ->
            while (c.moveToNext()) {
                val isGroup = c.getInt(0) == 1
                val targetId = c.getInt(1)
                val content = c.getString(2)
                if (content.isNotBlank()) {
                    map[Pair(isGroup, targetId)] = content
                }
            }
        }
        return map
    }

    fun deleteDraft(serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val grp = if (isGroup) 1 else 0
        writableDatabase.delete(
            TABLE_DRAFTS,
            "account_key = ? AND is_group = ? AND target_id = ?",
            arrayOf(key, grp.toString(), targetId.toString())
        )
    }

    // ==================== 📋 会话列表快照 ====================

    fun saveConversations(serverUrl: String, myUid: Int, items: List<ConversationItem>) {
        if (myUid <= 0) return
        val key = accountKey(serverUrl, myUid)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_CONVERSATIONS, "account_key = ?", arrayOf(key))
            val cv = ContentValues()
            for (item in items) {
                cv.clear()
                cv.put("account_key", key)
                cv.put("is_group", if (item.isGroup) 1 else 0)
                cv.put("target_id", item.id)
                cv.put("name", item.name)
                cv.put("description", item.description)
                cv.put("last_msg", item.lastMsg)
                cv.put("last_time", item.lastTime)
                cv.put("last_mid", item.lastMid)
                cv.put("unread_count", item.unreadCount)
                cv.put("avatar_ts", item.avatarUpdatedAt)
                cv.put("badge", item.badge)
                db.insert(TABLE_CONVERSATIONS, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadConversations(serverUrl: String, myUid: Int): List<ConversationItem> {
        if (myUid <= 0) return emptyList()
        val key = accountKey(serverUrl, myUid)
        val drafts = getAllDrafts(serverUrl, myUid)
        val list = mutableListOf<ConversationItem>()
        readableDatabase.rawQuery(
            """
            SELECT is_group, target_id, name, description, last_msg, last_time, last_mid, unread_count, avatar_ts, badge
            FROM $TABLE_CONVERSATIONS
            WHERE account_key = ?
            ORDER BY last_time DESC
            """.trimIndent(),
            arrayOf(key)
        ).use { c ->
            while (c.moveToNext()) {
                val isGroup = c.getInt(0) == 1
                val id = c.getInt(1)
                list.add(
                    ConversationItem(
                        id = id,
                        isGroup = isGroup,
                        name = c.getString(2),
                        description = c.getString(3),
                        lastMsg = c.getString(4),
                        lastTime = c.getLong(5),
                        lastMid = c.getLong(6),
                        unreadCount = c.getInt(7),
                        avatarUpdatedAt = c.getLong(8),
                        isOnline = if (isGroup) false else SanctuaryCore.isUserOnline(id),
                        badge = c.getString(9),
                        draftText = drafts[Pair(isGroup, id)] ?: ""
                    )
                )
            }
        }
        return list
    }
}
