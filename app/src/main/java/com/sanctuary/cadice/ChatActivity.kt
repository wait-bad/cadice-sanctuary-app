package com.sanctuary.cadice

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
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
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var etMessage: EditText

    private lateinit var cardScrollBottom: MaterialCardView
    private lateinit var tvScrollBottom: TextView
    private lateinit var layoutReplyPreview: LinearLayout
    private lateinit var tvReplyPreview: TextView

    private var isLoadingOlder = false
    private var hasMoreOlder = true
    private var draftSaveJob: Job? = null
    private val tempSeq = AtomicLong(0L)
    private var replyingToMessage: ChatMessage? = null
    private var unreadNewBelowCount = 0

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            sendImageOptimistic(uri)
        }
    }

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

        cardScrollBottom = findViewById(R.id.card_scroll_bottom)
        tvScrollBottom = findViewById(R.id.tv_scroll_bottom)
        layoutReplyPreview = findViewById(R.id.layout_reply_preview)
        tvReplyPreview = findViewById(R.id.tv_reply_preview)

        applyTheme()

        findViewById<MaterialButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_chat_title).text = targetName

        val tvSubtitle = findViewById<TextView>(R.id.tv_chat_subtitle)
        tvSubtitle.text = if (isGroup) {
            "群组频道 · 右滑引用 · 长按操作"
        } else {
            val online = SanctuaryCore.isUserOnline(targetId)
            if (online) "🟢 当前在线 · 右滑引用 · 长按操作" else "⚪ 离线 · 右滑引用 · 长按操作"
        }

        rvMessages = findViewById(R.id.rv_messages)
        layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvMessages.layoutManager = layoutManager
        chatAdapter = ChatAdapter(
            context = this,
            scope = lifecycleScope,
            serverUrl = serverUrl,
            token = SanctuaryCore.activeToken.ifEmpty { token },
            isDarkTheme = isDarkTheme,
            fontScale = fontScale,
            onMessageLongClick = { msg -> showMessageActionsDialog(msg) },
            onRetryFailedClick = { failedMsg -> retryFailedMessage(failedMsg) },
            onImageClick = { imgMsg ->
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra("server_url", serverUrl)
                    putExtra("file_path", imgMsg.filePath)
                    putExtra("local_uri", imgMsg.localFileUri)
                })
            }
        )
        rvMessages.adapter = chatAdapter

        ItemTouchHelper(SwipeReplyCallback(chatAdapter) { msg ->
            startReplyingTo(msg)
        }).attachToRecyclerView(rvMessages)

        cardScrollBottom.setOnClickListener {
            unreadNewBelowCount = 0
            cardScrollBottom.visibility = View.GONE
            val total = chatAdapter.itemCount
            if (total > 0) {
                rvMessages.smoothScrollToPosition(total - 1)
            }
        }

        findViewById<MaterialButton>(R.id.btn_cancel_reply).setOnClickListener {
            clearReplyState()
        }

        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val total = chatAdapter.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val nearBottom = total == 0 || lastVisible >= total - 3
                if (nearBottom) {
                    unreadNewBelowCount = 0
                    if (cardScrollBottom.visibility == View.VISIBLE) {
                        cardScrollBottom.visibility = View.GONE
                    }
                } else if (total - 1 - lastVisible >= 5) {
                    updateScrollBottomBadge()
                }

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

        findViewById<MaterialButton>(R.id.btn_pick_image).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.btn_send).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                etMessage.setText("")
                SanctuaryCore.deleteDraft(this, serverUrl, myUid, isGroup, targetId)
                val replyTarget = replyingToMessage
                clearReplyState()
                sendMessageOptimistic(text, replyTarget)
            }
        }

        val localCached = SanctuaryCore.loadLocalChatPage(this, serverUrl, myUid, isGroup, targetId, Long.MAX_VALUE, 50)
        if (localCached.isNotEmpty()) {
            messageList.addAll(localCached)
            localCached.forEach {
                if (it.syncStatus == ChatMessage.STATUS_SENT) {
                    loadedMids.add(it.mid)
                }
            }
            submitRows(scrollToBottom = true)
            localCached.lastOrNull { it.syncStatus == ChatMessage.STATUS_SENT }?.let {
                SanctuaryCore.markConversationRead(this, serverUrl, myUid, isGroup, targetId, it.mid)
            }
        }

        loadIncrementalHistory()
        observeRealtimeMessages()
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.setCurrentRoom(this, isGroup, targetId)
    }

    override fun onPause() {
        super.onPause()
        NotificationHelper.clearCurrentRoom()
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

    private fun isUserNearBottom(): Boolean {
        val total = chatAdapter.itemCount
        if (total == 0) return true
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        return lastVisible == RecyclerView.NO_POSITION || lastVisible >= total - 3
    }

    private fun updateScrollBottomBadge() {
        tvScrollBottom.text = if (unreadNewBelowCount > 0) "↓ ${unreadNewBelowCount}条新消息" else "↓ 回到底部"
        cardScrollBottom.visibility = View.VISIBLE
    }

    private fun buildChatRows(messages: List<ChatMessage>): List<ChatListRow> {
        val rows = ArrayList<ChatListRow>(messages.size + 4)
        var lastDayKey = ""
        for (msg in messages) {
            val (dayKey, dayLabel) = SanctuaryCore.formatDayKeyAndLabel(msg.createdAt)
            if (dayKey != lastDayKey) {
                lastDayKey = dayKey
                rows.add(ChatListRow.DateDivider(dayKey, dayLabel))
            }
            rows.add(ChatListRow.MessageRow(msg))
        }
        return rows
    }

    private fun submitRows(scrollToBottom: Boolean, onCommitted: (() -> Unit)? = null) {
        val rows = buildChatRows(messageList)
        chatAdapter.submitList(rows) {
            if (scrollToBottom && rows.isNotEmpty()) {
                rvMessages.scrollToPosition(rows.size - 1)
                unreadNewBelowCount = 0
                cardScrollBottom.visibility = View.GONE
            }
            onCommitted?.invoke()
        }
    }

    private fun startReplyingTo(msg: ChatMessage) {
        replyingToMessage = msg
        val sender = if (msg.isSelf) myName else msg.fromName
        tvReplyPreview.text = "回复 $sender: ${msg.content.replace('\n', ' ')}"
        layoutReplyPreview.visibility = View.VISIBLE
        etMessage.requestFocus()
    }

    private fun clearReplyState() {
        replyingToMessage = null
        layoutReplyPreview.visibility = View.GONE
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
            layoutReplyPreview.setBackgroundColor(bar)
            tvReplyPreview.setTextColor(Color.parseColor("#CBD5E1"))
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
            layoutReplyPreview.setBackgroundColor(bar)
            tvReplyPreview.setTextColor(Color.parseColor("#475569"))
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
                "↩️ 引用回复",
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
                        0 -> startReplyingTo(msg)
                        1 -> copyText(msg.content)
                        2 -> confirmDeleteServerMessage(msg)
                        3 -> {
                            SanctuaryCore.clearLocalChatHistory(this, serverUrl, myUid, isGroup, targetId)
                            messageList.clear()
                            loadedMids.clear()
                            submitRows(scrollToBottom = false)
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
            submitRows(scrollToBottom = false)
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
                            submitRows(scrollToBottom = false)
                            SanctuaryCore.deleteLocalChatMessage(this@ChatActivity, serverUrl, myUid, isGroup, targetId, msg.mid)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resolveReplyPreviewText(replyToMid: Long): String {
        if (replyToMid <= 0L) return ""
        val inMem = messageList.firstOrNull { it.mid == replyToMid }
        if (inMem != null) {
            val sender = if (inMem.isSelf) myName else inMem.fromName
            return "$sender: ${inMem.content}"
        }
        return SanctuaryDb.getInstance(this).getMessageContentByMid(serverUrl, myUid, isGroup, targetId, replyToMid)
    }

    private fun observeRealtimeMessages() {
        lifecycleScope.launch {
            SanctuaryCore.incomingMessages.collect { event ->
                if (event.isGroup == isGroup && event.targetId == targetId) {
                    if (loadedMids.contains(event.mid)) return@collect

                    if (event.fromUid == myUid) {
                        val pendingIdx = messageList.indexOfFirst {
                            it.isSelf && it.syncStatus == ChatMessage.STATUS_SENDING &&
                                (it.content == event.content || (it.isImageMessage && event.contentType == "vocechat/file"))
                        }
                        if (pendingIdx != -1) {
                            val pending = messageList[pendingIdx]
                            loadedMids.add(event.mid)
                            val realMsg = pending.copy(
                                mid = event.mid,
                                createdAt = event.createdAt,
                                syncStatus = ChatMessage.STATUS_SENT,
                                filePath = event.filePath.ifBlank { pending.filePath }
                            )
                            messageList[pendingIdx] = realMsg
                            submitRows(scrollToBottom = isUserNearBottom())
                            SanctuaryCore.promotePendingMessage(
                                this@ChatActivity, serverUrl, myUid, isGroup, targetId, pending.mid, realMsg
                            )
                            SanctuaryCore.markConversationRead(this@ChatActivity, serverUrl, myUid, isGroup, targetId, event.mid)
                            return@collect
                        }
                    }

                    if (loadedMids.add(event.mid)) {
                        val wasAtBottom = isUserNearBottom()
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
                            syncStatus = ChatMessage.STATUS_SENT,
                            replyToMid = event.replyToMid,
                            replyPreview = resolveReplyPreviewText(event.replyToMid),
                            filePath = event.filePath,
                            imageWidth = event.imageWidth,
                            imageHeight = event.imageHeight
                        )
                        val insertPos = messageList.indexOfFirst { it.syncStatus != ChatMessage.STATUS_SENT }
                        if (insertPos == -1) {
                            messageList.add(msg)
                        } else {
                            messageList.add(insertPos, msg)
                        }
                        val autoScroll = isSelf || wasAtBottom
                        if (!autoScroll) {
                            unreadNewBelowCount++
                            updateScrollBottomBadge()
                        }
                        submitRows(scrollToBottom = autoScroll)
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
                        submitRows(scrollToBottom = isUserNearBottom())
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
                    submitRows(scrollToBottom = false)
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
                            submitRows(scrollToBottom = false)
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
        val tempByMid = messageList.associateBy { it.mid }.toMutableMap()
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
            val replyToMid = if (detail?.optString("type") == "reply") detail.optLong("mid", 0L) else 0L
            val filePath = if (contentType == "vocechat/file") detail?.optString("content", "") ?: "" else ""
            val props = detail?.optJSONObject("properties")
            val imgW = props?.optInt("width", 0) ?: 0
            val imgH = props?.optInt("height", 0) ?: 0

            if (mid != -1L && content.isNotEmpty() && loadedMids.add(mid)) {
                SanctuaryCore.updateGlobalMaxMid(this, myUid, mid)
                val isSelf = (fromUid == myUid)
                val senderName = if (isSelf) myName else SanctuaryCore.getDisplayName(fromUid)
                val replyPreview = if (replyToMid > 0L) {
                    val parent = tempByMid[replyToMid]
                    if (parent != null) "${parent.fromName}: ${parent.content}" else resolveReplyPreviewText(replyToMid)
                } else ""
                val parsed = ChatMessage(
                    mid = mid,
                    fromUid = fromUid,
                    fromName = senderName,
                    fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(fromUid),
                    content = content,
                    createdAt = createdAt,
                    isSelf = isSelf,
                    contentType = contentType,
                    syncStatus = ChatMessage.STATUS_SENT,
                    replyToMid = replyToMid,
                    replyPreview = replyPreview,
                    filePath = filePath,
                    imageWidth = imgW,
                    imageHeight = imgH
                )
                tempByMid[mid] = parsed
                result.add(parsed)
            }
        }
        return result
    }

    private fun sendMessageOptimistic(text: String, replyTo: ChatMessage?) {
        val now = System.currentTimeMillis()
        val seq = tempSeq.incrementAndGet()
        val tempMid = ChatMessage.TEMP_MID_BASE + (now * 100L) + (seq % 100L)
        val replyMid = replyTo?.mid ?: 0L
        val replyPreview = if (replyTo != null) {
            val sender = if (replyTo.isSelf) myName else replyTo.fromName
            "$sender: ${replyTo.content}"
        } else ""

        val optimisticMsg = ChatMessage(
            mid = tempMid,
            fromUid = myUid,
            fromName = myName,
            fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
            content = text,
            createdAt = now,
            isSelf = true,
            syncStatus = ChatMessage.STATUS_SENDING,
            localId = now,
            replyToMid = replyMid,
            replyPreview = replyPreview
        )
        messageList.add(optimisticMsg)
        submitRows(scrollToBottom = true)

        SanctuaryCore.saveLocalChatMessages(this, serverUrl, myUid, isGroup, targetId, listOf(optimisticMsg))
        dispatchSendText(optimisticMsg)
    }

    private fun sendImageOptimistic(uri: Uri) {
        lifecycleScope.launch {
            val compressed = withContext(Dispatchers.IO) {
                MediaHelper.compressAndStripExif(this@ChatActivity, uri)
            }
            if (compressed == null) {
                Toast.makeText(this@ChatActivity, "无法处理所选图片", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val now = System.currentTimeMillis()
            val seq = tempSeq.incrementAndGet()
            val tempMid = ChatMessage.TEMP_MID_BASE + (now * 100L) + (seq % 100L)
            val optimisticImg = ChatMessage(
                mid = tempMid,
                fromUid = myUid,
                fromName = myName,
                fromAvatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
                content = "[图片]",
                createdAt = now,
                isSelf = true,
                contentType = "vocechat/file",
                syncStatus = ChatMessage.STATUS_SENDING,
                localId = now,
                localFileUri = compressed.localCacheFile.absolutePath,
                imageWidth = compressed.width,
                imageHeight = compressed.height
            )
            messageList.add(optimisticImg)
            submitRows(scrollToBottom = true)

            val (ok, realMid, serverPath) = SanctuaryCore.uploadAndSendImageQueued(
                this@ChatActivity, serverUrl, isGroup, targetId, compressed
            )
            if (ok && realMid > 0L) {
                promoteSentMessage(optimisticImg.mid, realMid, serverPath)
            } else {
                markSendFailed(optimisticImg.mid)
            }
        }
    }

    private fun retryFailedMessage(failedMsg: ChatMessage) {
        val idx = messageList.indexOfFirst { it.mid == failedMsg.mid }
        if (idx == -1) return
        val retryingMsg = failedMsg.copy(syncStatus = ChatMessage.STATUS_SENDING, createdAt = System.currentTimeMillis())
        messageList[idx] = retryingMsg
        submitRows(scrollToBottom = false)
        SanctuaryCore.updateLocalMessageStatus(this, serverUrl, myUid, isGroup, targetId, failedMsg.mid, ChatMessage.STATUS_SENDING)
        dispatchSendText(retryingMsg)
    }

    private fun dispatchSendText(pendingMsg: ChatMessage) {
        lifecycleScope.launch {
            val (ok, realMid) = SanctuaryCore.sendTextQueued(
                context = this@ChatActivity,
                serverUrl = serverUrl,
                isGroup = isGroup,
                targetId = targetId,
                text = pendingMsg.content,
                replyToMid = pendingMsg.replyToMid
            )
            if (ok && realMid > 0L) {
                promoteSentMessage(pendingMsg.mid, realMid, "")
            } else {
                markSendFailed(pendingMsg.mid)
            }
        }
    }

    private fun promoteSentMessage(tempMid: Long, realMid: Long, serverPath: String) {
        val curIdx = messageList.indexOfFirst { it.mid == tempMid }
        if (loadedMids.contains(realMid)) {
            if (curIdx != -1) {
                messageList.removeAt(curIdx)
                submitRows(scrollToBottom = false)
            }
            SanctuaryCore.deleteLocalChatMessage(this, serverUrl, myUid, isGroup, targetId, tempMid)
        } else if (curIdx != -1) {
            loadedMids.add(realMid)
            val realMsg = messageList[curIdx].copy(
                mid = realMid,
                syncStatus = ChatMessage.STATUS_SENT,
                filePath = serverPath.ifBlank { messageList[curIdx].filePath }
            )
            messageList[curIdx] = realMsg
            submitRows(scrollToBottom = false)
            SanctuaryCore.promotePendingMessage(this, serverUrl, myUid, isGroup, targetId, tempMid, realMsg)
        }
        SanctuaryCore.updateGlobalMaxMid(this, myUid, realMid)
        SanctuaryCore.markConversationRead(this, serverUrl, myUid, isGroup, targetId, realMid)
    }

    private fun markSendFailed(tempMid: Long) {
        val curIdx = messageList.indexOfFirst { it.mid == tempMid }
        if (curIdx != -1) {
            messageList[curIdx] = messageList[curIdx].copy(syncStatus = ChatMessage.STATUS_FAILED)
            submitRows(scrollToBottom = false)
        }
        SanctuaryCore.updateLocalMessageStatus(
            this, serverUrl, myUid, isGroup, targetId, tempMid, ChatMessage.STATUS_FAILED
        )
    }
}
