package com.sanctuary.cadice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class HomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1
    private var myName: String = ""

    private val conversationList = mutableListOf<ConversationItem>()
    private val contactList = mutableListOf<ConversationItem>()

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var contactAdapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences("cadice_sanctuary_prefs", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server_url", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        myUid = prefs.getInt("user_uid", -1)
        myName = prefs.getString("user_name", "") ?: ""

        if (token.isEmpty() || serverUrl.isEmpty() || myUid == -1) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val tvSubtitle = findViewById<TextView>(R.id.tv_home_subtitle)
        tvSubtitle.text = "🟢 $myName (在线) · $serverUrl"

        val btnLogout = findViewById<Button>(R.id.btn_home_logout)
        btnLogout.setOnClickListener {
            prefs.edit().remove("token").apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 初始化 RecyclerView
        val rvConversations = findViewById<RecyclerView>(R.id.rv_conversations)
        val rvContacts = findViewById<RecyclerView>(R.id.rv_contacts)

        rvConversations.layoutManager = LinearLayoutManager(this)
        rvContacts.layoutManager = LinearLayoutManager(this)

        conversationAdapter = ConversationAdapter(conversationList) { item ->
            openChat(item)
        }
        contactAdapter = ConversationAdapter(contactList) { item ->
            openChat(item)
        }

        rvConversations.adapter = conversationAdapter
        rvContacts.adapter = contactAdapter

        // 底部导航栏切换
        setupNavigation()

        // 加载数据
        loadData()
    }

    private fun openChat(item: ConversationItem) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("is_group", item.isGroup)
            putExtra("target_id", item.id)
            putExtra("target_name", item.name)
        }
        startActivity(intent)
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

    private fun loadData() {
        val pbLoading = findViewById<ProgressBar>(R.id.pb_loading)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty_chats)

        pbLoading.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // 1. 获取群组列表
                val groupReq = Request.Builder()
                    .url("$serverUrl/api/group")
                    .header("X-API-Key", token)
                    .get()
                    .build()

                val groupRes = withContext(Dispatchers.IO) { client.newCall(groupReq).execute() }
                val groupBody = groupRes.body?.string().orEmpty()

                // 2. 获取联系人列表
                val contactReq = Request.Builder()
                    .url("$serverUrl/api/user/contacts")
                    .header("X-API-Key", token)
                    .get()
                    .build()

                val contactRes = withContext(Dispatchers.IO) { client.newCall(contactReq).execute() }
                val contactBody = contactRes.body?.string().orEmpty()

                conversationList.clear()
                contactList.clear()

                if (groupRes.isSuccessful && groupBody.isNotEmpty()) {
                    val groups = JSONArray(groupBody)
                    for (i in 0 until groups.length()) {
                        val g = groups.getJSONObject(i)
                        val gid = g.getInt("gid")
                        val name = g.getString("name")
                        val desc = g.optString("description", "群聊频道")
                        val item = ConversationItem(
                            id = gid,
                            isGroup = true,
                            name = name,
                            description = desc,
                            lastMsg = desc,
                            badge = "群组"
                        )
                        conversationList.add(item)
                        contactList.add(item)
                    }
                }

                if (contactRes.isSuccessful && contactBody.isNotEmpty()) {
                    val contacts = JSONArray(contactBody)
                    for (i in 0 until contacts.length()) {
                        val c = contacts.getJSONObject(i)
                        val targetUid = c.getInt("target_uid")
                        if (targetUid == myUid) continue // 跳过自己

                        val targetInfo = c.optJSONObject("target_info")
                        val name = targetInfo?.optString("name", "用户 $targetUid") ?: "用户 $targetUid"
                        val email = targetInfo?.optString("email", "") ?: ""

                        val item = ConversationItem(
                            id = targetUid,
                            isGroup = false,
                            name = name,
                            description = email.ifEmpty { "VoceChat 联系人" },
                            lastMsg = "点击开始私聊",
                            badge = "好友"
                        )
                        conversationList.add(item)
                        contactList.add(item)
                    }
                }

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
