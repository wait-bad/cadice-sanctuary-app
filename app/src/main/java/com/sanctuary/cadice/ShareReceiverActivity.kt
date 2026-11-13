package com.sanctuary.cadice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 📲 系统外部分享直发接收器（借鉴 Element IncomingShare）
 * 从相册/微信/浏览器分享文字或图片，直接选中会话发送并打开聊天室！
 */
class ShareReceiverActivity : AppCompatActivity() {

    private var sharedText: String? = null
    private var sharedImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

        val prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        val token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        val myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        val isDark = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)

        if (serverUrl.isBlank() || token.isBlank() || myUid <= 0) {
            Toast.makeText(this, "请先登录 Violet IM 再使用分享发送", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type.orEmpty()
            if (type.startsWith("image/")) {
                sharedImageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }

        findViewById<TextView>(R.id.tv_share_cancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_share_summary).text = when {
            sharedImageUri != null -> "📷 待发送图片（将自动扶正并剥离 GPS 隐私），点击目标发送："
            !sharedText.isNullOrBlank() -> "📝 待发送文本：「${sharedText!!.take(40)}」"
            else -> "未检测到可分享内容"
        }

        val conversations = SanctuaryDb.getInstance(this).loadConversations(serverUrl, myUid)
        val rv = findViewById<RecyclerView>(R.id.rv_share_targets)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ConversationAdapter(
            context = this,
            scope = lifecycleScope,
            items = conversations,
            serverUrl = serverUrl,
            token = token,
            isDarkTheme = isDark,
            fontScale = SanctuaryCore.getFontScale(prefs)
        ) { target ->
            dispatchShareTo(target, serverUrl, myUid)
        }
    }

    private fun dispatchShareTo(target: ConversationItem, serverUrl: String, myUid: Int) {
        Toast.makeText(this, "正在发送给 ${target.name}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var ok = false
            val imgUri = sharedImageUri
            val txt = sharedText?.trim()
            if (imgUri != null) {
                val compressed = withContext(Dispatchers.IO) {
                    MediaHelper.compressAndStripExif(this@ShareReceiverActivity, imgUri)
                }
                if (compressed != null) {
                    val (sent, mid, _) = SanctuaryCore.uploadAndSendImageQueued(
                        this@ShareReceiverActivity, serverUrl, target.isGroup, target.id, compressed
                    )
                    ok = sent
                    if (sent && mid > 0L) {
                        SanctuaryCore.updateGlobalMaxMid(this@ShareReceiverActivity, myUid, mid)
                    }
                }
            } else if (!txt.isNullOrEmpty()) {
                val (sent, mid) = SanctuaryCore.sendTextQueued(
                    this@ShareReceiverActivity, serverUrl, target.isGroup, target.id, txt
                )
                ok = sent
                if (sent && mid > 0L) {
                    SanctuaryCore.updateGlobalMaxMid(this@ShareReceiverActivity, myUid, mid)
                }
            }

            if (ok) {
                Toast.makeText(this@ShareReceiverActivity, "已发送！", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@ShareReceiverActivity, ChatActivity::class.java).apply {
                    putExtra("is_group", target.isGroup)
                    putExtra("target_id", target.id)
                    putExtra("target_name", target.name)
                })
            } else {
                Toast.makeText(this@ShareReceiverActivity, "发送失败，请检查网络", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
