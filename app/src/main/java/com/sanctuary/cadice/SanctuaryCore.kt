package com.sanctuary.cadice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SanctuaryCore {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "VioletIM/1.4 (Android; Mobile)")
                .build()
            chain.proceed(req)
        }
        .build()

    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "VioletIM/1.4 (Android; Mobile)")
                .build()
            chain.proceed(req)
        }
        .build()

    val userMap = ConcurrentHashMap<Int, UserProfile>()
    val onlineMap = ConcurrentHashMap<Int, Boolean>()

    // 内存头像缓存 (最多存 80 张缩放后头像)
    private val avatarMemoryCache = LruCache<String, Bitmap>(80)

    // 全局实时事件总线
    private val _incomingMessages = MutableSharedFlow<IncomingChatEvent>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _userStateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val userStateEvents = _userStateEvents.asSharedFlow()

    private var sseJob: Job? = null
    private val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getDisplayName(uid: Int): String {
        return userMap[uid]?.name?.takeIf { it.isNotBlank() } ?: "用户 $uid"
    }

    fun getAvatarUpdatedAt(uid: Int): Long {
        return userMap[uid]?.avatarUpdatedAt ?: 0L
    }

    fun isUserOnline(uid: Int): Boolean {
        return onlineMap[uid] == true || userMap[uid]?.isOnline == true
    }

    fun clearSessionCache() {
        stopSse()
        userMap.clear()
        onlineMap.clear()
    }

    // 📥 从服务器同步全部用户信息、群组与联系人并聚合最后一条消息
    suspend fun syncAllAndBuildLists(
        serverUrl: String,
        token: String,
        myUid: Int
    ): Pair<List<ConversationItem>, List<ConversationItem>> = withContext(Dispatchers.IO) {
        // 1. 拉取全员用户表 (/api/user)
        try {
            val userReq = Request.Builder()
                .url("$serverUrl/api/user")
                .header("X-API-Key", token)
                .get()
                .build()
            httpClient.newCall(userReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful && body.startsWith("[")) {
                    parseUsersArray(JSONArray(body))
                }
            }
        } catch (_: Exception) {
        }

        // 2. 拉取群组列表 (/api/group)
        val groupItems = mutableListOf<ConversationItem>()
        try {
            val groupReq = Request.Builder()
                .url("$serverUrl/api/group")
                .header("X-API-Key", token)
                .get()
                .build()
            httpClient.newCall(groupReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful && body.startsWith("[")) {
                    val groups = JSONArray(body)
                    for (i in 0 until groups.length()) {
                        val g = groups.getJSONObject(i)
                        val gid = g.getInt("gid")
                        val name = g.optString("name", "群聊 $gid")
                        val desc = g.optString("description", "群聊频道").ifBlank { "群聊频道" }
                        val avatarUpdatedAt = g.optLong("avatar_updated_at", 0L)
                        groupItems.add(
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
            }
        } catch (_: Exception) {
        }

        // 3. 拉取联系人列表 (/api/user/contacts)
        val friendItems = mutableListOf<ConversationItem>()
        val seenFriendIds = HashSet<Int>()
        try {
            val contactReq = Request.Builder()
                .url("$serverUrl/api/user/contacts")
                .header("X-API-Key", token)
                .get()
                .build()
            httpClient.newCall(contactReq).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful && body.startsWith("[")) {
                    val contacts = JSONArray(body)
                    for (i in 0 until contacts.length()) {
                        val c = contacts.getJSONObject(i)
                        val targetUid = c.optInt("target_uid", -1)
                        if (targetUid == -1 || targetUid == myUid) continue
                        seenFriendIds.add(targetUid)

                        val targetInfo = c.optJSONObject("target_info")
                        if (targetInfo != null) {
                            putUserJson(targetInfo)
                        }
                        val profile = userMap[targetUid]
                        val name = profile?.name ?: targetInfo?.optString("name", "用户 $targetUid") ?: "用户 $targetUid"
                        val email = profile?.email ?: targetInfo?.optString("email", "") ?: ""
                        val avatarUpdated = profile?.avatarUpdatedAt ?: targetInfo?.optLong("avatar_updated_at", 0L) ?: 0L

                        friendItems.add(
                            ConversationItem(
                                id = targetUid,
                                isGroup = false,
                                name = name,
                                description = email.ifEmpty { "VoceChat 好友" },
                                lastMsg = "点击开始私聊",
                                avatarUpdatedAt = avatarUpdated,
                                isOnline = isUserOnline(targetUid),
                                badge = "好友"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 若联系人接口为空但全局用户表有其他非 Bot 真实用户，补充展示到联系人列表方便开聊
        if (friendItems.isEmpty() && userMap.isNotEmpty()) {
            userMap.values.filter { it.uid != myUid && !it.isBot }.sortedBy { it.uid }.forEach { u ->
                if (seenFriendIds.add(u.uid)) {
                    friendItems.add(
                        ConversationItem(
                            id = u.uid,
                            isGroup = false,
                            name = u.name,
                            description = u.email.ifEmpty { "成员 #${u.uid}" },
                            lastMsg = "点击开始私聊",
                            avatarUpdatedAt = u.avatarUpdatedAt,
                            isOnline = isUserOnline(u.uid),
                            badge = if (u.isAdmin) "管理" else "成员"
                        )
                    )
                }
            }
        }

        val allRaw = groupItems + friendItems

        // 4. 并发拉取每个会话的最新一条消息，填充会话列表摘要与时间戳
        val hydratedConversations = coroutineScope {
            allRaw.map { item ->
                async {
                    fetchLatestPreview(serverUrl, token, item)
                }
            }.awaitAll()
        }.sortedWith(
            compareByDescending<ConversationItem> { it.lastTime }
                .thenByDescending { it.isGroup }
                .thenBy { it.id }
        )

        val contactsSorted = allRaw.map { item ->
            if (item.isGroup) item else item.copy(isOnline = isUserOnline(item.id))
        }

        Pair(hydratedConversations, contactsSorted)
    }

    private fun fetchLatestPreview(
        serverUrl: String,
        token: String,
        item: ConversationItem
    ): ConversationItem {
        return try {
            val url = if (item.isGroup) {
                "$serverUrl/api/group/${item.id}/history?limit=1"
            } else {
                "$serverUrl/api/user/${item.id}/history?limit=1"
            }
            val req = Request.Builder()
                .url(url)
                .header("X-API-Key", token)
                .get()
                .build()
            httpClient.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful && body.startsWith("[")) {
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
            }
            item
        } catch (_: Exception) {
            item
        }
    }

    // 🔌 启动 VoceChat SSE 实时事件流（带指数退避自动重连）
    fun startSse(serverUrl: String, token: String, myUid: Int) {
        sseJob?.cancel()
        sseJob = sseScope.launch {
            var backoffMs = 2000L
            while (isActive) {
                try {
                    val req = Request.Builder()
                        .url("$serverUrl/api/user/events?api-key=$token")
                        .header("Accept", "text/event-stream")
                        .get()
                        .build()

                    sseClient.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            backoffMs = 2000L // 建连成功重置退避时间
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
        val name = u.optString("name", "用户 $uid")
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

    // 🖼️ 加载用户或群组头像（内存 + 磁盘双层缓存，零闪烁回退文字占位）
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
        val resolvedUpdatedAt = if (!isGroup && avatarUpdatedAt == 0L) {
            getAvatarUpdatedAt(id)
        } else {
            avatarUpdatedAt
        }

        tvFallback.text = fallbackText
        tvFallback.visibility = View.VISIBLE
        ivAvatar.visibility = View.GONE

        if (resolvedUpdatedAt <= 0L || serverUrl.isBlank()) {
            ivAvatar.tag = null
            return
        }

        val cacheKey = "${if (isGroup) "g" else "u"}_${id}_$resolvedUpdatedAt"
        ivAvatar.tag = cacheKey

        // 1. 命中内存缓存直接渲染
        avatarMemoryCache.get(cacheKey)?.let { bmp ->
            ivAvatar.setImageBitmap(bmp)
            ivAvatar.visibility = View.VISIBLE
            tvFallback.visibility = View.GONE
            return
        }

        scope.launch(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            val avatarDir = File(context.cacheDir, "avatars").apply { if (!exists()) mkdirs() }
            val diskFile = File(avatarDir, "$cacheKey.png")

            // 2. 检查磁盘缓存
            if (diskFile.exists() && diskFile.length() > 0) {
                bitmap = decodeSampledBitmap(diskFile.readBytes(), 144, 144)
            }

            // 3. 网络拉取
            if (bitmap == null) {
                try {
                    val path = if (isGroup) {
                        "$serverUrl/api/resource/group_avatar?gid=$id"
                    } else {
                        "$serverUrl/api/resource/avatar?uid=$id"
                    }
                    val req = Request.Builder()
                        .url(path)
                        .header("X-API-Key", token)
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val bytes = res.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                bitmap = decodeSampledBitmap(bytes, 144, 144)
                                if (bitmap != null) {
                                    FileOutputStream(diskFile).use { it.write(bytes) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val finalBitmap = bitmap
            if (finalBitmap != null) {
                avatarMemoryCache.put(cacheKey, finalBitmap)
                withContext(Dispatchers.Main) {
                    if (ivAvatar.tag == cacheKey) {
                        ivAvatar.setImageBitmap(finalBitmap)
                        ivAvatar.visibility = View.VISIBLE
                        tvFallback.visibility = View.GONE
                    }
                }
            }
        }
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
