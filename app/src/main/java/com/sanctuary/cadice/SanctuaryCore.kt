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

    // 内存头像缓存 (最多存 100 张缩放后头像)
    private val avatarMemoryCache = LruCache<String, Bitmap>(100)
    private val inFlightAvatars = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 保护 Token 自动续期的互斥锁，防止并发重复 renew
    private val renewMutex = Mutex()
    private val previewSemaphore = Semaphore(3)
    private val avatarSemaphore = Semaphore(2)

    @Volatile
    var activeToken: String = ""

    // 全局实时事件总线
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
        context?.let {
            File(it.cacheDir, "snapshot_home.json").delete()
        }
    }

    // 🔐 核心保活：检查并自动刷新 VoceChat 5分钟短期 AccessToken！
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
        // 若距离过期不足 60 秒（或强制刷新），则调用 /api/token/renew 续期
        val needsRenew = forceRenew || obtainedAt == 0L || (now - obtainedAt) >= ((expiredInSec - 60).coerceAtLeast(30) * 1000L)
        if (!needsRenew) {
            return currentToken
        }

        if (refreshToken.isEmpty()) {
            // 老版本升级上来还没有保存 refresh_token：强制刷新时触发温和重登提醒，否则先试着用旧 token
            if (forceRenew) {
                _authExpiredEvents.tryEmit(Unit)
                return null
            }
            return currentToken
        }

        return renewMutex.withLock {
            // 双重检查，避免刚被另一个协程续期过
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
                // 网络抖动没续上时，若非强制刷新，先返回现有 token；强制刷新失败则交由调用方处理
                if (forceRenew) null else (prefs.getString(KEY_TOKEN, "") ?: currentToken)
            }
        }
    }

    // 🛡️ 带自动 401 续期重试的通用请求执行器
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
        // 遇到 401 立即强制 renew 后重试一次
        val token2 = ensureValidToken(context, forceRenew = true) ?: return Pair(401, "")
        return withContext(Dispatchers.IO) {
            httpClient.newCall(requestBuilder(token2)).execute().use { res ->
                Pair(res.code, res.body?.string().orEmpty())
            }
        }
    }

    // 💾 读取本地列表快照（0ms 秒开首屏）
    fun loadHomeSnapshot(context: Context): Pair<List<ConversationItem>, List<ConversationItem>>? {
        return try {
            val file = File(context.cacheDir, "snapshot_home.json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val usersArr = root.optJSONArray("users")
            if (usersArr != null) {
                for (i in 0 until usersArr.length()) {
                    putUserJson(usersArr.getJSONObject(i))
                }
            }
            val convs = jsonToItems(root.optJSONArray("conversations"))
            val contacts = jsonToItems(root.optJSONArray("contacts"))
            if (convs.isEmpty() && contacts.isEmpty()) null else Pair(convs, contacts)
        } catch (_: Exception) {
            null
        }
    }

    fun saveHomeSnapshot(
        context: Context,
        conversations: List<ConversationItem>,
        contacts: List<ConversationItem>
    ) {
        coreScope.launch {
            try {
                val root = JSONObject()
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
                root.put("users", usersArr)
                root.put("conversations", itemsToJson(conversations))
                root.put("contacts", itemsToJson(contacts))
                File(context.cacheDir, "snapshot_home.json").writeText(root.toString())
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
                    unreadCount = o.optInt("unreadCount", 0),
                    avatarUpdatedAt = o.optLong("avatarUpdatedAt", 0L),
                    isOnline = if (isGroup) false else isUserOnline(id),
                    badge = o.optString("badge", if (isGroup) "群聊" else "好友")
                )
            )
        }
        return list
    }

    // ⚡ 阶段一：并发拉取用户表、群组、好友联系人（自动续期 + 错峰防超时）
    suspend fun fetchBaseDirectory(
        context: Context,
        serverUrl: String,
        myUid: Int
    ): Pair<List<ConversationItem>, List<ConversationItem>> = withContext(Dispatchers.IO) {
        // 先保证拿到可用有效 Token，避免三路同时撞 401
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
            val baseConversations = groupItems + memberItems
            Pair(baseConversations, allContacts)
        }
    }

    // ⚡ 阶段二：限流并发（最多同时 3 路）补全会话最后一条消息摘要，彻底避免把隧道挤超时！
    suspend fun hydrateLatestPreviews(
        context: Context,
        serverUrl: String,
        items: List<ConversationItem>
    ): List<ConversationItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { item ->
                async {
                    previewSemaphore.withPermit {
                        fetchLatestPreview(context, serverUrl, item)
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
                    val fromUid = lastObj.optInt("from_uid", -1)
                    val createdAt = normalizeTimestampMs(lastObj.optLong("created_at", 0L))
                    val detail = lastObj.optJSONObject("detail")
                    val previewText = parseMessagePreview(detail)
                    val formatted = if (item.isGroup && fromUid != -1) {
                        "${getDisplayName(fromUid)}: $previewText"
                    } else {
                        previewText
                    }
                    return item.copy(
                        lastMsg = formatted,
                        lastTime = createdAt
                    )
                }
            }
            item
        } catch (_: Exception) {
            item
        }
    }

    // 🖼️ 上传当前用户个人头像 (自动缩放到 512px 以内 PNG 提交到 /api/user/avatar)
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

    // 🔌 启动 VoceChat SSE 实时事件流（每次重连都先自动续期 Token，绝不再用过期 Token 空转）
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

                    val req = Request.Builder()
                        .url("$serverUrl/api/user/events?api-key=$freshToken")
                        .header("Accept", "text/event-stream")
                        .get()
                        .build()

                    sseClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            backoffMs = 2000L
                            val source = response.body?.source()
                            while (isActive && source != null && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:")) {
                                    val jsonText = line.removePrefix("data:").trim()
                                    if (jsonText.startsWith("{")) {
                                        handleSseJson(jsonText, myUid)
                                    }
                                }
                            }
                        } else if (response.code == 401 || response.code == 403) {
                            // SSE 返回 401 说明 Token 刚好到期，立即强制续期后重连
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

    private fun handleSseJson(raw: String, myUid: Int) {
        try {
            val obj = JSONObject(raw)
            when (obj.optString("type")) {
                "users_snapshot" -> {
                    val users = obj.optJSONArray("users")
                    if (users != null) {
                        parseUsersArray(users)
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
                "chat" -> {
                    val mid = obj.optLong("mid", 0L)
                    val fromUid = obj.optInt("from_uid", -1)
                    val createdAt = normalizeTimestampMs(obj.optLong("created_at", System.currentTimeMillis()))
                    val detail = obj.optJSONObject("detail")
                    val contentType = detail?.optString("content_type", "text/plain") ?: "text/plain"
                    val content = parseMessagePreview(detail)
                    val target = obj.optJSONObject("target") ?: return

                    if (mid <= 0L || fromUid == -1 || content.isBlank()) return

                    if (target.has("gid")) {
                        val gid = target.getInt("gid")
                        _incomingMessages.tryEmit(
                            IncomingChatEvent(
                                mid = mid,
                                fromUid = fromUid,
                                isGroup = true,
                                targetId = gid,
                                content = content,
                                contentType = contentType,
                                createdAt = createdAt
                            )
                        )
                    } else if (target.has("uid")) {
                        val targetUid = target.getInt("uid")
                        val peerUid = if (fromUid == myUid) targetUid else fromUid
                        _incomingMessages.tryEmit(
                            IncomingChatEvent(
                                mid = mid,
                                fromUid = fromUid,
                                isGroup = false,
                                targetId = peerUid,
                                content = content,
                                contentType = contentType,
                                createdAt = createdAt
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
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

    // 🖼️ 预热有头像的用户（受 avatarSemaphore 限流控制，不与列表请求抢隧道）
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

    // 🖼️ 加载用户或群组头像（内存 + 磁盘缩略图缓存 + In-Flight 请求去重合并）
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
