package com.sanctuary.cadice

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SanctuaryCore {

    const val PREFS_NAME = "cadice_sanctuary_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_TOKEN = "token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_TOKEN_OBTAINED_AT = "token_obtained_at"
    const val KEY_TOKEN_EXPIRED_IN = "token_expired_in"
    const val KEY_USER_NAME = "user_name"
    const val KEY_USER_UID = "user_uid"
    const val KEY_IS_ADMIN = "is_admin"
    const val KEY_DARK_THEME = "dark_theme"
    const val KEY_FONT_SCALE = "font_scale"

    // 限制单主机最大并发连接数为 4，防止手机打开十几个请求把 Cloudflare 隧道挤爆超时
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 4
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "VioletIM/1.5 (Android; Mobile)")
                .build()
            chain.proceed(req)
        }
        .build()

    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "VioletIM/1.5 (Android; Mobile)")
                .build()
            chain.proceed(req)
        }
        .build()

    val userMap = ConcurrentHashMap<Int, UserProfile>()
    val onlineMap = ConcurrentHashMap<Int, Boolean>()

    private val avatarMemoryCache = LruCache<String, Bitmap>(100)
    private val inFlightAvatars = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val renewMutex = Mutex()
    private val previewSemaphore = Semaphore(3)
    private val avatarSemaphore = Semaphore(2)

    @Volatile
    var activeToken: String = ""

    private val _incomingMessages = MutableSharedFlow<IncomingChatEvent>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _userStateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val userStateEvents = _userStateEvents.asSharedFlow()

    private val _authExpiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val authExpiredEvents = _authExpiredEvents.asSharedFlow()

    private var sseJob: Job? = null
    private val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getDisplayName(uid: Int): String {
        return userMap[uid]?.name?.takeIf { it.isNotBlank() } ?: "用户 #$uid"
    }

    fun getAvatarUpdatedAt(uid: Int): Long {
        return userMap[uid]?.avatarUpdatedAt ?: 0L
    }

    fun isUserOnline(uid: Int): Boolean {
        return onlineMap[uid] == true || userMap[uid]?.isOnline == true
    }

    fun getFontScale(prefs: SharedPreferences): Float {
        return prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(0.85f, 1.35f)
    }

    fun clearSessionCache(context: Context? = null) {
        stopSse()
        activeToken = ""
        userMap.clear()
        onlineMap.clear()
        inFlightAvatars.clear()
        // 注意：故意保留 SQLite 永久离线库 (SanctuaryDb) 与本地消息记录，退出账号再登录依然完整保留！
    }

    // ==================== 🔖 持久化游标（彻底根除反复弹出未读 & 重复全量拉取） ====================

    private fun readMidKey(myUid: Int, isGroup: Boolean, targetId: Int): String {
        return "read_mid_${myUid}_${if (isGroup) "g" else "u"}_$targetId"
    }

    fun getLastReadMid(context: Context, myUid: Int, isGroup: Boolean, targetId: Int): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(readMidKey(myUid, isGroup, targetId), 0L)
    }

    fun setLastReadMidLocal(context: Context, myUid: Int, isGroup: Boolean, targetId: Int, mid: Long) {
        if (mid <= 0L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = readMidKey(myUid, isGroup, targetId)
        val old = prefs.getLong(key, 0L)
        if (mid > old) {
            prefs.edit().putLong(key, mid).apply()
        }
    }

    // 标记会话已读：本地持久化 + 异步同步到 VoceChat 服务器 /api/user/read-index
    fun markConversationRead(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        mid: Long
    ) {
        if (mid <= 0L) return
        setLastReadMidLocal(context, myUid, isGroup, targetId, mid)
        val appCtx = context.applicationContext
        coreScope.launch {
            try {
                val payload = JSONObject().apply {
                    if (isGroup) {
                        put("groups", JSONArray().put(JSONObject().apply {
                            put("gid", targetId)
                            put("mid", mid)
                        }))
                        put("users", JSONArray())
                    } else {
                        put("groups", JSONArray())
                        put("users", JSONArray().put(JSONObject().apply {
                            put("uid", targetId)
                            put("mid", mid)
                        }))
                    }
                }
                executeAuthorized(appCtx) { tk ->
                    Request.Builder()
                        .url("$serverUrl/api/user/read-index")
                        .header("X-API-Key", tk)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun getGlobalMaxMid(context: Context, myUid: Int): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong("global_max_mid_$myUid", 0L)
    }

    fun updateGlobalMaxMid(context: Context, myUid: Int, mid: Long) {
        if (mid <= 0L || mid >= ChatMessage.TEMP_MID_BASE) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "global_max_mid_$myUid"
        if (mid > prefs.getLong(key, 0L)) {
            prefs.edit().putLong(key, mid).apply()
        }
    }

    // ==================== 💾 永久离线 SQLite 聊天记录存储（按服务器 + 账号 UID 严格隔离） ====================

    fun loadLocalChatPage(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        beforeMid: Long = Long.MAX_VALUE,
        limit: Int = 50
    ): List<ChatMessage> {
        return SanctuaryDb.getInstance(context).getMessagesPage(serverUrl, myUid, isGroup, targetId, beforeMid, limit)
    }

    fun getLocalConversationMaxMid(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int
    ): Long {
        return SanctuaryDb.getInstance(context).getMaxMidForConversation(serverUrl, myUid, isGroup, targetId)
    }

    fun saveLocalChatMessages(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        messages: List<ChatMessage>
    ) {
        if (messages.isEmpty()) return
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).upsertMessages(serverUrl, myUid, isGroup, targetId, messages)
        }
    }

    fun deleteLocalChatMessage(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        mid: Long
    ) {
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).deleteMessage(serverUrl, myUid, isGroup, targetId, mid)
        }
    }

    fun promotePendingMessage(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        tempMid: Long,
        realMsg: ChatMessage
    ) {
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).promotePendingMessage(serverUrl, myUid, isGroup, targetId, tempMid, realMsg)
        }
    }

    fun updateLocalMessageStatus(
        context: Context,
        serverUrl: String,
        myUid: Int,
        isGroup: Boolean,
        targetId: Int,
        mid: Long,
        syncStatus: Int
    ) {
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).updateMessageSyncStatus(serverUrl, myUid, isGroup, targetId, mid, syncStatus)
        }
    }

    fun clearLocalChatHistory(context: Context, serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int) {
        SanctuaryDb.getInstance(context).clearConversationMessages(serverUrl, myUid, isGroup, targetId)
    }

    fun saveDraft(context: Context, serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int, content: String) {
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).saveDraft(serverUrl, myUid, isGroup, targetId, content)
        }
    }

    fun getDraft(context: Context, serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int): String {
        return SanctuaryDb.getInstance(context).getDraft(serverUrl, myUid, isGroup, targetId)
    }

    fun getAllDrafts(context: Context, serverUrl: String, myUid: Int): Map<Pair<Boolean, Int>, String> {
        return SanctuaryDb.getInstance(context).getAllDrafts(serverUrl, myUid)
    }

    fun deleteDraft(context: Context, serverUrl: String, myUid: Int, isGroup: Boolean, targetId: Int) {
        val appCtx = context.applicationContext
        coreScope.launch {
            SanctuaryDb.getInstance(appCtx).deleteDraft(serverUrl, myUid, isGroup, targetId)
        }
    }

    // 🗑️ 向服务器请求删除单条消息 (DELETE /api/message/{mid})
    suspend fun deleteServerMessage(
        context: Context,
        serverUrl: String,
        mid: Long
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = executeAuthorized(context) { tk ->
                Request.Builder()
                    .url("$serverUrl/api/message/$mid")
                    .header("X-API-Key", tk)
                    .delete()
                    .build()
            }
            when {
                code in 200..299 -> Pair(true, "已从服务器删除该消息")
                code == 403 -> Pair(false, "无权删除他人消息 (HTTP 403)")
                code == 404 -> Pair(true, "消息已在服务器移除")
                else -> Pair(false, "删除失败 (HTTP $code) $body")
            }
        } catch (e: Exception) {
            Pair(false, "删除异常: ${e.localizedMessage}")
        }
    }

    // ==================== 🔐 Token 5分钟自动续期与请求封装 ====================

    suspend fun ensureValidToken(context: Context, forceRenew: Boolean = false): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
        val currentToken = prefs.getString(KEY_TOKEN, "") ?: ""
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        val obtainedAt = prefs.getLong(KEY_TOKEN_OBTAINED_AT, 0L)
        val expiredInSec = prefs.getLong(KEY_TOKEN_EXPIRED_IN, 300L)

        if (currentToken.isEmpty() || serverUrl.isEmpty()) return null
        activeToken = currentToken

        val now = System.currentTimeMillis()
        val needsRenew = forceRenew || obtainedAt == 0L || (now - obtainedAt) >= ((expiredInSec - 60).coerceAtLeast(30) * 1000L)
        if (!needsRenew) {
            return currentToken
        }

        if (refreshToken.isEmpty()) {
            if (forceRenew) {
                _authExpiredEvents.tryEmit(Unit)
                return null
            }
            return currentToken
        }

        return renewMutex.withLock {
            val latestToken = prefs.getString(KEY_TOKEN, "") ?: ""
            val latestObtained = prefs.getLong(KEY_TOKEN_OBTAINED_AT, 0L)
            val latestExp = prefs.getLong(KEY_TOKEN_EXPIRED_IN, 300L)
            if (!forceRenew && latestToken.isNotEmpty() && latestObtained > 0L &&
                (System.currentTimeMillis() - latestObtained) < ((latestExp - 60).coerceAtLeast(30) * 1000L)
            ) {
                activeToken = latestToken
                return@withLock latestToken
            }

            withContext(Dispatchers.IO) {
                try {
                    val payload = JSONObject().apply {
                        put("token", prefs.getString(KEY_TOKEN, "") ?: "")
                        put("refresh_token", prefs.getString(KEY_REFRESH_TOKEN, "") ?: "")
                    }
                    val req = Request.Builder()
                        .url("$serverUrl/api/token/renew")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { res ->
                        val body = res.body?.string().orEmpty()
                        if (res.isSuccessful && body.startsWith("{")) {
                            val json = JSONObject(body)
                            val newToken = json.getString("token")
                            val newRefresh = json.optString("refresh_token", refreshToken)
                            val newExpiredIn = json.optLong("expired_in", 300L)
                            prefs.edit()
                                .putString(KEY_TOKEN, newToken)
                                .putString(KEY_REFRESH_TOKEN, newRefresh)
                                .putLong(KEY_TOKEN_OBTAINED_AT, System.currentTimeMillis())
                                .putLong(KEY_TOKEN_EXPIRED_IN, newExpiredIn)
                                .apply()
                            activeToken = newToken
                            return@withContext newToken
                        } else if (res.code == 401 || res.code == 403) {
                            _authExpiredEvents.tryEmit(Unit)
                            return@withContext null
                        }
                    }
                } catch (_: Exception) {
                }
                if (forceRenew) null else (prefs.getString(KEY_TOKEN, "") ?: currentToken)
            }
        }
    }

    suspend fun executeAuthorized(context: Context, requestBuilder: (token: String) -> Request): Pair<Int, String> {
        val token1 = ensureValidToken(context, forceRenew = false) ?: return Pair(401, "")
        val firstAttempt = withContext(Dispatchers.IO) {
            httpClient.newCall(requestBuilder(token1)).execute().use { res ->
                Pair(res.code, res.body?.string().orEmpty())
            }
        }
        if (firstAttempt.first != 401) {
            return firstAttempt
        }
        val token2 = ensureValidToken(context, forceRenew = true) ?: return Pair(401, "")
        return withContext(Dispatchers.IO) {
            httpClient.newCall(requestBuilder(token2)).execute().use { res ->
                Pair(res.code, res.body?.string().orEmpty())
            }
        }
    }

    // ==================== 💾 首页列表快照 ====================

    fun loadHomeSnapshot(context: Context, serverUrl: String, myUid: Int): Pair<List<ConversationItem>, List<ConversationItem>>? {
        return try {
            val dbItems = SanctuaryDb.getInstance(context).loadConversations(serverUrl, myUid)
            // 同步恢复用户资料快照以便离线显示昵称与头像时间戳
            val file = File(context.filesDir, "users_profile_${myUid}.json")
            if (file.exists()) {
                val usersArr = JSONArray(file.readText())
                for (i in 0 until usersArr.length()) {
                    putUserJson(usersArr.getJSONObject(i))
                }
            }
            if (dbItems.isEmpty()) null else Pair(dbItems, dbItems)
        } catch (_: Exception) {
            null
        }
    }

    fun saveHomeSnapshot(
        context: Context,
        serverUrl: String,
        myUid: Int,
        conversations: List<ConversationItem>
    ) {
        val convCopy = conversations.toList()
        val appCtx = context.applicationContext
        coreScope.launch {
            try {
                SanctuaryDb.getInstance(appCtx).saveConversations(serverUrl, myUid, convCopy)
                val usersArr = JSONArray()
                userMap.values.forEach { u ->
                    usersArr.put(JSONObject().apply {
                        put("uid", u.uid)
                        put("name", u.name)
                        put("email", u.email)
                        put("avatar_updated_at", u.avatarUpdatedAt)
                        put("is_admin", u.isAdmin)
                        put("is_bot", u.isBot)
                    })
                }
                File(appCtx.filesDir, "users_profile_${myUid}.json").writeText(usersArr.toString())
            } catch (_: Exception) {
            }
        }
    }

    private fun itemsToJson(list: List<ConversationItem>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("isGroup", item.isGroup)
                put("name", item.name)
                put("description", item.description)
                put("lastMsg", item.lastMsg)
                put("lastTime", item.lastTime)
                put("lastMid", item.lastMid)
                put("unreadCount", item.unreadCount)
                put("avatarUpdatedAt", item.avatarUpdatedAt)
                put("badge", item.badge)
            })
        }
        return arr
    }

    private fun jsonToItems(arr: JSONArray?): List<ConversationItem> {
        if (arr == null) return emptyList()
        val list = mutableListOf<ConversationItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val isGroup = o.optBoolean("isGroup", false)
            val id = o.getInt("id")
            list.add(
                ConversationItem(
                    id = id,
                    isGroup = isGroup,
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    lastMsg = o.optString("lastMsg", ""),
                    lastTime = o.optLong("lastTime", 0L),
                    lastMid = o.optLong("lastMid", 0L),
                    unreadCount = o.optInt("unreadCount", 0),
                    avatarUpdatedAt = o.optLong("avatarUpdatedAt", 0L),
                    isOnline = if (isGroup) false else isUserOnline(id),
                    badge = o.optString("badge", if (isGroup) "群聊" else "好友")
                )
            )
        }
        return list
    }

    // ==================== ⚡ 基础名录 & 增量摘要拉取 ====================

    suspend fun fetchBaseDirectory(
        context: Context,
        serverUrl: String,
        myUid: Int
    ): Pair<List<ConversationItem>, List<ConversationItem>> = withContext(Dispatchers.IO) {
        ensureValidToken(context, forceRenew = false)

        coroutineScope {
            val usersJob = async {
                try {
                    val (code, body) = executeAuthorized(context) { tk ->
                        Request.Builder()
                            .url("$serverUrl/api/user")
                            .header("X-API-Key", tk)
                            .get()
                            .build()
                    }
                    if (code in 200..299 && body.startsWith("[")) {
                        parseUsersArray(JSONArray(body))
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }

            val groupsJob = async {
                val list = mutableListOf<ConversationItem>()
                try {
                    val (code, body) = executeAuthorized(context) { tk ->
                        Request.Builder()
                            .url("$serverUrl/api/group")
                            .header("X-API-Key", tk)
                            .get()
                            .build()
                    }
                    if (code in 200..299 && body.startsWith("[")) {
                        val groups = JSONArray(body)
                        for (i in 0 until groups.length()) {
                            val g = groups.getJSONObject(i)
                            val gid = g.getInt("gid")
                            val name = g.optString("name", "群聊 #$gid").ifBlank { "群聊 #$gid" }
                            val desc = g.optString("description", "群聊频道").ifBlank { "群聊频道" }
                            val avatarUpdatedAt = g.optLong("avatar_updated_at", 0L)
                            list.add(
                                ConversationItem(
                                    id = gid,
                                    isGroup = true,
                                    name = name,
                                    description = desc,
                                    lastMsg = desc,
                                    avatarUpdatedAt = avatarUpdatedAt,
                                    badge = "群聊"
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }
                list
            }

            val contactsJob = async {
                val friendUids = HashSet<Int>()
                try {
                    val (code, body) = executeAuthorized(context) { tk ->
                        Request.Builder()
                            .url("$serverUrl/api/user/contacts")
                            .header("X-API-Key", tk)
                            .get()
                            .build()
                    }
                    if (code in 200..299 && body.startsWith("[")) {
                        val contacts = JSONArray(body)
                        for (i in 0 until contacts.length()) {
                            val c = contacts.getJSONObject(i)
                            val targetUid = c.optInt("target_uid", -1)
                            if (targetUid == -1 || targetUid == myUid) continue
                            friendUids.add(targetUid)
                            c.optJSONObject("target_info")?.let { putUserJson(it) }
                        }
                    }
                } catch (_: Exception) {
                }
                friendUids
            }

            usersJob.await()
            val groupItems = groupsJob.await()
            val friendUids = contactsJob.await()

            val memberItems = mutableListOf<ConversationItem>()
            val addedUids = HashSet<Int>()

            friendUids.sorted().forEach { uid ->
                addedUids.add(uid)
                val u = userMap[uid]
                val displayName = u?.name?.takeIf { it.isNotBlank() } ?: "用户 #$uid"
                memberItems.add(
                    ConversationItem(
                        id = uid,
                        isGroup = false,
                        name = displayName,
                        description = u?.email?.ifEmpty { "VoceChat 好友" } ?: "VoceChat 好友",
                        lastMsg = "点击发起私聊",
                        avatarUpdatedAt = u?.avatarUpdatedAt ?: 0L,
                        isOnline = isUserOnline(uid),
                        badge = "好友"
                    )
                )
            }

            userMap.values
                .filter { it.uid != myUid && !it.isBot && !addedUids.contains(it.uid) }
                .sortedBy { it.uid }
                .forEach { u ->
                    addedUids.add(u.uid)
                    val displayName = u.name.ifBlank { "用户 #${u.uid}" }
                    memberItems.add(
                        ConversationItem(
                            id = u.uid,
                            isGroup = false,
                            name = displayName,
                            description = u.email.ifEmpty { "成员 #${u.uid}" },
                            lastMsg = "点击发起私聊",
                            avatarUpdatedAt = u.avatarUpdatedAt,
                            isOnline = isUserOnline(u.uid),
                            badge = if (u.isAdmin) "管理" else "成员"
                        )
                    )
                }

            val allContacts = groupItems + memberItems
            Pair(allContacts, allContacts)
        }
    }

    suspend fun hydrateLatestPreviews(
        context: Context,
        serverUrl: String,
        myUid: Int,
        items: List<ConversationItem>
    ): List<ConversationItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { item ->
                async {
                    previewSemaphore.withPermit {
                        fetchLatestPreview(context, serverUrl, myUid, item)
                    }
                }
            }.awaitAll()
        }.sortedWith(
            compareByDescending<ConversationItem> { it.lastTime }
                .thenByDescending { it.isGroup }
                .thenByDescending { it.badge == "好友" }
                .thenBy { it.id }
        )
    }

    private suspend fun fetchLatestPreview(
        context: Context,
        serverUrl: String,
        myUid: Int,
        item: ConversationItem
    ): ConversationItem {
        return try {
            val url = if (item.isGroup) {
                "$serverUrl/api/group/${item.id}/history?limit=1"
            } else {
                "$serverUrl/api/user/${item.id}/history?limit=1"
            }
            val (code, body) = executeAuthorized(context) { tk ->
                Request.Builder()
                    .url(url)
                    .header("X-API-Key", tk)
                    .get()
                    .build()
            }
            if (code in 200..299 && body.startsWith("[")) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val lastObj = arr.getJSONObject(arr.length() - 1)
                    val mid = lastObj.optLong("mid", 0L)
                    val fromUid = lastObj.optInt("from_uid", -1)
                    val createdAt = normalizeTimestampMs(lastObj.optLong("created_at", 0L))
                    val detail = lastObj.optJSONObject("detail")
                    val previewText = parseMessagePreview(detail)
                    val formatted = if (item.isGroup && fromUid != -1) {
                        "${getDisplayName(fromUid)}: $previewText"
                    } else {
                        previewText
                    }
                    updateGlobalMaxMid(context, myUid, mid)

                    val lastRead = getLastReadMid(context, myUid, item.isGroup, item.id)
                    // 初次初始化若无本地游标且非本人刚发，默认把已有历史基准记为已读，避免首次进入满屏假红点
                    if (lastRead == 0L && mid > 0L) {
                        setLastReadMidLocal(context, myUid, item.isGroup, item.id, mid)
                    }
                    val finalRead = getLastReadMid(context, myUid, item.isGroup, item.id)
                    val unread = if (mid <= finalRead || fromUid == myUid) 0 else item.unreadCount.coerceAtLeast(1)

                    return item.copy(
                        lastMsg = formatted,
                        lastTime = createdAt,
                        lastMid = mid,
                        unreadCount = unread
                    )
                }
            }
            item
        } catch (_: Exception) {
            item
        }
    }

    // ==================== 🖼️ 头像上传与双层缓存 ====================

    suspend fun uploadUserAvatar(
        context: Context,
        serverUrl: String,
        myUid: Int,
        rawBytes: ByteArray
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val bmp = decodeSampledBitmap(rawBytes, 512, 512)
                ?: return@withContext Pair(false, "无法解码所选图片")
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 92, baos)
            val pngBytes = baos.toByteArray()

            val (code, body) = executeAuthorized(context) { tk ->
                Request.Builder()
                    .url("$serverUrl/api/user/avatar")
                    .header("X-API-Key", tk)
                    .post(pngBytes.toRequestBody("image/png".toMediaType()))
                    .build()
            }
            if (code in 200..299) {
                val newTs = System.currentTimeMillis()
                val old = userMap[myUid]
                if (old != null) {
                    userMap[myUid] = old.copy(avatarUpdatedAt = newTs)
                }
                val newKey = "u_${myUid}_$newTs"
                val smallBmp = decodeSampledBitmap(pngBytes, 144, 144) ?: bmp
                avatarMemoryCache.put(newKey, smallBmp)
                val avatarDir = File(context.cacheDir, "avatars").apply { if (!exists()) mkdirs() }
                FileOutputStream(File(avatarDir, "$newKey.webp")).use { out ->
                    smallBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                _userStateEvents.tryEmit(Unit)
                Pair(true, "头像更换成功")
            } else {
                Pair(false, "上传失败 (HTTP $code) $body")
            }
        } catch (e: Exception) {
            Pair(false, "上传异常: ${e.localizedMessage}")
        }
    }

    // ==================== 🔌 增量 SSE 实时事件流 (带 after_mid 与 ready 识别) ====================

    fun startSse(context: Context, serverUrl: String, myUid: Int) {
        val appContext = context.applicationContext
        sseJob?.cancel()
        sseJob = sseScope.launch {
            var backoffMs = 2000L
            while (isActive) {
                try {
                    val freshToken = ensureValidToken(appContext, forceRenew = false)
                    if (freshToken.isNullOrEmpty()) {
                        delay(5000L)
                        continue
                    }

                    // 关键升级：携带本地已同步到的 globalMaxMid，防止每次重连重复重放历史消息！
                    val afterMid = getGlobalMaxMid(appContext, myUid)
                    val sseUrl = if (afterMid > 0L) {
                        "$serverUrl/api/user/events?api-key=$freshToken&after_mid=$afterMid"
                    } else {
                        "$serverUrl/api/user/events?api-key=$freshToken"
                    }

                    val req = Request.Builder()
                        .url(sseUrl)
                        .header("Accept", "text/event-stream")
                        .get()
                        .build()

                    var sseReady = false
                    sseClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            backoffMs = 2000L
                            val source = response.body?.source()
                            while (isActive && source != null && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    val jsonText = line.removePrefix("data:").trim()
                                    if (jsonText.startsWith("{")) {
                                        val becameReady = handleSseJson(appContext, serverUrl, jsonText, myUid, sseReady)
                                        if (becameReady) sseReady = true
                                    }
                                }
                            }
                        } else if (response.code == 401 || response.code == 403) {
                            ensureValidToken(appContext, forceRenew = true)
                        }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (_: Exception) {
                }

                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15000L)
            }
        }
    }

    fun stopSse() {
        sseJob?.cancel()
        sseJob = null
    }

    private fun handleSseJson(context: Context, serverUrl: String, raw: String, myUid: Int, sseReady: Boolean): Boolean {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("type")) {
                "ready" -> return true
                "users_snapshot" -> {
                    obj.optJSONArray("users")?.let {
                        parseUsersArray(it)
                        _userStateEvents.tryEmit(Unit)
                    }
                }
                "users_state" -> {
                    val users = obj.optJSONArray("users")
                    if (users != null) {
                        for (i in 0 until users.length()) {
                            val u = users.getJSONObject(i)
                            val uid = u.optInt("uid", -1)
                            val online = u.optBoolean("online", false)
                            if (uid != -1) {
                                onlineMap[uid] = online
                                userMap[uid]?.let { userMap[uid] = it.copy(isOnline = online) }
                            }
                        }
                        _userStateEvents.tryEmit(Unit)
                    }
                }
                "user_settings" -> {
                    // 同步服务端保存的已读游标到本地！
                    obj.optJSONArray("read_index_groups")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val gid = item.optInt("gid", -1)
                            val mid = item.optLong("mid", 0L)
                            if (gid != -1 && mid > 0L) {
                                setLastReadMidLocal(context, myUid, true, gid, mid)
                            }
                        }
                    }
                    obj.optJSONArray("read_index_users")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val uid = item.optInt("uid", -1)
                            val mid = item.optLong("mid", 0L)
                            if (uid != -1 && mid > 0L) {
                                setLastReadMidLocal(context, myUid, false, uid, mid)
                            }
                        }
                    }
                    _userStateEvents.tryEmit(Unit)
                }
                "chat" -> {
                    val mid = obj.optLong("mid", 0L)
                    val fromUid = obj.optInt("from_uid", -1)
                    val createdAt = normalizeTimestampMs(obj.optLong("created_at", System.currentTimeMillis()))
                    val detail = obj.optJSONObject("detail")
                    val contentType = detail?.optString("content_type", "text/plain") ?: "text/plain"
                    val content = parseMessagePreview(detail)
                    val target = obj.optJSONObject("target") ?: return false

                    if (mid <= 0L || fromUid == -1 || content.isBlank()) return false
                    updateGlobalMaxMid(context, myUid, mid)

                    if (target.has("gid")) {
                        val gid = target.getInt("gid")
                        val senderName = if (fromUid == myUid) "我" else getDisplayName(fromUid)
                        SanctuaryDb.getInstance(context).upsertSingleMessage(
                            serverUrl, myUid, true, gid,
                            ChatMessage(mid, fromUid, senderName, getAvatarUpdatedAt(fromUid), content, createdAt, fromUid == myUid, contentType)
                        )
                        _incomingMessages.tryEmit(
                            IncomingChatEvent(
                                mid = mid,
                                fromUid = fromUid,
                                isGroup = true,
                                targetId = gid,
                                content = content,
                                contentType = contentType,
                                createdAt = createdAt,
                                isRealtime = sseReady
                            )
                        )
                    } else if (target.has("uid")) {
                        val targetUid = target.getInt("uid")
                        val peerUid = if (fromUid == myUid) targetUid else fromUid
                        val senderName = if (fromUid == myUid) "我" else getDisplayName(fromUid)
                        SanctuaryDb.getInstance(context).upsertSingleMessage(
                            serverUrl, myUid, false, peerUid,
                            ChatMessage(mid, fromUid, senderName, getAvatarUpdatedAt(fromUid), content, createdAt, fromUid == myUid, contentType)
                        )
                        _incomingMessages.tryEmit(
                            IncomingChatEvent(
                                mid = mid,
                                fromUid = fromUid,
                                isGroup = false,
                                targetId = peerUid,
                                content = content,
                                contentType = contentType,
                                createdAt = createdAt,
                                isRealtime = sseReady
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    private fun parseUsersArray(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            putUserJson(arr.getJSONObject(i))
        }
    }

    private fun putUserJson(u: JSONObject) {
        val uid = u.optInt("uid", -1)
        if (uid == -1) return
        val rawName = u.optString("name", "").trim()
        val name = if (rawName.isNotEmpty()) rawName else "用户 #$uid"
        val email = u.optString("email", "")
        val avatarUpdatedAt = u.optLong("avatar_updated_at", 0L)
        val isAdmin = u.optBoolean("is_admin", false)
        val isBot = u.optBoolean("is_bot", false)
        val online = onlineMap[uid] ?: false
        userMap[uid] = UserProfile(
            uid = uid,
            name = name,
            email = email,
            avatarUpdatedAt = avatarUpdatedAt,
            isAdmin = isAdmin,
            isBot = isBot,
            isOnline = online
        )
    }

    fun prewarmAvatars(context: Context, serverUrl: String) {
        val appCtx = context.applicationContext
        userMap.values.filter { it.avatarUpdatedAt > 0L }.forEach { u ->
            val cacheKey = "u_${u.uid}_${u.avatarUpdatedAt}"
            if (avatarMemoryCache.get(cacheKey) == null) {
                coreScope.launch {
                    fetchOrLoadAvatarBitmap(appCtx, serverUrl, false, u.uid, cacheKey)
                }
            }
        }
    }

    fun loadAvatar(
        context: Context,
        scope: CoroutineScope,
        serverUrl: String,
        token: String,
        isGroup: Boolean,
        id: Int,
        avatarUpdatedAt: Long,
        fallbackText: String,
        ivAvatar: ImageView,
        tvFallback: TextView
    ) {
        if (token.isNotEmpty() && activeToken.isEmpty()) {
            activeToken = token
        }
        val resolvedUpdatedAt = if (!isGroup && avatarUpdatedAt == 0L) {
            getAvatarUpdatedAt(id)
        } else {
            avatarUpdatedAt
        }

        tvFallback.text = fallbackText.ifBlank { "?" }
        tvFallback.visibility = View.VISIBLE
        ivAvatar.visibility = View.GONE

        if (resolvedUpdatedAt <= 0L || serverUrl.isBlank()) {
            ivAvatar.tag = null
            return
        }

        val cacheKey = "${if (isGroup) "g" else "u"}_${id}_$resolvedUpdatedAt"
        ivAvatar.tag = cacheKey

        avatarMemoryCache.get(cacheKey)?.let { bmp ->
            ivAvatar.setImageBitmap(bmp)
            ivAvatar.visibility = View.VISIBLE
            tvFallback.visibility = View.GONE
            return
        }

        scope.launch {
            val bitmap = fetchOrLoadAvatarBitmap(context.applicationContext, serverUrl, isGroup, id, cacheKey)
            if (bitmap != null) {
                withContext(Dispatchers.Main) {
                    if (ivAvatar.tag == cacheKey) {
                        ivAvatar.setImageBitmap(bitmap)
                        ivAvatar.visibility = View.VISIBLE
                        tvFallback.visibility = View.GONE
                    }
                }
            }
        }
    }

    private suspend fun fetchOrLoadAvatarBitmap(
        context: Context,
        serverUrl: String,
        isGroup: Boolean,
        id: Int,
        cacheKey: String
    ): Bitmap? {
        avatarMemoryCache.get(cacheKey)?.let { return it }

        val avatarDir = File(context.cacheDir, "avatars").apply { if (!exists()) mkdirs() }
        val diskFile = File(avatarDir, "$cacheKey.webp")
        val legacyFile = File(avatarDir, "$cacheKey.png")

        val existingDisk = when {
            diskFile.exists() && diskFile.length() > 0 -> diskFile
            legacyFile.exists() && legacyFile.length() > 0 -> legacyFile
            else -> null
        }

        if (existingDisk != null) {
            val cachedBmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(existingDisk.absolutePath)
            }
            if (cachedBmp != null) {
                avatarMemoryCache.put(cacheKey, cachedBmp)
                return cachedBmp
            }
        }

        val deferred = inFlightAvatars.getOrPut(cacheKey) {
            coreScope.async {
                avatarSemaphore.withPermit {
                    try {
                        val validTk = ensureValidToken(context, forceRenew = false) ?: activeToken
                        val path = if (isGroup) {
                            "$serverUrl/api/resource/group_avatar?gid=$id"
                        } else {
                            "$serverUrl/api/resource/avatar?uid=$id"
                        }
                        val req = Request.Builder()
                            .url(path)
                            .header("X-API-Key", validTk)
                            .get()
                            .build()
                        httpClient.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                val bytes = res.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val sampled = decodeSampledBitmap(bytes, 144, 144)
                                    if (sampled != null) {
                                        avatarMemoryCache.put(cacheKey, sampled)
                                        FileOutputStream(diskFile).use { out ->
                                            sampled.compress(Bitmap.CompressFormat.PNG, 90, out)
                                        }
                                        return@withPermit sampled
                                    }
                                }
                            }
                        }
                        null
                    } catch (_: Exception) {
                        null
                    } finally {
                        inFlightAvatars.remove(cacheKey)
                    }
                }
            }
        }

        return deferred.await()
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        options.inSampleSize = inSampleSize
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun parseMessagePreview(detail: JSONObject?): String {
        if (detail == null) return ""
        val contentType = detail.optString("content_type", "text/plain")
        val rawContent = detail.optString("content", "")
        if (contentType == "vocechat/file") {
            val props = detail.optJSONObject("properties")
            val mime = props?.optString("content_type", "") ?: ""
            val fileName = props?.optString("name", "") ?: ""
            return when {
                mime.startsWith("image/") -> "[图片]"
                mime.startsWith("audio/") -> "[语音]"
                mime.startsWith("video/") -> "[视频]"
                fileName.isNotBlank() -> "[文件] $fileName"
                else -> "[文件]"
            }
        }
        return rawContent
    }

    fun normalizeTimestampMs(ts: Long): Long {
        return if (ts in 1..99999999999L) ts * 1000L else ts
    }

    fun formatListTime(tsMs: Long): String {
        if (tsMs <= 0L) return ""
        val msgCal = Calendar.getInstance().apply { timeInMillis = normalizeTimestampMs(tsMs) }
        val nowCal = Calendar.getInstance()
        return when {
            msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) -> {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(msgCal.time)
            }
            msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                nowCal.get(Calendar.DAY_OF_YEAR) - msgCal.get(Calendar.DAY_OF_YEAR) == 1 -> {
                "昨天"
            }
            msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) -> {
                SimpleDateFormat("MM-dd", Locale.getDefault()).format(msgCal.time)
            }
            else -> {
                SimpleDateFormat("yy/MM/dd", Locale.getDefault()).format(msgCal.time)
            }
        }
    }

    fun formatBubbleTime(tsMs: Long): String {
        if (tsMs <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(normalizeTimestampMs(tsMs)))
    }
}
