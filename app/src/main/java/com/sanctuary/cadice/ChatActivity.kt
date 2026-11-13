package com.sanctuary.cadice

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class ChatActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1

    private var isGroup: Boolean = true
    private var targetId: Int = -1
    private var targetName: String = ""

    private val messageList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var rvMessages: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        prefs = getSharedPreferences("cadice_sanctuary_prefs", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        myUid = prefs.getInt("user_uid", -1)

        isGroup = intent.getBooleanExtra("is_group", true)
        targetId = intent.getIntExtra("target_id", -1)
        targetName = intent.getStringExtra("target_name") ?: "会话"

        val btnBack = findViewById<Button>(R.id.btn_back)
        btnBack.setOnClickListener { finish() }

        val tvTitle = findViewById<TextView>(R.id.tv_chat_title)
        tvTitle.text = targetName

        val tvSubtitle = findViewById<TextView>(R.id.tv_chat_subtitle)
        tvSubtitle.text = if (isGroup) "群组频道 (GID: $targetId)" else "私聊加密会话 (UID: $targetId)"

        rvMessages = findViewById(R.id.rv_messages)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = layoutManager
        chatAdapter = ChatAdapter(messageList)
        rvMessages.adapter = chatAdapter

        val etMessage = findViewById<EditText>(R.id.et_message)
        val btnSend = findViewById<Button>(R.id.btn_send)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text, etMessage, btnSend)
            }
        }

        // 加载历史消息
        loadHistory()
    }

    private fun loadHistory() {
        val path = if (isGroup) {
            "$serverUrl/api/group/$targetId/history?limit=40"
        } else {
            "$serverUrl/api/user/$targetId/history?limit=40"
        }

        lifecycleScope.launch {
            try {
                val req = Request.Builder()
                    .url(path)
                    .header("X-API-Key", token)
                    .get()
                    .build()

                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val body = res.body?.string().orEmpty()

                if (res.isSuccessful && body.isNotEmpty()) {
                    val arr = JSONArray(body)
                    messageList.clear()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val mid = obj.getLong("mid")
                        val fromUid = obj.getInt("from_uid")
                        val createdAt = obj.optLong("created_at", System.currentTimeMillis())
                        val detail = obj.optJSONObject("detail")
                        val content = detail?.optString("content", "") ?: ""

                        if (content.isNotEmpty()) {
                            val senderName = if (fromUid == myUid) "我" else "用户 $fromUid"
                            messageList.add(
                                ChatMessage(
                                    mid = mid,
                                    fromUid = fromUid,
                                    fromName = senderName,
                                    content = content,
                                    createdAt = createdAt,
                                    isSelf = (fromUid == myUid)
                                )
                            )
                        }
                    }

                    chatAdapter.notifyDataSetChanged()
                    if (messageList.isNotEmpty()) {
                        rvMessages.scrollToPosition(messageList.size - 1)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "获取历史失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendMessage(text: String, etMessage: EditText, btnSend: Button) {
        val sendUrl = if (isGroup) {
            "$serverUrl/api/group/$targetId/send"
        } else {
            "$serverUrl/api/user/$targetId/send"
        }

        btnSend.isEnabled = false

        lifecycleScope.launch {
            try {
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val req = Request.Builder()
                    .url(sendUrl)
                    .header("X-API-Key", token)
                    .post(text.toRequestBody(mediaType))
                    .build()

                val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                if (res.isSuccessful) {
                    val midStr = res.body?.string()?.trim().orEmpty()
                    val newMid = midStr.toLongOrNull() ?: System.currentTimeMillis()

                    messageList.add(
                        ChatMessage(
                            mid = newMid,
                            fromUid = myUid,
                            fromName = "我",
                            content = text,
                            createdAt = System.currentTimeMillis(),
                            isSelf = true
                        )
                    )
                    chatAdapter.notifyItemInserted(messageList.size - 1)
                    rvMessages.scrollToPosition(messageList.size - 1)
                    etMessage.setText("")
                } else {
                    Toast.makeText(this@ChatActivity, "发送失败 (HTTP ${res.code})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "发送异常: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                btnSend.isEnabled = true
            }
        }
    }
}
