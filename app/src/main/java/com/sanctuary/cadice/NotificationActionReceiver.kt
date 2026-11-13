package com.sanctuary.cadice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 📩 处理系统通知栏按钮回调（通知栏内直接打字回复 / 标为已读）
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val isGroup = intent.getBooleanExtra("is_group", true)
        val targetId = intent.getIntExtra("target_id", -1)
        val lastMid = intent.getLongExtra("last_mid", 0L)
        if (targetId == -1) return

        val prefs = context.getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        val myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        val myName = prefs.getString(SanctuaryCore.KEY_USER_NAME, "我") ?: "我"

        when (intent.action) {
            NotificationHelper.ACTION_MARK_READ -> {
                if (serverUrl.isNotBlank() && myUid > 0 && lastMid > 0L) {
                    SanctuaryCore.markConversationRead(context, serverUrl, myUid, isGroup, targetId, lastMid)
                } else {
                    NotificationHelper.clearRoomNotifications(context, isGroup, targetId)
                }
            }
            NotificationHelper.ACTION_REPLY -> {
                val bundle = RemoteInput.getResultsFromIntent(intent) ?: return
                val replyText = bundle.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
                if (replyText.isBlank() || serverUrl.isBlank() || myUid <= 0) {
                    NotificationHelper.clearRoomNotifications(context, isGroup, targetId)
                    return
                }
                val pendingResult = goAsync()
                val appCtx = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val (ok, realMid) = SanctuaryCore.sendTextQueued(appCtx, serverUrl, isGroup, targetId, replyText)
                        if (ok && realMid > 0L) {
                            SanctuaryCore.updateGlobalMaxMid(appCtx, myUid, realMid)
                            SanctuaryCore.markConversationRead(appCtx, serverUrl, myUid, isGroup, targetId, realMid)
                            SanctuaryDb.getInstance(appCtx).upsertSingleMessage(
                                serverUrl, myUid, isGroup, targetId,
                                ChatMessage(
                                    mid = realMid,
                                    fromUid = myUid,
                                    fromName = myName,
                                    fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
                                    content = replyText,
                                    createdAt = System.currentTimeMillis(),
                                    isSelf = true,
                                    syncStatus = ChatMessage.STATUS_SENT
                                )
                            )
                        }
                        NotificationHelper.clearRoomNotifications(appCtx, isGroup, targetId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
