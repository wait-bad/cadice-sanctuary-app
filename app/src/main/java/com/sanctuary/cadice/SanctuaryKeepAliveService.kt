package com.sanctuary.cadice

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 🔋 国产手机无 FCM 下的轻量前台保活服务（借鉴 Element VectorSyncAndroidService）
 */
class SanctuaryKeepAliveService : Service() {

    companion object {
        fun syncWithPreference(context: Context) {
            val prefs = context.getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(SanctuaryCore.KEY_KEEP_ALIVE, false)
            val token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
            val intent = Intent(context, SanctuaryKeepAliveService::class.java)
            if (enabled && token.isNotEmpty()) {
                try {
                    context.startForegroundService(intent)
                } catch (_: Exception) {
                }
            } else {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.ensureChannels(this)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_KEEPALIVE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Violet IM 实时通道运行中")
            .setContentText("正在守护私有云长连接，不错过任何一条消息")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationHelper.KEEPALIVE_NOTIFICATION_ID, notification, fgsType)

        val prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        val myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        val token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        if (serverUrl.isNotBlank() && myUid > 0 && token.isNotBlank()) {
            SanctuaryCore.startSse(applicationContext, serverUrl, myUid)
        }
        return START_STICKY
    }
}
