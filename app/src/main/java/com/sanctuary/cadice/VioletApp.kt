package com.sanctuary.cadice

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 🌸 全局前后台生命周期管家：
 * - 切到后台且未开“前台保活”时：优雅暂停 SSE 释放无线电与电量（接近 0 功耗）；
 * - 任意页面切回前台的瞬间：0ms 秒唤醒 SSE 并探测健康状态，告别 1~2 分钟漫长重连！
 */
class VioletApp : Application() {

    private var startedCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = (startedCount == 0)
                startedCount++
                if (wasBackground) {
                    val prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, MODE_PRIVATE)
                    val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
                    val token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
                    val uid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
                    if (serverUrl.isNotBlank() && token.isNotBlank() && uid > 0) {
                        SanctuaryCore.pokeOrRestartSse(applicationContext, serverUrl, uid)
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) {
                    val prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, MODE_PRIVATE)
                    val keepAlive = prefs.getBoolean(SanctuaryCore.KEY_KEEP_ALIVE, false)
                    if (!keepAlive) {
                        SanctuaryCore.pauseSseForBackground()
                    }
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
