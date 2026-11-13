package com.sanctuary.cadice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔔 工业级通知栏防轰炸系统（融合 GetStream MessagingStyle + Element 200ms 尾节流 & 进房自动消提醒）
 */
object NotificationHelper {

    const val CHANNEL_MESSAGES = "violet_messages"
    const val CHANNEL_KEEPALIVE = "violet_keepalive"
    const val KEEPALIVE_NOTIFICATION_ID = 10001
    const val KEY_TEXT_REPLY = "key_violet_text_reply"
    const val ACTION_REPLY = "com.sanctuary.cadice.ACTION_DIRECT_REPLY"
    const val ACTION_MARK_READ = "com.sanctuary.cadice.ACTION_MARK_READ"

    private data class PendingNotifyMsg(
        val mid: Long,
        val senderName: String,
        val text: String,
        val timestamp: Long
    )

    private val notifyThread = HandlerThread("VioletNotifyThread", Thread.MIN_PRIORITY).apply { start() }
    private val notifyHandler = Handler(notifyThread.looper)

    private val roomBuckets = ConcurrentHashMap<String, MutableList<PendingNotifyMsg>>()
    private val recentMids = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            if (size >= 40) {
                val it = iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return super.add(element)
        }
    }

    @Volatile
    private var currentRoomKey: String? = null

    @Volatile
    private var flushScheduled = false

    fun roomKey(isGroup: Boolean, targetId: Int): String = "${if (isGroup) "g" else "u"}_$targetId"

    private fun notificationIdForRoom(isGroup: Boolean, targetId: Int): Int {
        return 20000 + (if (isGroup) 100000 else 0) + (targetId and 0xFFFF)
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "聊天新消息提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "接收好友私聊与群聊实时消息提醒"
            enableVibration(true)
        }
        val keepAliveChannel = NotificationChannel(
            CHANNEL_KEEPALIVE,
            "后台实时消息通道",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与 VoceChat 私有云的实时消息长连接"
            setShowBadge(false)
        }
        nm.createNotificationChannel(msgChannel)
        nm.createNotificationChannel(keepAliveChannel)
    }

    fun setCurrentRoom(context: Context, isGroup: Boolean, targetId: Int) {
        currentRoomKey = roomKey(isGroup, targetId)
        clearRoomNotifications(context, isGroup, targetId)
    }

    fun clearCurrentRoom() {
        currentRoomKey = null
    }

    fun clearRoomNotifications(context: Context, isGroup: Boolean, targetId: Int) {
        val key = roomKey(isGroup, targetId)
        roomBuckets.remove(key)
        try {
            NotificationManagerCompat.from(context).cancel(notificationIdForRoom(isGroup, targetId))
        } catch (_: Exception) {
        }
    }

    fun clearAllNotifications(context: Context?) {
        roomBuckets.clear()
        if (context == null) return
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {
        }
    }

    fun onIncomingMessage(context: Context, event: IncomingChatEvent) {
        val appCtx = context.applicationContext
        val key = roomKey(event.isGroup, event.targetId)
        if (currentRoomKey == key) return

        synchronized(recentMids) {
            if (!recentMids.add(event.mid)) return
        }

        val sender = SanctuaryCore.getDisplayName(event.fromUid)
        val bucket = roomBuckets.getOrPut(key) { mutableListOf() }
        synchronized(bucket) {
            bucket.add(
                PendingNotifyMsg(
                    mid = event.mid,
                    senderName = sender,
                    text = event.content,
                    timestamp = event.createdAt
                )
            )
            if (bucket.size > 6) {
                bucket.removeAt(0)
            }
        }

        // 200ms 尾节流合并渲染（借鉴 Element FirstThrottler(200)）
        if (!flushScheduled) {
            flushScheduled = true
            notifyHandler.postDelayed({
                flushScheduled = false
                renderAllBuckets(appCtx)
            }, 200L)
        }
    }

    private fun renderAllBuckets(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels(context)
        val nm = NotificationManagerCompat.from(context)
        val prefs = context.getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val myName = prefs.getString(SanctuaryCore.KEY_USER_NAME, "我") ?: "我"
        val mePerson = Person.Builder().setName(myName).build()

        roomBuckets.entries.forEach { (key, list) ->
            if (currentRoomKey == key) return@forEach
            val snapshot = synchronized(list) { list.toList() }
            if (snapshot.isEmpty()) return@forEach

            val isGroup = key.startsWith("g_")
            val targetId = key.substring(2).toIntOrNull() ?: return@forEach
            val latestMid = snapshot.last().mid
            val roomTitle = if (isGroup) "群聊 #$targetId" else SanctuaryCore.getDisplayName(targetId)
            val convTitle = resolveConversationTitle(context, isGroup, targetId, roomTitle)

            val style = NotificationCompat.MessagingStyle(mePerson)
                .setConversationTitle(if (isGroup) convTitle else null)
                .setGroupConversation(isGroup)
            snapshot.forEach { m ->
                val person = Person.Builder().setName(m.senderName).build()
                style.addMessage(m.text, m.timestamp, person)
            }

            val openIntent = Intent(context, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("is_group", isGroup)
                putExtra("target_id", targetId)
                putExtra("target_name", convTitle)
            }
            val notifId = notificationIdForRoom(isGroup, targetId)
            val contentPending = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 快捷回复 RemoteInput（Android 12+ 必须使用 FLAG_MUTABLE）
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("回复 $convTitle...")
                .build()
            val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra("is_group", isGroup)
                putExtra("target_id", targetId)
                putExtra("target_name", convTitle)
                putExtra("last_mid", latestMid)
            }
            val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val replyPending = PendingIntent.getBroadcast(context, notifId + 1, replyIntent, replyFlags)
            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "回复",
                replyPending
            ).addRemoteInput(remoteInput).build()

            val readIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_MARK_READ
                putExtra("is_group", isGroup)
                putExtra("target_id", targetId)
                putExtra("last_mid", latestMid)
            }
            val readPending = PendingIntent.getBroadcast(
                context,
                notifId + 2,
                readIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val readAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_view,
                "标为已读",
                readPending
            ).build()

            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setStyle(style)
                .setContentTitle(convTitle)
                .setContentText(snapshot.last().text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(contentPending)
                .addAction(replyAction)
                .addAction(readAction)
                .build()

            try {
                nm.notify(notifId, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun resolveConversationTitle(
        context: Context,
        isGroup: Boolean,
        targetId: Int,
        fallback: String
    ): String {
        if (!isGroup) return SanctuaryCore.getDisplayName(targetId)
        val prefs = context.getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        val myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        if (serverUrl.isNotBlank() && myUid > 0) {
            val found = SanctuaryDb.getInstance(context).loadConversations(serverUrl, myUid)
                .firstOrNull { it.isGroup == isGroup && it.id == targetId }
            if (found != null && found.name.isNotBlank()) return found.name
        }
        return fallback
    }
}
