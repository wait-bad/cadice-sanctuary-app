package com.sanctuary.cadice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout

    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1
    private var myName: String = ""
    private var isDarkTheme: Boolean = true
    private var fontScale: Float = 1.0f

    private val conversationList = mutableListOf<ConversationItem>()
    private val contactList = mutableListOf<ConversationItem>()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var contactAdapter: ConversationAdapter

    private var activeIsGroup: Boolean? = null
    private var activeTargetId: Int = -1

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        myName = prefs.getString(SanctuaryCore.KEY_USER_NAME, "") ?: ""
        isDarkTheme = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)
        fontScale = SanctuaryCore.getFontScale(prefs)

        if (token.isEmpty() || serverUrl.isEmpty() || myUid == -1) {
            prefs.edit().remove(SanctuaryCore.KEY_TOKEN).remove(SanctuaryCore.KEY_USER_UID).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        SanctuaryCore.activeToken = token
        drawerLayout = findViewById(R.id.drawer_layout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<View>(R.id.card_my_avatar).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_open_drawer_title).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val tvSubtitle = findViewById<TextView>(R.id.tv_home_subtitle)
        val cleanHost = serverUrl.removePrefix("https://").removePrefix("http://")
        tvSubtitle.text = "🟢 $myName · $cleanHost"

        findViewById<TextView>(R.id.tv_drawer_name).text = myName
        findViewById<TextView>(R.id.tv_drawer_server).text = "🌐 $cleanHost (UID: $myUid)"

        findViewById<View>(R.id.btn_drawer_theme).setOnClickListener {
            isDarkTheme = !isDarkTheme
            prefs.edit().putBoolean(SanctuaryCore.KEY_DARK_THEME, isDarkTheme).apply()
            applyTheme()
            conversationAdapter.updateStyle(isDarkTheme, fontScale, serverUrl, SanctuaryCore.activeToken.ifEmpty { token })
            contactAdapter.updateStyle(isDarkTheme, fontScale, serverUrl, SanctuaryCore.activeToken.ifEmpty { token })
        }

        findViewById<View>(R.id.btn_drawer_settings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_home_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_home_refresh).setOnClickListener {
            loadData(showSpinner = true)
        }
        findViewById<MaterialButton>(R.id.btn_home_logout).setOnClickListener {
            logoutToLogin("已退出登录")
        }

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
            isDarkTheme = isDarkTheme,
            fontScale = fontScale
        ) { item ->
            openChat(item)
        }
        contactAdapter = ConversationAdapter(
            context = this,
            scope = lifecycleScope,
            items = contactList,
            serverUrl = serverUrl,
            token = token,
            isDarkTheme = isDarkTheme,
            fontScale = fontScale
        ) { item ->
            openChat(item)
        }

        rvConversations.adapter = conversationAdapter
        rvContacts.adapter = contactAdapter

        applyTheme()
        setupNavigation()
        observeRealtimeEvents()

        NotificationHelper.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        SanctuaryCore.startSse(this, serverUrl, myUid)
        SanctuaryKeepAliveService.syncWithPreference(this)
        val cached = SanctuaryCore.loadHomeSnapshot(this, serverUrl, myUid)
        if (cached != null) {
            conversationList.clear()
            conversationList.addAll(reconcileUnreadWithLocalCursor(cached.first))
            contactList.clear()
            contactList.addAll(cached.second)
            updateMyAvatars()
            updateContactsHeaderCount()
            conversationAdapter.notifyDataSetChanged()
            contactAdapter.notifyDataSetChanged()
            loadData(showSpinner = false)
        } else {
            loadData(showSpinner = true)
        }
    }

    override fun onResume() {
        super.onResume()
        activeIsGroup = null
        activeTargetId = -1

        val newDark = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)
        val newFontScale = SanctuaryCore.getFontScale(prefs)
        val latestToken = prefs.getString(SanctuaryCore.KEY_TOKEN, token) ?: token
        token = latestToken

        // 从聊天室返回时重新校准一次本地已读游标，保证未读红点绝不会残留！
        val reconciled = reconcileUnreadWithLocalCursor(conversationList)
        val unreadChanged = reconciled != conversationList
        if (unreadChanged) {
            conversationList.clear()
            conversationList.addAll(reconciled)
            conversationAdapter.notifyDataSetChanged()
            SanctuaryCore.saveHomeSnapshot(this, serverUrl, myUid, conversationList)
        }

        if (newDark != isDarkTheme || newFontScale != fontScale) {
            isDarkTheme = newDark
            fontScale = newFontScale
            applyTheme()
            conversationAdapter.updateStyle(isDarkTheme, fontScale, serverUrl, latestToken)
            contactAdapter.updateStyle(isDarkTheme, fontScale, serverUrl, latestToken)
        }
        updateMyAvatars()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !prefs.getBoolean(SanctuaryCore.KEY_KEEP_ALIVE, false)) {
            SanctuaryCore.stopSse()
        }
    }

    private fun reconcileUnreadWithLocalCursor(items: List<ConversationItem>): List<ConversationItem> {
        val drafts = SanctuaryCore.getAllDrafts(this, serverUrl, myUid)
        return items.map { item ->
            val readMid = SanctuaryCore.getLastReadMid(this, myUid, item.isGroup, item.id)
            val draft = drafts[Pair(item.isGroup, item.id)] ?: ""
            val fixedUnread = if (item.lastMid > 0L && item.lastMid <= readMid && item.unreadCount > 0) 0 else item.unreadCount
            val liveOnline = if (item.isGroup) false else SanctuaryCore.isUserOnline(item.id)
            if (fixedUnread != item.unreadCount || draft != item.draftText || liveOnline != item.isOnline) {
                item.copy(unreadCount = fixedUnread, draftText = draft, isOnline = liveOnline)
            } else {
                item
            }
        }
    }

    private fun logoutToLogin(tip: String) {
        SanctuaryCore.clearSessionCache(this)
        prefs.edit()
            .remove(SanctuaryCore.KEY_TOKEN)
            .remove(SanctuaryCore.KEY_REFRESH_TOKEN)
            .remove(SanctuaryCore.KEY_TOKEN_OBTAINED_AT)
            .remove(SanctuaryCore.KEY_USER_UID)
            .remove(SanctuaryCore.KEY_USER_NAME)
            .apply()
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openChat(item: ConversationItem) {
        activeIsGroup = item.isGroup
        activeTargetId = item.id

        // 立刻标记该会话已读并同步本地游标 + 服务器 read-index
        val idx = conversationList.indexOfFirst { it.isGroup == item.isGroup && it.id == item.id }
        if (idx != -1) {
            val cur = conversationList[idx]
            if (cur.lastMid > 0L) {
                SanctuaryCore.markConversationRead(this, serverUrl, myUid, item.isGroup, item.id, cur.lastMid)
            }
            if (cur.unreadCount > 0) {
                conversationList[idx] = cur.copy(unreadCount = 0)
                conversationAdapter.notifyItemChanged(idx)
                SanctuaryCore.saveHomeSnapshot(this, serverUrl, myUid, conversationList)
            }
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
        val cardMyAvatar = findViewById<MaterialCardView>(R.id.card_my_avatar)

        val drawerContainer = findViewById<LinearLayout>(R.id.drawer_container)
        val drawerHeader = findViewById<LinearLayout>(R.id.drawer_header)
        val tvDrawerName = findViewById<TextView>(R.id.tv_drawer_name)
        val tvDrawerServer = findViewById<TextView>(R.id.tv_drawer_server)
        val btnDrawerTheme = findViewById<MaterialCardView>(R.id.btn_drawer_theme)
        val tvThemeIcon = findViewById<TextView>(R.id.tv_drawer_theme_icon)
        val tvThemeLabel = findViewById<TextView>(R.id.tv_drawer_theme_label)
        val tvThemeHint = findViewById<TextView>(R.id.tv_drawer_theme_hint)
        val btnDrawerSettings = findViewById<MaterialCardView>(R.id.btn_drawer_settings)
        val tvSettingsLabel = findViewById<TextView>(R.id.tv_drawer_settings_label)
        val tvSettingsHint = findViewById<TextView>(R.id.tv_drawer_settings_hint)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isDarkTheme) {
            val bg = Color.parseColor("#0F172A")
            val bar = Color.parseColor("#1E293B")
            val stroke = Color.parseColor("#334155")
            val textMain = Color.parseColor("#F8FAFC")
            val textSub = Color.parseColor("#94A3B8")

            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            homeRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            tvTitle.setTextColor(textMain)
            tvContactsHeader.setTextColor(Color.parseColor("#64748B"))
            cardMyAvatar.strokeColor = stroke

            drawerContainer.setBackgroundColor(bar)
            drawerHeader.setBackgroundColor(bg)
            tvDrawerName.setTextColor(textMain)
            tvDrawerServer.setTextColor(textSub)

            btnDrawerTheme.setCardBackgroundColor(bg)
            btnDrawerTheme.strokeColor = stroke
            tvThemeIcon.text = "🌙"
            tvThemeLabel.text = "夜间模式"
            tvThemeLabel.setTextColor(textMain)
            tvThemeHint.text = "点击切换日间"
            tvThemeHint.setTextColor(textSub)

            btnDrawerSettings.setCardBackgroundColor(bg)
            btnDrawerSettings.strokeColor = stroke
            tvSettingsLabel.setTextColor(textMain)
            tvSettingsHint.setTextColor(textSub)
        } else {
            val bg = Color.parseColor("#F8FAFC")
            val bar = Color.parseColor("#FFFFFF")
            val stroke = Color.parseColor("#E2E8F0")
            val textMain = Color.parseColor("#0F172A")
            val textSub = Color.parseColor("#64748B")

            window.statusBarColor = bar
            window.navigationBarColor = bar
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true

            homeRoot.setBackgroundColor(bg)
            topBar.setBackgroundColor(bar)
            bottomBar.setBackgroundColor(bar)
            tvTitle.setTextColor(textMain)
            tvContactsHeader.setTextColor(textSub)
            cardMyAvatar.strokeColor = stroke

            drawerContainer.setBackgroundColor(bar)
            drawerHeader.setBackgroundColor(bg)
            tvDrawerName.setTextColor(textMain)
            tvDrawerServer.setTextColor(textSub)

            btnDrawerTheme.setCardBackgroundColor(bg)
            btnDrawerTheme.strokeColor = stroke
            tvThemeIcon.text = "☀️"
            tvThemeLabel.text = "日间模式"
            tvThemeLabel.setTextColor(textMain)
            tvThemeHint.text = "点击切换夜间"
            tvThemeHint.setTextColor(textSub)

            btnDrawerSettings.setCardBackgroundColor(bg)
            btnDrawerSettings.strokeColor = stroke
            tvSettingsLabel.setTextColor(textMain)
            tvSettingsHint.setTextColor(textSub)
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

    private fun updateContactsHeaderCount() {
        val tvContactsHeader = findViewById<TextView>(R.id.tv_contacts_header)
        val groups = contactList.count { it.isGroup }
        val users = contactList.count { !it.isGroup }
        tvContactsHeader.text = "群聊与全员名录 · $groups 个群聊 / $users 位成员"
    }

    private fun observeRealtimeEvents() {
        lifecycleScope.launch {
            SanctuaryCore.authExpiredEvents.collect {
                logoutToLogin("登录凭证已失效，请重新登录")
            }
        }

        lifecycleScope.launch {
            val tvSubtitle = findViewById<TextView>(R.id.tv_home_subtitle)
            val cleanHost = serverUrl.removePrefix("https://").removePrefix("http://")
            SanctuaryCore.connectionStateEvents.collect { connected ->
                if (connected) {
                    tvSubtitle.text = "🟢 $myName · $cleanHost"
                    tvSubtitle.setTextColor(Color.parseColor("#10B981"))
                } else {
                    tvSubtitle.text = "🟠 网络重连中 · $cleanHost"
                    tvSubtitle.setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }
        lifecycleScope.launch {
            SanctuaryCore.userStateEvents.collect {
                updateMyAvatars()
                SanctuaryCore.prewarmAvatars(this@HomeActivity, serverUrl)

                var changed = false
                for (i in contactList.indices) {
                    val item = contactList[i]
                    if (!item.isGroup) {
                        val online = SanctuaryCore.isUserOnline(item.id)
                        val avTs = SanctuaryCore.getAvatarUpdatedAt(item.id)
                        val name = SanctuaryCore.getDisplayName(item.id)
                        if (item.isOnline != online || item.avatarUpdatedAt != avTs || item.name != name) {
                            contactList[i] = item.copy(name = name, isOnline = online, avatarUpdatedAt = avTs)
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
                        val name = SanctuaryCore.getDisplayName(item.id)
                        val readMid = SanctuaryCore.getLastReadMid(this@HomeActivity, myUid, false, item.id)
                        val newUnread = if (item.lastMid > 0L && item.lastMid <= readMid) 0 else item.unreadCount
                        if (item.isOnline != online || item.avatarUpdatedAt != avTs || item.name != name || item.unreadCount != newUnread) {
                            conversationList[i] = item.copy(
                                name = name,
                                isOnline = online,
                                avatarUpdatedAt = avTs,
                                unreadCount = newUnread
                            )
                            convChanged = true
                        }
                    } else {
                        val readMid = SanctuaryCore.getLastReadMid(this@HomeActivity, myUid, true, item.id)
                        if (item.lastMid > 0L && item.lastMid <= readMid && item.unreadCount > 0) {
                            conversationList[i] = item.copy(unreadCount = 0)
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
                val lastReadMid = SanctuaryCore.getLastReadMid(this@HomeActivity, myUid, event.isGroup, event.targetId)

                // 核心修复：只有真正高于持久化已读游标 (mid > lastReadMid)、且非自己发送、且未打开该会话的消息，才累加未读！
                // 若是重连回放 (isRealtime=false) 且本地尚无任何已读基准 (lastReadMid==0)，也不触发假未读！
                val shouldIncrementUnread = event.fromUid != myUid && !isCurrentlyOpen &&
                    event.mid > lastReadMid && (event.isRealtime || lastReadMid > 0L)

                if (isCurrentlyOpen) {
                    SanctuaryCore.markConversationRead(
                        this@HomeActivity, serverUrl, myUid, event.isGroup, event.targetId, event.mid
                    )
                }

                if (idx != -1) {
                    val old = conversationList[idx]
                    if (event.mid > old.lastMid) {
                        val updated = old.copy(
                            lastMsg = preview,
                            lastTime = event.createdAt,
                            lastMid = event.mid,
                            unreadCount = if (shouldIncrementUnread) old.unreadCount + 1 else old.unreadCount
                        )
                        conversationList.removeAt(idx)
                        conversationList.add(0, updated)
                        conversationAdapter.notifyDataSetChanged()
                        SanctuaryCore.saveHomeSnapshot(this@HomeActivity, serverUrl, myUid, conversationList)
                    }
                } else {
                    val name = if (event.isGroup) "群聊 #${event.targetId}" else SanctuaryCore.getDisplayName(event.targetId)
                    val newItem = ConversationItem(
                        id = event.targetId,
                        isGroup = event.isGroup,
                        name = name,
                        description = if (event.isGroup) "群聊频道" else "VoceChat 成员",
                        lastMsg = preview,
                        lastTime = event.createdAt,
                        lastMid = event.mid,
                        unreadCount = if (shouldIncrementUnread) 1 else 0,
                        avatarUpdatedAt = if (event.isGroup) 0L else SanctuaryCore.getAvatarUpdatedAt(event.targetId),
                        isOnline = if (event.isGroup) false else SanctuaryCore.isUserOnline(event.targetId),
                        badge = if (event.isGroup) "群聊" else "成员"
                    )
                    conversationList.add(0, newItem)
                    conversationAdapter.notifyItemInserted(0)
                    findViewById<TextView>(R.id.tv_empty_chats).visibility = View.GONE
                    SanctuaryCore.saveHomeSnapshot(this@HomeActivity, serverUrl, myUid, conversationList)
                }
            }
        }
    }

    private fun updateMyAvatars() {
        val myAvatarTs = SanctuaryCore.getAvatarUpdatedAt(myUid)
        val fallback = myName.take(1).ifBlank { "我" }
        val activeTk = SanctuaryCore.activeToken.ifEmpty { token }

        SanctuaryCore.loadAvatar(
            context = this,
            scope = lifecycleScope,
            serverUrl = serverUrl,
            token = activeTk,
            isGroup = false,
            id = myUid,
            avatarUpdatedAt = myAvatarTs,
            fallbackText = fallback,
            ivAvatar = findViewById(R.id.iv_my_avatar),
            tvFallback = findViewById(R.id.tv_my_avatar)
        )
        SanctuaryCore.loadAvatar(
            context = this,
            scope = lifecycleScope,
            serverUrl = serverUrl,
            token = activeTk,
            isGroup = false,
            id = myUid,
            avatarUpdatedAt = myAvatarTs,
            fallbackText = fallback,
            ivAvatar = findViewById(R.id.iv_drawer_avatar),
            tvFallback = findViewById(R.id.tv_drawer_avatar)
        )
    }

    private fun loadData(showSpinner: Boolean) {
        val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty_chats)

        if (showSpinner && conversationList.isEmpty()) {
            pbLoading.visibility = View.VISIBLE
        }
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val (baseConvs, contacts) = SanctuaryCore.fetchBaseDirectory(
                    context = this@HomeActivity,
                    serverUrl = serverUrl,
                    myUid = myUid
                )

                val oldMap = conversationList.associateBy { Pair(it.isGroup, it.id) }
                val mergedBase = baseConvs.map { item ->
                    val prev = oldMap[Pair(item.isGroup, item.id)]
                    if (prev != null && (prev.lastMid > item.lastMid || prev.lastTime > item.lastTime)) {
                        item.copy(
                            lastMsg = prev.lastMsg,
                            lastTime = prev.lastTime,
                            lastMid = prev.lastMid,
                            unreadCount = prev.unreadCount
                        )
                    } else {
                        item
                    }
                }.sortedWith(
                    compareByDescending<ConversationItem> { it.lastTime }
                        .thenByDescending { it.isGroup }
                        .thenByDescending { it.badge == "好友" }
                        .thenBy { it.id }
                )

                if (mergedBase.isNotEmpty()) {
                    conversationList.clear()
                    conversationList.addAll(reconcileUnreadWithLocalCursor(mergedBase))
                    contactList.clear()
                    contactList.addAll(contacts)
                }

                pbLoading.visibility = View.GONE
                updateMyAvatars()
                updateContactsHeaderCount()
                SanctuaryCore.prewarmAvatars(this@HomeActivity, serverUrl)

                conversationAdapter.notifyDataSetChanged()
                contactAdapter.notifyDataSetChanged()

                if (conversationList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                }

                val hydrated = SanctuaryCore.hydrateLatestPreviews(
                    context = this@HomeActivity,
                    serverUrl = serverUrl,
                    myUid = myUid,
                    items = conversationList.toList()
                )
                val finalConvs = reconcileUnreadWithLocalCursor(hydrated)

                conversationList.clear()
                conversationList.addAll(finalConvs)
                conversationAdapter.notifyDataSetChanged()

                SanctuaryCore.saveHomeSnapshot(this@HomeActivity, serverUrl, myUid, finalConvs)
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "刷新失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                pbLoading.visibility = View.GONE
            }
        }
    }
}
