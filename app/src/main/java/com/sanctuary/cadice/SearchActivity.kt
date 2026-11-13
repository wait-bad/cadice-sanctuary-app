package com.sanctuary.cadice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🔍 全局离线聊天记录搜索（直查 SanctuaryDb 全库索引）
 */
class SearchActivity : AppCompatActivity() {

    private val results = mutableListOf<ConversationItem>()
    private lateinit var adapter: ConversationAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        val prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        val token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        val myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        val isDark = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)
        val fontScale = SanctuaryCore.getFontScale(prefs)

        applyTheme(isDark)

        findViewById<MaterialButton>(R.id.btn_search_back).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rv_search_results)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ConversationAdapter(
            context = this,
            scope = lifecycleScope,
            items = results,
            serverUrl = serverUrl,
            token = SanctuaryCore.activeToken.ifEmpty { token },
            isDarkTheme = isDark,
            fontScale = fontScale
        ) { item ->
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("is_group", item.isGroup)
                putExtra("target_id", item.id)
                putExtra("target_name", item.name)
            })
        }
        rv.adapter = adapter

        val tvHint = findViewById<TextView>(R.id.tv_search_hint)
        val etKeyword = findViewById<EditText>(R.id.et_search_keyword)
        etKeyword.requestFocus()
        etKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val kw = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                if (kw.isEmpty()) {
                    results.clear()
                    adapter.notifyDataSetChanged()
                    tvHint.text = "输入关键词，毫秒级检索全库离线聊天记录"
                    tvHint.visibility = View.VISIBLE
                    return
                }
                searchJob = lifecycleScope.launch {
                    delay(180L)
                    val matched = withContext(Dispatchers.IO) {
                        SanctuaryDb.getInstance(this@SearchActivity)
                            .searchMessages(serverUrl, myUid, kw, 60)
                            .map { (conv, msg) ->
                                conv.copy(
                                    lastMsg = "${msg.fromName}: ${msg.content}",
                                    lastTime = msg.createdAt,
                                    unreadCount = 0,
                                    draftText = ""
                                )
                            }
                    }
                    results.clear()
                    results.addAll(matched)
                    adapter.notifyDataSetChanged()
                    if (matched.isEmpty()) {
                        tvHint.text = "未找到包含「$kw」的本地消息"
                        tvHint.visibility = View.VISIBLE
                    } else {
                        tvHint.visibility = View.GONE
                    }
                }
            }
        })
    }

    private fun applyTheme(isDark: Boolean) {
        val root = findViewById<LinearLayout>(R.id.search_root)
        val topbar = findViewById<LinearLayout>(R.id.search_topbar)
        val cardBox = findViewById<MaterialCardView>(R.id.card_search_box)
        val et = findViewById<EditText>(R.id.et_search_keyword)
        val btnBack = findViewById<MaterialButton>(R.id.btn_search_back)
        val insets = WindowCompat.getInsetsController(window, window.decorView)

        if (isDark) {
            val bg = Color.parseColor("#0F172A")
            val bar = Color.parseColor("#1E293B")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
            root.setBackgroundColor(bg)
            topbar.setBackgroundColor(bar)
            cardBox.setCardBackgroundColor(bg)
            cardBox.strokeColor = Color.parseColor("#334155")
            et.setTextColor(Color.parseColor("#F8FAFC"))
            btnBack.setTextColor(Color.parseColor("#F8FAFC"))
        } else {
            val bg = Color.parseColor("#F8FAFC")
            val bar = Color.parseColor("#FFFFFF")
            window.statusBarColor = bar
            window.navigationBarColor = bar
            insets.isAppearanceLightStatusBars = true
            insets.isAppearanceLightNavigationBars = true
            root.setBackgroundColor(bg)
            topbar.setBackgroundColor(bar)
            cardBox.setCardBackgroundColor(bg)
            cardBox.strokeColor = Color.parseColor("#E2E8F0")
            et.setTextColor(Color.parseColor("#0F172A"))
            btnBack.setTextColor(Color.parseColor("#0F172A"))
        }
    }
}
