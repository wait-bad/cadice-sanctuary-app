package com.sanctuary.cadice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1
    private var myName: String = ""
    private var isDarkTheme: Boolean = true

    private val conversationList = mutableListOf<ConversationItem>()
    private val contactList = mutableListOf<ConversationItem>()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var contactAdapter: ConversationAdapter

    // 用来区分初始历史回放与真正的新到达消息，以及当前正在浏览的会话
    private var lastSyncCompletedAt: Long = 0L
    private var activeIsGroup: Boolean? = null
    private var activeTargetId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences("cadice_sanctuary_prefs", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        myUid = prefs.getInt("user_uid", -1)
        myName = prefs.getString("user_name", "") ?: ""
        isDarkTheme = prefs.getBoolean("dark_theme", true)

        if (token.isEmpty() || serverUrl.isEmpty() || myUid == -1) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        applyTheme()

        val tvSubtitle = findViewById<TextView>(R.id.tv_home_subtitle)
        tvSubtitle.text = "🟢 $myName · ${serverUrl.removePrefix("https://").removePrefix("http://")}"

        val btnRefresh = findViewById<MaterialButton>(R.id.btn_home_refresh)
        btnRefresh.setOnClickListener {
            loadData()
        }

        val btnLogout = findViewById<MaterialButton>(R.id.btn_home_logout)
        btnLogout.setOnClickListener {
            SanctuaryCore.clearSessionCache()
            prefs.edit()
                .remove("token")
                .remove("user_uid")
                .remove("user_name")
                .apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 初始化 RecyclerView
        val rvConversations = findViewById<RecyclerView>(R.id.rv_conversations)
        val rvContacts = findViewById<RecyclerView>(R.id.rv_contacts)

        rvConversations.layoutManager = LinearLayoutManager(this)
        rvContacts.layoutManager = LinearLayoutManager(this)

        conversationAdapter = ConversationAdapter(
            context = this,
            scope = lifecycleScope,
            items = conversationList,
            serverUrl = serverUrl,
            token = token,
            isDarkTheme = isDarkTheme
        ) { item ->
            openChat(item)
        }
        contactAdapter = ConversationAdapter(
            context = this,
            scope = lifecycleScope,
            items = contactList,
            serverUrl = serverUrl,
            token = token,
            isDarkTheme = isDarkTheme
        ) { item ->
            openChat(item)
        }

        rvConversations.adapter = conversationAdapter
        rvContacts.adapter = contactAdapter

        // 底部导航栏切换
        setupNavigation()

        // 订阅全局实时 SSE 事件流
        observeRealtimeEvents()
        SanctuaryCore.startSse(serverUrl, token, myUid)

        // 加载数据
        loadData()
    }

    override fun onResume() {
        super.onResume()
        activeIsGroup = null
        activeTargetId = -1
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            SanctuaryCore.stopSse()
        }
    }

    private fun openChat(item: ConversationItem) {
        activeIsGroup = item.isGroup
        activeTargetId = item.id

        // 清除当前会话未读数
        val idx = conversationList.indexOfFirst { it.isGroup == item.isGroup && it.id == item.id }
        if (idx != -1 && conversationList[idx].unreadCount > 0) {
            conversationList[idx] = conversationList[idx].copy(unreadCount = 0)
            conversationAdapter.notifyItemChanged(idx)
        }

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("is_group", item.isGroup)
            putExtra("target_id", item.id)
            putExtra("target_name", item.name)
        }
        startActivity(intent)
    }

    private fun applyTheme() {
        val homeRoot = findViewById<LinearLayout>(R.id.home_root)
        val topBar = findViewById<LinearLayout>(R.id.home_topbar)
        val bottomBar = findViewById<LinearLayout>(R.id.home_bottom_bar)
        val tvTitle = findViewById<TextView>(R.id.tv_home_title)
        val tvContactsHeader = findViewById<TextView>(R.id.tv_contacts_header)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isDarkTheme) {
            val bg = Color.parseColor("#0F172A")
            val bar = Color.parseColor("#1E293B")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            homeRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            tvTitle.setTextColor(Color.parseColor("#F8FAFC"))
            tvContactsHeader.setTextColor(Color.parseColor("#64748B"))
        } else {
            val bg = Color.parseColor("#F8FAFC")
            val bar = Color.parseColor("#FFFFFF")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true

            homeRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            tvTitle.setTextColor(Color.parseColor("#0F172A"))
            tvContactsHeader.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun setupNavigation() {
        val tabChats = findViewById<View>(R.id.layout_tab_chats)
        val tabContacts = findViewById<View>(R.id.layout_tab_contacts)
        val tabMoments = findViewById<View>(R.id.layout_tab_moments)

        val navChats = findViewById<View>(R.id.nav_chats)
        val navContacts = findViewById<View>(R.id.nav_contacts)
        val navMoments = findViewById<View>(R.id.nav_moments)

        val tvChatsText = findViewById<TextView>(R.id.tv_nav_chats_text)
        val tvContactsText = findViewById<TextView>(R.id.tv_nav_contacts_text)
        val tvMomentsText = findViewById<TextView>(R.id.tv_nav_moments_text)

        fun resetTabs() {
            tabChats.visibility = View.GONE
            tabContacts.visibility = View.GONE
            tabMoments.visibility = View.GONE

            tvChatsText.setTextColor(0xFF94A3B8.toInt())
            tvContactsText.setTextColor(0xFF94A3B8.toInt())
            tvMomentsText.setTextColor(0xFF94A3B8.toInt())
        }

        navChats.setOnClickListener {
            resetTabs()
            tabChats.visibility = View.VISIBLE
            tvChatsText.setTextColor(0xFF7C3AED.toInt())
        }

        navContacts.setOnClickListener {
            resetTabs()
            tabContacts.visibility = View.VISIBLE
            tvContactsText.setTextColor(0xFF7C3AED.toInt())
        }

        navMoments.setOnClickListener {
            resetTabs()
            tabMoments.visibility = View.VISIBLE
            tvMomentsText.setTextColor(0xFF7C3AED.toInt())
        }
    }

    private fun observeRealtimeEvents() {
        lifecycleScope.launch {
            SanctuaryCore.userStateEvents.collect {
                // 在线状态或用户头像时间戳更新，刷新列表与个人头像
                updateMyHeaderAvatar()
                var changed = false
                for (i in contactList.indices) {
                    val item = contactList[i]
                    if (!item.isGroup) {
                        val online = SanctuaryCore.isUserOnline(item.id)
                        val avTs = SanctuaryCore.getAvatarUpdatedAt(item.id)
                        if (item.isOnline != online || item.avatarUpdatedAt != avTs) {
                            contactList[i] = item.copy(isOnline = online, avatarUpdatedAt = avTs)
                            changed = true
                        }
                    }
                }
                if (changed) contactAdapter.notifyDataSetChanged()

                var convChanged = false
                for (i in conversationList.indices) {
                    val item = conversationList[i]
                    if (!item.isGroup) {
                        val online = SanctuaryCore.isUserOnline(item.id)
                        val avTs = SanctuaryCore.getAvatarUpdatedAt(item.id)
                        if (item.isOnline != online || item.avatarUpdatedAt != avTs) {
                            conversationList[i] = item.copy(isOnline = online, avatarUpdatedAt = avTs)
                            convChanged = true
                        }
                    }
                }
                if (convChanged) conversationAdapter.notifyDataSetChanged()
            }
        }

        lifecycleScope.launch {
            SanctuaryCore.incomingMessages.collect { event ->
                val idx = conversationList.indexOfFirst {
                    it.isGroup == event.isGroup && it.id == event.targetId
                }
                val preview = if (event.isGroup) {
                    "${SanctuaryCore.getDisplayName(event.fromUid)}: ${event.content}"
                } else {
                    event.content
                }
                val isCurrentlyOpen = (activeIsGroup == event.isGroup && activeTargetId == event.targetId)
                val isNewRealtime = event.createdAt > lastSyncCompletedAt &&
                    event.fromUid != myUid && !isCurrentlyOpen

                if (idx != -1) {
                    val old = conversationList[idx]
                    // 仅当新消息时间不早于已显示的最后一条时才更新并置顶
                    if (event.createdAt >= old.lastTime) {
                        val updated = old.copy(
                            lastMsg = preview,
                            lastTime = event.createdAt,
                            unreadCount = if (isNewRealtime) old.unreadCount + 1 else old.unreadCount
                        )
                        conversationList.removeAt(idx)
                        conversationList.add(0, updated)
                        conversationAdapter.notifyDataSetChanged()
                    }
                } else {
                    // 收到未在当前列表中的新好友/新群消息，动态追加到顶部
                    val name = if (event.isGroup) "群聊 #${event.targetId}" else SanctuaryCore.getDisplayName(event.targetId)
                    val newItem = ConversationItem(
                        id = event.targetId,
                        isGroup = event.isGroup,
                        name = name,
                        description = if (event.isGroup) "群聊频道" else "VoceChat 好友",
                        lastMsg = preview,
                        lastTime = event.createdAt,
                        unreadCount = if (isNewRealtime) 1 else 0,
                        avatarUpdatedAt = if (event.isGroup) 0L else SanctuaryCore.getAvatarUpdatedAt(event.targetId),
                        isOnline = if (event.isGroup) false else SanctuaryCore.isUserOnline(event.targetId),
                        badge = if (event.isGroup) "群聊" else "好友"
                    )
                    conversationList.add(0, newItem)
                    conversationAdapter.notifyItemInserted(0)
                    findViewById<TextView>(R.id.tv_empty_chats).visibility = View.GONE
                }
            }
        }
    }

    private fun updateMyHeaderAvatar() {
        val ivMyAvatar = findViewById<ImageView>(R.id.iv_my_avatar)
        val tvMyAvatar = findViewById<TextView>(R.id.tv_my_avatar)
        val myAvatarTs = SanctuaryCore.getAvatarUpdatedAt(myUid)
        SanctuaryCore.loadAvatar(
            context = this,
            scope = lifecycleScope,
            serverUrl = serverUrl,
            token = token,
            isGroup = false,
            id = myUid,
            avatarUpdatedAt = myAvatarTs,
            fallbackText = myName.take(1).ifBlank { "我" },
            ivAvatar = ivMyAvatar,
            tvFallback = tvMyAvatar
        )
    }

    private fun loadData() {
        val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty_chats)

        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val (conversations, contacts) = SanctuaryCore.syncAllAndBuildLists(
                    serverUrl = serverUrl,
                    token = token,
                    myUid = myUid
                )
                conversationList.clear()
                conversationList.addAll(conversations)
                contactList.clear()
                contactList.addAll(contacts)

                lastSyncCompletedAt = System.currentTimeMillis()
                updateMyHeaderAvatar()

                conversationAdapter.notifyDataSetChanged()
                contactAdapter.notifyDataSetChanged()

                if (conversationList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "拉取数据失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                pbLoading.visibility = View.GONE
            }
        }
    }
}
