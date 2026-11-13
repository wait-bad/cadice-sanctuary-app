package com.sanctuary.cadice

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class ChatActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1
    private var myName: String = ""
    private var isDarkTheme: Boolean = true
    private var fontScale: Float = 1.0f

    private var isGroup: Boolean = true
    private var targetId: Int = -1
    private var targetName: String = ""

    private val messageList = mutableListOf<ChatMessage>()
    private val loadedMids = HashSet<Long>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        myName = prefs.getString(SanctuaryCore.KEY_USER_NAME, "我") ?: "我"
        isDarkTheme = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)
        fontScale = SanctuaryCore.getFontScale(prefs)

        isGroup = intent.getBooleanExtra("is_group", true)
        targetId = intent.getIntExtra("target_id", -1)
        targetName = intent.getStringExtra("target_name") ?: "会话"

        applyTheme()

        val btnBack = findViewById<MaterialButton>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tv_chat_title)
        tvTitle.text = targetName

        val tvSubtitle = findViewById<TextView>(R.id.tv_chat_subtitle)
        tvSubtitle.text = if (isGroup) {
            "群组频道 · 实时全双工"
        } else {
            val online = SanctuaryCore.isUserOnline(targetId)
            if (online) "🟢 当前在线 · 私聊会话" else "⚪ 离线 · 私聊会话"
        }

        rvMessages = findViewById(R.id.rv_messages)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = layoutManager
        chatAdapter = ChatAdapter(
            context = this,
            scope = lifecycleScope,
            messages = messageList,
            serverUrl = serverUrl,
            token = SanctuaryCore.activeToken.ifEmpty { token },
            isDarkTheme = isDarkTheme,
            fontScale = fontScale
        )
        rvMessages.adapter = chatAdapter

        val etMessage = findViewById<EditText>(R.id.et_message)
        etMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
        val btnSend = findViewById<MaterialButton>(R.id.btn_send)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text, etMessage, btnSend)
            }
        }

        loadHistory()
        observeRealtimeMessages()
    }

    private fun applyTheme() {
        val chatRoot = findViewById<LinearLayout>(R.id.chat_root)
        val topBar = findViewById<LinearLayout>(R.id.chat_topbar)
        val bottomBar = findViewById<LinearLayout>(R.id.chat_bottom_bar)
        val btnBack = findViewById<MaterialButton>(R.id.btn_back)
        val tvTitle = findViewById<TextView>(R.id.tv_chat_title)
        val tvSubtitle = findViewById<TextView>(R.id.tv_chat_subtitle)
        val cardInput = findViewById<MaterialCardView>(R.id.card_chat_input)
        val etMessage = findViewById<EditText>(R.id.et_message)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isDarkTheme) {
            val bg = Color.parseColor("#0F172A")
            val bar = Color.parseColor("#1E293B")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            chatRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            btnBack.setTextColor(Color.parseColor("#F8FAFC"))
            tvTitle.setTextColor(Color.parseColor("#F8FAFC"))
            tvSubtitle.setTextColor(Color.parseColor("#94A3B8"))
            cardInput.setCardBackgroundColor(bg)
            cardInput.strokeColor = Color.parseColor("#334155")
            etMessage.setTextColor(Color.parseColor("#F8FAFC"))
            etMessage.setHintTextColor(Color.parseColor("#64748B"))
        } else {
            val bg = Color.parseColor("#F8FAFC")
            val bar = Color.parseColor("#FFFFFF")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true

            chatRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            btnBack.setTextColor(Color.parseColor("#0F172A"))
            tvTitle.setTextColor(Color.parseColor("#0F172A"))
            tvSubtitle.setTextColor(Color.parseColor("#64748B"))
            cardInput.setCardBackgroundColor(bg)
            cardInput.strokeColor = Color.parseColor("#E2E8F0")
            etMessage.setTextColor(Color.parseColor("#0F172A"))
            etMessage.setHintTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun observeRealtimeMessages() {
        lifecycleScope.launch {
            SanctuaryCore.incomingMessages.collect { event ->
                if (event.isGroup == isGroup && event.targetId == targetId) {
                    if (loadedMids.add(event.mid)) {
                        val isSelf = (event.fromUid == myUid)
                        val senderName = if (isSelf) myName else SanctuaryCore.getDisplayName(event.fromUid)
                        messageList.add(
                            ChatMessage(
                                mid = event.mid,
                                fromUid = event.fromUid,
                                fromName = senderName,
                                fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(event.fromUid),
                                content = event.content,
                                createdAt = event.createdAt,
                                isSelf = isSelf,
                                contentType = event.contentType
                            )
                        )
                        chatAdapter.notifyItemInserted(messageList.size - 1)
                        rvMessages.scrollToPosition(messageList.size - 1)
                    }
                }
            }
        }
    }

    private fun loadHistory() {
        val path = if (isGroup) {
            "$serverUrl/api/group/$targetId/history?limit=50"
        } else {
            "$serverUrl/api/user/$targetId/history?limit=50"
        }

        lifecycleScope.launch {
            try {
                // 使用自动续期 + 401 重试执行器，彻底解决免登后进聊天室没历史的问题！
                val (code, body) = SanctuaryCore.executeAuthorized(this@ChatActivity) { tk ->
                    Request.Builder()
                        .url(path)
                        .header("X-API-Key", tk)
                        .get()
                        .build()
                }

                if (code in 200..299 && body.startsWith("[")) {
                    val arr = JSONArray(body)
                    messageList.clear()
                    loadedMids.clear()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val mid = obj.optLong("mid", -1L)
                        val fromUid = obj.optInt("from_uid", -1)
                        val createdAt = SanctuaryCore.normalizeTimestampMs(
                            obj.optLong("created_at", System.currentTimeMillis())
                        )
                        val detail = obj.optJSONObject("detail")
                        val contentType = detail?.optString("content_type", "text/plain") ?: "text/plain"
                        val content = SanctuaryCore.parseMessagePreview(detail)

                        if (mid != -1L && content.isNotEmpty() && loadedMids.add(mid)) {
                            val isSelf = (fromUid == myUid)
                            val senderName = if (isSelf) myName else SanctuaryCore.getDisplayName(fromUid)
                            messageList.add(
                                ChatMessage(
                                    mid = mid,
                                    fromUid = fromUid,
                                    fromName = senderName,
                                    fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(fromUid),
                                    content = content,
                                    createdAt = createdAt,
                                    isSelf = isSelf,
                                    contentType = contentType
                                )
                            )
                        }
                    }

                    chatAdapter.notifyDataSetChanged()
                    if (messageList.isNotEmpty()) {
                        rvMessages.scrollToPosition(messageList.size - 1)
                    }
                } else if (code !in 200..299) {
                    Toast.makeText(this@ChatActivity, "拉取历史失败 (HTTP $code)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "获取历史失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendMessage(text: String, etMessage: EditText, btnSend: MaterialButton) {
        val sendUrl = if (isGroup) {
            "$serverUrl/api/group/$targetId/send"
        } else {
            "$serverUrl/api/user/$targetId/send"
        }

        btnSend.isEnabled = false

        lifecycleScope.launch {
            try {
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val (code, midStr) = SanctuaryCore.executeAuthorized(this@ChatActivity) { tk ->
                    Request.Builder()
                        .url(sendUrl)
                        .header("X-API-Key", tk)
                        .post(text.toRequestBody(mediaType))
                        .build()
                }

                if (code in 200..299) {
                    val newMid = midStr.trim().toLongOrNull() ?: System.currentTimeMillis()
                    if (loadedMids.add(newMid)) {
                        messageList.add(
                            ChatMessage(
                                mid = newMid,
                                fromUid = myUid,
                                fromName = myName,
                                fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
                                content = text,
                                createdAt = System.currentTimeMillis(),
                                isSelf = true
                            )
                        )
                        chatAdapter.notifyItemInserted(messageList.size - 1)
                        rvMessages.scrollToPosition(messageList.size - 1)
                    }
                    etMessage.setText("")
                } else {
                    Toast.makeText(this@ChatActivity, "发送失败 (HTTP $code)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "发送异常: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                btnSend.isEnabled = true
            }
        }
    }
}
