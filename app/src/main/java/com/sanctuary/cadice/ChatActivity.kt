package com.sanctuary.cadice

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

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
    private lateinit var etMessage: EditText

    private var isLoadingOlder = false
    private var hasMoreOlder = true
    private var draftSaveJob: Job? = null
    private val tempSeq = AtomicLong(0L)

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
            "群组频道 · 长按气泡可复制或删除"
        } else {
            val online = SanctuaryCore.isUserOnline(targetId)
            if (online) "🟢 当前在线 · 长按气泡可复制或删除" else "⚪ 离线 · 长按气泡可复制或删除"
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
            fontScale = fontScale,
            onMessageLongClick = { msg -> showMessageActionsDialog(msg) },
            onRetryFailedClick = { failedMsg -> retryFailedMessage(failedMsg) }
        )
        rvMessages.adapter = chatAdapter

        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && !isLoadingOlder && hasMoreOlder && messageList.isNotEmpty()) {
                    val firstVisible = layoutManager.findFirstVisibleItemPosition()
                    if (firstVisible <= 2) {
                        loadOlderHistory()
                    }
                }
            }
        })

        etMessage = findViewById(R.id.et_message)
        etMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
        setupDraftPersistence()

        val btnSend = findViewById<MaterialButton>(R.id.btn_send)
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                etMessage.setText("")
                SanctuaryCore.deleteDraft(this, serverUrl, myUid, isGroup, targetId)
                sendMessageOptimistic(text)
            }
        }

        // 1. 0ms 秒加载本地永久 SQLite 聊天记录
        val localCached = SanctuaryCore.loadLocalChatPage(this, serverUrl, myUid, isGroup, targetId, Long.MAX_VALUE, 50)
        if (localCached.isNotEmpty()) {
            messageList.addAll(localCached)
            localCached.forEach {
                if (it.syncStatus == ChatMessage.STATUS_SENT) {
                    loadedMids.add(it.mid)
                }
            }
            chatAdapter.notifyDataSetChanged()
            rvMessages.scrollToPosition(messageList.size - 1)
            localCached.lastOrNull { it.syncStatus == ChatMessage.STATUS_SENT }?.let {
                SanctuaryCore.markConversationRead(this, serverUrl, myUid, isGroup, targetId, it.mid)
            }
        }

        // 2. 增量同步最新消息 (只拉 after={localMaxSentMid})
        loadIncrementalHistory()
        observeRealtimeMessages()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面或切后台前立刻把当前草稿落盘，绝不丢失一个字
        draftSaveJob?.cancel()
        if (::etMessage.isInitialized) {
            val currentText = etMessage.text.toString()
            if (currentText.isBlank()) {
                SanctuaryCore.deleteDraft(this, serverUrl, myUid, isGroup, targetId)
            } else {
                SanctuaryCore.saveDraft(this, serverUrl, myUid, isGroup, targetId, currentText)
            }
        }
    }

    private fun setupDraftPersistence() {
        val savedDraft = SanctuaryCore.getDraft(this, serverUrl, myUid, isGroup, targetId)
        if (savedDraft.isNotEmpty()) {
            etMessage.setText(savedDraft)
            etMessage.setSelection(savedDraft.length)
        }

        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                draftSaveJob?.cancel()
                draftSaveJob = lifecycleScope.launch {
                    delay(250L)
                    if (text.isBlank()) {
                        SanctuaryCore.deleteDraft(this@ChatActivity, serverUrl, myUid, isGroup, targetId)
                    } else {
                        SanctuaryCore.saveDraft(this@ChatActivity, serverUrl, myUid, isGroup, targetId, text)
                    }
                }
            }
        })
    }

    private fun applyTheme() {
        val chatRoot = findViewById<LinearLayout>(R.id.chat_root)
        val topBar = findViewById<LinearLayout>(R.id.chat_topbar)
        val bottomBar = findViewById<LinearLayout>(R.id.chat_bottom_bar)
        val btnBack = findViewById<MaterialButton>(R.id.btn_back)
        val tvTitle = findViewById<TextView>(R.id.tv_chat_title)
        val tvSubtitle = findViewById<TextView>(R.id.tv_chat_subtitle)
        val cardInput = findViewById<MaterialCardView>(R.id.card_chat_input)
        val etInput = findViewById<EditText>(R.id.et_message)

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
            etInput.setTextColor(Color.parseColor("#F8FAFC"))
            etInput.setHintTextColor(Color.parseColor("#64748B"))
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
            etInput.setTextColor(Color.parseColor("#0F172A"))
            etInput.setHintTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun showMessageActionsDialog(msg: ChatMessage) {
        val options = when (msg.syncStatus) {
            ChatMessage.STATUS_FAILED -> arrayOf(
                "🔄 重新发送此消息",
                "📋 复制消息内容",
                "🗑️ 删除此失败消息"
            )
            ChatMessage.STATUS_SENDING -> arrayOf(
                "📋 复制消息内容"
            )
            else -> arrayOf(
                "📋 复制消息内容",
                "🗑️ 从服务器删除此条消息",
                "🧹 清空本机此会话缓存记录"
            )
        }
        AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(options) { _, which ->
                if (msg.syncStatus == ChatMessage.STATUS_FAILED) {
                    when (which) {
                        0 -> retryFailedMessage(msg)
                        1 -> copyText(msg.content)
                        2 -> removeLocalOnlyMessage(msg)
                    }
                } else if (msg.syncStatus == ChatMessage.STATUS_SENDING) {
                    copyText(msg.content)
                } else {
                    when (which) {
                        0 -> copyText(msg.content)
                        1 -> confirmDeleteServerMessage(msg)
                        2 -> {
                            SanctuaryCore.clearLocalChatHistory(this, serverUrl, myUid, isGroup, targetId)
                            messageList.clear()
                            loadedMids.clear()
                            chatAdapter.notifyDataSetChanged()
                            Toast.makeText(this, "已清空本地缓存（未删除服务器数据）", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VioletIM", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun removeLocalOnlyMessage(msg: ChatMessage) {
        val idx = messageList.indexOfFirst { it.mid == msg.mid }
        if (idx != -1) {
            messageList.removeAt(idx)
            chatAdapter.notifyItemRemoved(idx)
            SanctuaryCore.deleteLocalChatMessage(this, serverUrl, myUid, isGroup, targetId, msg.mid)
        }
    }

    private fun confirmDeleteServerMessage(msg: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle("删除消息")
            .setMessage("确定要向服务器请求删除这条消息吗？（删除后双方都将不可见）")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val (ok, tip) = SanctuaryCore.deleteServerMessage(this@ChatActivity, serverUrl, msg.mid)
                    Toast.makeText(this@ChatActivity, tip, Toast.LENGTH_SHORT).show()
                    if (ok) {
                        val idx = messageList.indexOfFirst { it.mid == msg.mid }
                        if (idx != -1) {
                            messageList.removeAt(idx)
                            loadedMids.remove(msg.mid)
                            chatAdapter.notifyItemRemoved(idx)
                            SanctuaryCore.deleteLocalChatMessage(this@ChatActivity, serverUrl, myUid, isGroup, targetId, msg.mid)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun observeRealtimeMessages() {
        lifecycleScope.launch {
            SanctuaryCore.incomingMessages.collect { event ->
                if (event.isGroup == isGroup && event.targetId == targetId) {
                    if (loadedMids.contains(event.mid)) return@collect

                    // 若是我刚乐观发出正在等待回执的消息，原地升格为正式服务器消息，避免出现两条重复气泡！
                    if (event.fromUid == myUid) {
                        val pendingIdx = messageList.indexOfFirst {
                            it.isSelf && it.syncStatus == ChatMessage.STATUS_SENDING && it.content == event.content
                        }
                        if (pendingIdx != -1) {
                            val pending = messageList[pendingIdx]
                            loadedMids.add(event.mid)
                            val realMsg = pending.copy(
                                mid = event.mid,
                                createdAt = event.createdAt,
                                syncStatus = ChatMessage.STATUS_SENT
                            )
                            messageList[pendingIdx] = realMsg
                            chatAdapter.notifyItemChanged(pendingIdx)
                            SanctuaryCore.promotePendingMessage(
                                this@ChatActivity, serverUrl, myUid, isGroup, targetId, pending.mid, realMsg
                            )
                            SanctuaryCore.markConversationRead(this@ChatActivity, serverUrl, myUid, isGroup, targetId, event.mid)
                            return@collect
                        }
                    }

                    if (loadedMids.add(event.mid)) {
                        val isSelf = (event.fromUid == myUid)
                        val senderName = if (isSelf) myName else SanctuaryCore.getDisplayName(event.fromUid)
                        val msg = ChatMessage(
                            mid = event.mid,
                            fromUid = event.fromUid,
                            fromName = senderName,
                            fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(event.fromUid),
                            content = event.content,
                            createdAt = event.createdAt,
                            isSelf = isSelf,
                            contentType = event.contentType,
                            syncStatus = ChatMessage.STATUS_SENT
                        )
                        // 插到还没发出去的本地临时气泡前面，保证时序永远整齐
                        val insertPos = messageList.indexOfFirst { it.syncStatus != ChatMessage.STATUS_SENT }
                        if (insertPos == -1) {
                            messageList.add(msg)
                            chatAdapter.notifyItemInserted(messageList.size - 1)
                            rvMessages.scrollToPosition(messageList.size - 1)
                        } else {
                            messageList.add(insertPos, msg)
                            chatAdapter.notifyItemInserted(insertPos)
                        }
                        SanctuaryCore.markConversationRead(this@ChatActivity, serverUrl, myUid, isGroup, targetId, event.mid)
                        SanctuaryCore.saveLocalChatMessages(this@ChatActivity, serverUrl, myUid, isGroup, targetId, listOf(msg))
                    }
                }
            }
        }
    }

    private fun loadIncrementalHistory() {
        val localMaxMid = messageList
            .filter { it.syncStatus == ChatMessage.STATUS_SENT }
            .maxOfOrNull { it.mid } ?: 0L
        val basePath = if (isGroup) "$serverUrl/api/group/$targetId/history" else "$serverUrl/api/user/$targetId/history"
        val url = if (localMaxMid > 0L) {
            "$basePath?limit=50&after=$localMaxMid"
        } else {
            "$basePath?limit=30"
        }

        lifecycleScope.launch {
            try {
                val (code, body) = SanctuaryCore.executeAuthorized(this@ChatActivity) { tk ->
                    Request.Builder()
                        .url(url)
                        .header("X-API-Key", tk)
                        .get()
                        .build()
                }

                if (code in 200..299 && body.startsWith("[")) {
                    val arr = JSONArray(body)
                    val newlyAdded = parseHistoryArray(arr)
                    if (newlyAdded.isNotEmpty()) {
                        messageList.addAll(newlyAdded)
                        messageList.sortBy { it.mid }
                        chatAdapter.notifyDataSetChanged()
                        rvMessages.scrollToPosition(messageList.size - 1)
                        SanctuaryCore.saveLocalChatMessages(this@ChatActivity, serverUrl, myUid, isGroup, targetId, newlyAdded)
                    }
                    messageList.lastOrNull { it.syncStatus == ChatMessage.STATUS_SENT }?.let {
                        SanctuaryCore.markConversationRead(this@ChatActivity, serverUrl, myUid, isGroup, targetId, it.mid)
                    }
                } else if (code !in 200..299 && messageList.isEmpty()) {
                    Toast.makeText(this@ChatActivity, "拉取历史失败 (HTTP $code)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (messageList.isEmpty()) {
                    Toast.makeText(this@ChatActivity, "获取历史失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadOlderHistory() {
        val oldestMid = messageList
            .filter { it.syncStatus == ChatMessage.STATUS_SENT }
            .minOfOrNull { it.mid } ?: return
        isLoadingOlder = true

        lifecycleScope.launch {
            try {
                val localOlder = SanctuaryCore.loadLocalChatPage(
                    this@ChatActivity, serverUrl, myUid, isGroup, targetId, oldestMid, 30
                ).filter { it.syncStatus != ChatMessage.STATUS_SENT || loadedMids.add(it.mid) }

                if (localOlder.isNotEmpty()) {
                    messageList.addAll(0, localOlder)
                    chatAdapter.notifyItemRangeInserted(0, localOlder.size)
                    isLoadingOlder = false
                    return@launch
                }

                val basePath = if (isGroup) "$serverUrl/api/group/$targetId/history" else "$serverUrl/api/user/$targetId/history"
                val url = "$basePath?limit=30&before=$oldestMid"
                val (code, body) = SanctuaryCore.executeAuthorized(this@ChatActivity) { tk ->
                    Request.Builder()
                        .url(url)
                        .header("X-API-Key", tk)
                        .get()
                        .build()
                }
                if (code in 200..299 && body.startsWith("[")) {
                    val arr = JSONArray(body)
                    if (arr.length() == 0) {
                        hasMoreOlder = false
                    } else {
                        val older = parseHistoryArray(arr).sortedBy { it.mid }
                        if (older.isNotEmpty()) {
                            messageList.addAll(0, older)
                            chatAdapter.notifyItemRangeInserted(0, older.size)
                            SanctuaryCore.saveLocalChatMessages(this@ChatActivity, serverUrl, myUid, isGroup, targetId, older)
                        } else {
                            hasMoreOlder = false
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isLoadingOlder = false
            }
        }
    }

    private fun parseHistoryArray(arr: JSONArray): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
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
                SanctuaryCore.updateGlobalMaxMid(this, myUid, mid)
                val isSelf = (fromUid == myUid)
                val senderName = if (isSelf) myName else SanctuaryCore.getDisplayName(fromUid)
                result.add(
                    ChatMessage(
                        mid = mid,
                        fromUid = fromUid,
                        fromName = senderName,
                        fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(fromUid),
                        content = content,
                        createdAt = createdAt,
                        isSelf = isSelf,
                        contentType = contentType,
                        syncStatus = ChatMessage.STATUS_SENT
                    )
                )
            }
        }
        return result
    }

    // ⚡ 乐观发送：0ms 上屏 + 入库，后台异步提交，不锁输入框！
    private fun sendMessageOptimistic(text: String) {
        val now = System.currentTimeMillis()
        val seq = tempSeq.incrementAndGet()
        val tempMid = ChatMessage.TEMP_MID_BASE + (now * 100L) + (seq % 100L)
        val optimisticMsg = ChatMessage(
            mid = tempMid,
            fromUid = myUid,
            fromName = myName,
            fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
            content = text,
            createdAt = now,
            isSelf = true,
            syncStatus = ChatMessage.STATUS_SENDING,
            localId = now
        )
        messageList.add(optimisticMsg)
        val pos = messageList.size - 1
        chatAdapter.notifyItemInserted(pos)
        rvMessages.scrollToPosition(pos)

        // 先落盘到永久 SQLite（即使突然杀进程也不会丢失消息）
        SanctuaryCore.saveLocalChatMessages(this, serverUrl, myUid, isGroup, targetId, listOf(optimisticMsg))
        dispatchSendRequest(optimisticMsg)
    }

    private fun retryFailedMessage(failedMsg: ChatMessage) {
        val idx = messageList.indexOfFirst { it.mid == failedMsg.mid }
        if (idx == -1) return
        val retryingMsg = failedMsg.copy(syncStatus = ChatMessage.STATUS_SENDING, createdAt = System.currentTimeMillis())
        messageList[idx] = retryingMsg
        chatAdapter.notifyItemChanged(idx)
        SanctuaryCore.updateLocalMessageStatus(this, serverUrl, myUid, isGroup, targetId, failedMsg.mid, ChatMessage.STATUS_SENDING)
        dispatchSendRequest(retryingMsg)
    }

    private fun dispatchSendRequest(pendingMsg: ChatMessage) {
        val sendUrl = if (isGroup) {
            "$serverUrl/api/group/$targetId/send"
        } else {
            "$serverUrl/api/user/$targetId/send"
        }

        lifecycleScope.launch {
            try {
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val (code, midStr) = SanctuaryCore.executeAuthorized(this@ChatActivity) { tk ->
                    Request.Builder()
                        .url(sendUrl)
                        .header("X-API-Key", tk)
                        .post(pendingMsg.content.toRequestBody(mediaType))
                        .build()
                }

                if (code in 200..299) {
                    val newMid = midStr.trim().toLongOrNull() ?: System.currentTimeMillis()
                    val curIdx = messageList.indexOfFirst { it.mid == pendingMsg.mid }

                    if (loadedMids.contains(newMid)) {
                        // SSE 已经抢先一步把真消息落位了，静默移除临时占位即可
                        if (curIdx != -1) {
                            messageList.removeAt(curIdx)
                            chatAdapter.notifyItemRemoved(curIdx)
                        }
                        SanctuaryCore.deleteLocalChatMessage(this@ChatActivity, serverUrl, myUid, isGroup, targetId, pendingMsg.mid)
                    } else {
                        loadedMids.add(newMid)
                        val realMsg = pendingMsg.copy(
                            mid = newMid,
                            syncStatus = ChatMessage.STATUS_SENT
                        )
                        if (curIdx != -1) {
                            messageList[curIdx] = realMsg
                            chatAdapter.notifyItemChanged(curIdx)
                        }
                        SanctuaryCore.promotePendingMessage(
                            this@ChatActivity, serverUrl, myUid, isGroup, targetId, pendingMsg.mid, realMsg
                        )
                    }
                    SanctuaryCore.updateGlobalMaxMid(this@ChatActivity, myUid, newMid)
                    SanctuaryCore.markConversationRead(this@ChatActivity, serverUrl, myUid, isGroup, targetId, newMid)
                } else {
                    markSendFailed(pendingMsg.mid)
                }
            } catch (_: Exception) {
                markSendFailed(pendingMsg.mid)
            }
        }
    }

    private fun markSendFailed(tempMid: Long) {
        val curIdx = messageList.indexOfFirst { it.mid == tempMid }
        if (curIdx != -1) {
            messageList[curIdx] = messageList[curIdx].copy(syncStatus = ChatMessage.STATUS_FAILED)
            chatAdapter.notifyItemChanged(curIdx)
        }
        SanctuaryCore.updateLocalMessageStatus(
            this, serverUrl, myUid, isGroup, targetId, tempMid, ChatMessage.STATUS_FAILED
        )
    }
}
