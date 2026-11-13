package com.sanctuary.cadice

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var isDarkTheme = true
    private var isPasswordVisible = false
    private lateinit var flowerAnim: ObjectAnimator

    companion object {
        private const val PREFS_NAME = "cadice_sanctuary_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_HISTORY = "server_history"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_UID = "user_uid"
        private const val KEY_IS_ADMIN = "is_admin"

        private const val DEFAULT_REMOTE = "https://chat.waitb.sbs"
        private const val DEFAULT_LOCAL = "http://192.168.1.20:3009"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 若已有登录态，直接秒跳主界面！
        val existingToken = prefs.getString(KEY_TOKEN, null)
        val existingUid = prefs.getInt(KEY_USER_UID, -1)
        if (!existingToken.isNullOrEmpty() && existingUid != -1) {
            goToHome()
            return
        }

        setContentView(R.layout.activity_main)

        // 2. 绑定核心控件
        val tvRotatingFlower = findViewById<TextView>(R.id.tv_rotating_flower)
        val tvCurrentServerDisplay = findViewById<TextView>(R.id.tv_current_server_display)
        val btnOpenServerModal = findViewById<MaterialCardView>(R.id.btn_open_server_modal)

        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnTogglePwd = findViewById<TextView>(R.id.btn_toggle_pwd)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        val btnToggleTheme = findViewById<LinearLayout>(R.id.btn_toggle_theme)

        // 3. 启动小花缓慢自转动画 (12秒一圈，优雅从容)
        flowerAnim = ObjectAnimator.ofFloat(tvRotatingFlower, "rotation", 0f, 360f).apply {
            duration = 12000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        flowerAnim.start()

        // 4. 初始化并回显当前服务器
        val currentServer = prefs.getString(KEY_SERVER_URL, DEFAULT_REMOTE) ?: DEFAULT_REMOTE
        tvCurrentServerDisplay.text = formatServerDisplay(currentServer)

        // 5. 初始化明暗主题
        isDarkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        applyTheme(isDarkTheme)

        btnToggleTheme.setOnClickListener {
            isDarkTheme = !isDarkTheme
            prefs.edit().putBoolean(KEY_DARK_THEME, isDarkTheme).apply()
            applyTheme(isDarkTheme)
        }

        // 6. 密码明文/密文切换 (👁️)
        btnTogglePwd.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnTogglePwd.text = "👁️"
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                btnTogglePwd.text = "🙈"
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // 7. 点击弹出服务器配置窗口
        btnOpenServerModal.setOnClickListener {
            showServerSettingsDialog(tvCurrentServerDisplay)
        }

        // 8. 仿 QQ 极简登录认证
        btnLogin.setOnClickListener {
            val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_REMOTE) ?: DEFAULT_REMOTE
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请输入账号与密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "正在安全登录..."

            lifecycleScope.launch {
                try {
                    val payload = JSONObject().apply {
                        put("credential", JSONObject().apply {
                            put("type", "password")
                            put("email", email)
                            put("password", password)
                        })
                        put("device", "violet_mobile_client")
                    }

                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val req = Request.Builder()
                        .url("$serverUrl/api/token/login")
                        .post(payload.toString().toRequestBody(mediaType))
                        .build()

                    val response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                    val body = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val token = json.getString("token")
                        val userObj = json.getJSONObject("user")
                        val uid = userObj.getInt("uid")
                        val name = userObj.getString("name")
                        val isAdmin = userObj.optBoolean("is_admin", false)

                        // 永久安全保存凭证
                        prefs.edit()
                            .putString(KEY_TOKEN, token)
                            .putInt(KEY_USER_UID, uid)
                            .putString(KEY_USER_NAME, name)
                            .putBoolean(KEY_IS_ADMIN, isAdmin)
                            .apply()

                        Toast.makeText(this@MainActivity, "登录成功，欢迎 $name！🌸", Toast.LENGTH_SHORT).show()
                        goToHome()
                    } else {
                        Toast.makeText(this@MainActivity, "登录失败 (HTTP ${response.code})", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "网络或服务异常: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    btnLogin.isEnabled = true
                    btnLogin.text = "登 录"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::flowerAnim.isInitialized) {
            flowerAnim.cancel()
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun formatServerDisplay(url: String): String {
        return url.replace("https://", "").replace("http://", "").removeSuffix("/")
    }

    // 🎨 明暗双主题动态渲染引擎
    private fun applyTheme(dark: Boolean) {
        val rootLayout = findViewById<RelativeLayout>(R.id.root_layout)
        val tvBrandName = findViewById<TextView>(R.id.tv_brand_name)
        val tvBrandDesc = findViewById<TextView>(R.id.tv_brand_desc)
        val btnOpenServerModal = findViewById<MaterialCardView>(R.id.btn_open_server_modal)

        val layoutAccount = findViewById<LinearLayout>(R.id.layout_input_account)
        val layoutPassword = findViewById<LinearLayout>(R.id.layout_input_password)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)

        val tvThemeIcon = findViewById<TextView>(R.id.tv_theme_icon)
        val tvThemeText = findViewById<TextView>(R.id.tv_theme_text)

        if (dark) {
            // 深色星空主题
            rootLayout.setBackgroundColor(Color.parseColor("#0F172A"))
            tvBrandName.setTextColor(Color.parseColor("#F8FAFC"))
            tvBrandDesc.setTextColor(Color.parseColor("#94A3B8"))

            btnOpenServerModal.setCardBackgroundColor(Color.parseColor("#1E293B"))
            btnOpenServerModal.strokeColor = Color.parseColor("#334155")

            layoutAccount.setBackgroundColor(Color.parseColor("#1E293B"))
            layoutPassword.setBackgroundColor(Color.parseColor("#1E293B"))
            etEmail.setTextColor(Color.parseColor("#F8FAFC"))
            etEmail.setHintTextColor(Color.parseColor("#64748B"))
            etPassword.setTextColor(Color.parseColor("#F8FAFC"))
            etPassword.setHintTextColor(Color.parseColor("#64748B"))

            tvThemeIcon.text = "🌙"
            tvThemeText.text = "暗色模式"
            tvThemeText.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            // 清爽明亮主题
            rootLayout.setBackgroundColor(Color.parseColor("#F1F5F9"))
            tvBrandName.setTextColor(Color.parseColor("#0F172A"))
            tvBrandDesc.setTextColor(Color.parseColor("#64748B"))

            btnOpenServerModal.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            btnOpenServerModal.strokeColor = Color.parseColor("#CBD5E1")

            layoutAccount.setBackgroundColor(Color.parseColor("#FFFFFF"))
            layoutPassword.setBackgroundColor(Color.parseColor("#FFFFFF"))
            etEmail.setTextColor(Color.parseColor("#0F172A"))
            etEmail.setHintTextColor(Color.parseColor("#94A3B8"))
            etPassword.setTextColor(Color.parseColor("#0F172A"))
            etPassword.setHintTextColor(Color.parseColor("#94A3B8"))

            tvThemeIcon.text = "☀️"
            tvThemeText.text = "亮色模式"
            tvThemeText.setTextColor(Color.parseColor("#475569"))
        }
    }

    // 🌐 服务器配置窗口 (带历史列表、测试与选择)
    private fun showServerSettingsDialog(tvDisplayBadge: TextView) {
        val dialog = Dialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_server_settings, null)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etNewServer = dialogView.findViewById<EditText>(R.id.et_new_server)
        val btnAddServer = dialogView.findViewById<Button>(R.id.btn_add_server)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_dialog_close)
        val layoutServerList = dialogView.findViewById<LinearLayout>(R.id.layout_server_list)

        // 读取历史服务器列表
        val historyList = getServerHistory()

        fun refreshList() {
            layoutServerList.removeAllViews()
            val currentSelected = prefs.getString(KEY_SERVER_URL, DEFAULT_REMOTE) ?: DEFAULT_REMOTE

            for (serverUrl in historyList) {
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_server_entry, layoutServerList, false)
                val tvUrl = itemView.findViewById<TextView>(R.id.tv_entry_url)
                val tvStatus = itemView.findViewById<TextView>(R.id.tv_entry_status)
                val btnTest = itemView.findViewById<Button>(R.id.btn_entry_test)
                val btnSelect = itemView.findViewById<Button>(R.id.btn_entry_select)

                tvUrl.text = serverUrl
                if (serverUrl == currentSelected) {
                    tvStatus.text = "🟢 当前使用中"
                    tvStatus.setTextColor(Color.parseColor("#10B981"))
                } else {
                    tvStatus.text = "未测试连通性"
                    tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                }

                // 【测试】小按钮
                btnTest.setOnClickListener {
                    tvStatus.text = "⚡ 测速中..."
                    tvStatus.setTextColor(Color.parseColor("#38BDF8"))

                    lifecycleScope.launch {
                        val start = System.currentTimeMillis()
                        try {
                            val req = Request.Builder()
                                .url("$serverUrl/api/admin/system/organization")
                                .get()
                                .build()
                            val response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                            val duration = System.currentTimeMillis() - start
                            if (response.isSuccessful) {
                                tvStatus.text = "🟢 连通正常 (${duration}ms)"
                                tvStatus.setTextColor(Color.parseColor("#10B981"))
                            } else {
                                tvStatus.text = "🔴 状态异常 (${response.code})"
                                tvStatus.setTextColor(Color.parseColor("#EF4444"))
                            }
                        } catch (e: Exception) {
                            tvStatus.text = "🔴 失败: ${e.localizedMessage ?: "超时"}"
                            tvStatus.setTextColor(Color.parseColor("#EF4444"))
                        }
                    }
                }

                // 【选择】小按钮 -> 立即保存并退出弹窗
                btnSelect.setOnClickListener {
                    prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()
                    tvDisplayBadge.text = formatServerDisplay(serverUrl)
                    Toast.makeText(this@MainActivity, "已切换服务器：$serverUrl", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }

                layoutServerList.addView(itemView)
            }
        }

        refreshList()

        // 添加新服务器
        btnAddServer.setOnClickListener {
            val input = etNewServer.text.toString().trim().removeSuffix("/")
            if (input.isNotEmpty() && (input.startsWith("http://") || input.startsWith("https://"))) {
                if (!historyList.contains(input)) {
                    historyList.add(input)
                    saveServerHistory(historyList)
                    etNewServer.setText("")
                    refreshList()
                    Toast.makeText(this, "已添加至常用列表", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "请输入以 http:// 或 https:// 开头的完整地址", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun getServerHistory(): MutableList<String> {
        val jsonStr = prefs.getString(KEY_SERVER_HISTORY, null)
        val list = mutableListOf<String>()
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        if (list.isEmpty()) {
            list.add(DEFAULT_REMOTE)
            list.add(DEFAULT_LOCAL)
        }
        return list
    }

    private fun saveServerHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SERVER_HISTORY, arr.toString()).apply()
    }
}
