package com.sanctuary.cadice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val PREFS_NAME = "cadice_sanctuary_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_UID = "user_uid"
        private const val KEY_IS_ADMIN = "is_admin"

        private const val DEFAULT_REMOTE_SERVER = "https://chat.waitb.sbs"
        private const val DEFAULT_LOCAL_SERVER = "http://192.168.1.20:3009"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 如果已存在有效登录态，直接秒跳主界面！
        val existingToken = prefs.getString(KEY_TOKEN, null)
        val existingUid = prefs.getInt(KEY_USER_UID, -1)
        if (!existingToken.isNullOrEmpty() && existingUid != -1) {
            goToHome()
            return
        }

        setContentView(R.layout.activity_main)

        val etServerUrl = findViewById<EditText>(R.id.et_server_url)
        val btnQuickRemote = findViewById<Button>(R.id.btn_quick_remote)
        val btnQuickLocal = findViewById<Button>(R.id.btn_quick_local)
        val btnPingServer = findViewById<Button>(R.id.btn_ping_server)
        val tvPingResult = findViewById<TextView>(R.id.tv_ping_result)

        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        // 回显已保存的服务器端点
        val savedServer = prefs.getString(KEY_SERVER_URL, DEFAULT_REMOTE_SERVER) ?: DEFAULT_REMOTE_SERVER
        etServerUrl.setText(savedServer)

        // 快速切换预设
        btnQuickRemote.setOnClickListener {
            etServerUrl.setText(DEFAULT_REMOTE_SERVER)
            saveServerUrl(DEFAULT_REMOTE_SERVER)
            Toast.makeText(this, "已选择公网隧道通道", Toast.LENGTH_SHORT).show()
        }

        btnQuickLocal.setOnClickListener {
            etServerUrl.setText(DEFAULT_LOCAL_SERVER)
            saveServerUrl(DEFAULT_LOCAL_SERVER)
            Toast.makeText(this, "已选择局域网内网通道", Toast.LENGTH_SHORT).show()
        }

        // 测速探针
        btnPingServer.setOnClickListener {
            val serverUrl = cleanUrl(etServerUrl.text.toString().trim())
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveServerUrl(serverUrl)

            tvPingResult.visibility = View.VISIBLE
            tvPingResult.setTextColor(0xFF38BDF8.toInt())
            tvPingResult.text = "⚡ 正在握手探测..."

            lifecycleScope.launch {
                val start = System.currentTimeMillis()
                try {
                    val req = Request.Builder()
                        .url("$serverUrl/api/admin/system/organization")
                        .get()
                        .build()

                    val response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                    val duration = System.currentTimeMillis() - start
                    val body = response.body?.string().orEmpty()

                    if (response.isSuccessful) {
                        val orgName = try {
                            JSONObject(body).optString("name", "VoceChat Server")
                        } catch (e: Exception) {
                            "VoceChat Server"
                        }
                        tvPingResult.setTextColor(0xFF10B981.toInt())
                        tvPingResult.text = "🟢 连接正常 · 延迟 ${duration}ms · 服务: $orgName"
                    } else {
                        tvPingResult.setTextColor(0xFFEF4444.toInt())
                        tvPingResult.text = "🔴 响应异常 (HTTP ${response.code})"
                    }
                } catch (e: Exception) {
                    tvPingResult.setTextColor(0xFFEF4444.toInt())
                    tvPingResult.text = "🔴 连接失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }

        // 登录认证并跳转
        btnLogin.setOnClickListener {
            val serverUrl = cleanUrl(etServerUrl.text.toString().trim())
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (serverUrl.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写完整服务器地址、邮箱和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveServerUrl(serverUrl)
            btnLogin.isEnabled = false
            btnLogin.text = "正在验证登录..."

            lifecycleScope.launch {
                try {
                    val payload = JSONObject().apply {
                        put("credential", JSONObject().apply {
                            put("type", "password")
                            put("email", email)
                            put("password", password)
                        })
                        put("device", "violet_android_client")
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

                        // 持久化 Token
                        prefs.edit()
                            .putString(KEY_TOKEN, token)
                            .putInt(KEY_USER_UID, uid)
                            .putString(KEY_USER_NAME, name)
                            .putBoolean(KEY_IS_ADMIN, isAdmin)
                            .apply()

                        Toast.makeText(this@MainActivity, "登录成功，欢迎 $name！", Toast.LENGTH_SHORT).show()
                        goToHome()
                    } else {
                        Toast.makeText(this@MainActivity, "登录失败 (HTTP ${response.code}): $body", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "连接异常: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    btnLogin.isEnabled = true
                    btnLogin.text = "登录系统"
                }
            }
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun cleanUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    private fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl(url)).apply()
    }
}
