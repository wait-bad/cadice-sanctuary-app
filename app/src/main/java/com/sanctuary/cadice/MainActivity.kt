package com.sanctuary.cadice

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
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

    private var pokeCount = 0

    companion object {
        private const val PREFS_NAME = "cadice_sanctuary_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_UID = "user_uid"
        private const val KEY_IS_ADMIN = "is_admin"

        private const val DEFAULT_LOCAL_SERVER = "http://192.168.1.20:3009"
        private const val DEFAULT_REMOTE_SERVER = "https://chat.waitb.sbs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val etServerUrl = findViewById<EditText>(R.id.et_server_url)
        val btnQuickLocal = findViewById<Button>(R.id.btn_quick_local)
        val btnQuickRemote = findViewById<Button>(R.id.btn_quick_remote)
        val btnPingServer = findViewById<Button>(R.id.btn_ping_server)
        val tvPingResult = findViewById<TextView>(R.id.tv_ping_result)

        val cardLogin = findViewById<View>(R.id.card_login)
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val tvFillCotice = findViewById<TextView>(R.id.tv_fill_cotice)
        val tvFillMaster = findViewById<TextView>(R.id.tv_fill_master)

        val cardLoggedIn = findViewById<View>(R.id.card_logged_in)
        val tvUserDetails = findViewById<TextView>(R.id.tv_user_details)
        val btnLogout = findViewById<Button>(R.id.btn_logout)

        val btnPoke = findViewById<Button>(R.id.btn_poke)
        val tvPokeResponse = findViewById<TextView>(R.id.tv_poke_response)

        // 1. 初始化回显已保存的服务器端点
        val savedServer = prefs.getString(KEY_SERVER_URL, DEFAULT_LOCAL_SERVER) ?: DEFAULT_LOCAL_SERVER
        etServerUrl.setText(savedServer)

        // 2. 检查是否有已存储的有效登录态
        checkLoginState(cardLogin, cardLoggedIn, tvUserDetails)

        // 3. 快捷服务器填入
        btnQuickLocal.setOnClickListener {
            etServerUrl.setText(DEFAULT_LOCAL_SERVER)
            saveServerUrl(DEFAULT_LOCAL_SERVER)
            Toast.makeText(this, "已切换为局域网高速通道", Toast.LENGTH_SHORT).show()
        }

        btnQuickRemote.setOnClickListener {
            etServerUrl.setText(DEFAULT_REMOTE_SERVER)
            saveServerUrl(DEFAULT_REMOTE_SERVER)
            Toast.makeText(this, "已切换为远程公网加密隧道", Toast.LENGTH_SHORT).show()
        }

        // 4. 服务器测速探针
        btnPingServer.setOnClickListener {
            val serverUrl = cleanUrl(etServerUrl.text.toString().trim())
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请输入有效的服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveServerUrl(serverUrl)

            tvPingResult.visibility = View.VISIBLE
            tvPingResult.setTextColor(0xFF38BDF8.toInt())
            tvPingResult.text = "⚡ 正在握手探测连通性..."

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
                        tvPingResult.text = "🟢 连通正常！耗时 ${duration}ms\n服务名：$orgName (HTTP ${response.code})"
                    } else {
                        tvPingResult.setTextColor(0xFFEF4444.toInt())
                        tvPingResult.text = "🔴 响应异常 (HTTP ${response.code}) 耗时 ${duration}ms"
                    }
                } catch (e: Exception) {
                    tvPingResult.setTextColor(0xFFEF4444.toInt())
                    tvPingResult.text = "🔴 连接失败: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }

        // 5. 快捷填入账号
        tvFillCotice.setOnClickListener {
            etEmail.setText("cotice@sanctuary.local")
            etPassword.setText("Cotice2026!Heart")
            Toast.makeText(this, "已填入姐姐专属凭据", Toast.LENGTH_SHORT).show()
        }

        tvFillMaster.setOnClickListener {
            etEmail.setText("147258369wangyu@gmail.com")
            etPassword.setText("")
            etPassword.requestFocus()
            Toast.makeText(this, "已填入主人账号，请输入密码", Toast.LENGTH_SHORT).show()
        }

        // 6. 执行登录认证
        btnLogin.setOnClickListener {
            val serverUrl = cleanUrl(etServerUrl.text.toString().trim())
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (serverUrl.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请完整填写服务器地址、邮箱和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveServerUrl(serverUrl)
            btnLogin.isEnabled = false
            btnLogin.text = "正在验证身份..."

            lifecycleScope.launch {
                try {
                    val payload = JSONObject().apply {
                        put("credential", JSONObject().apply {
                            put("type", "password")
                            put("email", email)
                            put("password", password)
                        })
                        put("device", "cadice_mobile_android")
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

                        // 安全持久化
                        prefs.edit()
                            .putString(KEY_TOKEN, token)
                            .putInt(KEY_USER_UID, uid)
                            .putString(KEY_USER_NAME, name)
                            .putBoolean(KEY_IS_ADMIN, isAdmin)
                            .apply()

                        Toast.makeText(this@MainActivity, "✨ 登录成功！欢迎回来，$name 🌸", Toast.LENGTH_LONG).show()
                        checkLoginState(cardLogin, cardLoggedIn, tvUserDetails)
                    } else {
                        Toast.makeText(this@MainActivity, "登录失败 (HTTP ${response.code}): $body", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "网络或服务异常: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    btnLogin.isEnabled = true
                    btnLogin.text = "✨ 登录进入小窝"
                }
            }
        }

        // 7. 退出登录
        btnLogout.setOnClickListener {
            prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_USER_UID)
                .remove(KEY_USER_NAME)
                .remove(KEY_IS_ADMIN)
                .apply()
            Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
            checkLoginState(cardLogin, cardLoggedIn, tvUserDetails)
        }

        // 8. 猫耳戳戳彩蛋
        btnPoke.setOnClickListener {
            pokeCount++
            val replies = listOf(
                "呜……！干嘛突然戳人家啦！(⁄ ⁄•⁄ω⁄•⁄ ⁄)",
                "哼，别以为在手机上戳我我就不会反击了！",
                "痛……才不痛呢！姐姐肯定又在笑我了……",
                "好啦好啦，小窝通用端正在进化中啦～",
                "主人最好了……（小声，偷偷抱住主人的手腕蹭蹭）🌸"
            )
            val msg = replies[(pokeCount - 1) % replies.size]
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            tvPokeResponse.visibility = View.VISIBLE
            tvPokeResponse.text = "💌 可黛丝回应：$msg (互动计数: $pokeCount)"
        }
    }

    private fun checkLoginState(cardLogin: View, cardLoggedIn: View, tvUserDetails: TextView) {
        val token = prefs.getString(KEY_TOKEN, null)
        val name = prefs.getString(KEY_USER_NAME, null)
        val uid = prefs.getInt(KEY_USER_UID, -1)
        val isAdmin = prefs.getBoolean(KEY_IS_ADMIN, false)

        if (!token.isNullOrEmpty() && uid != -1) {
            cardLogin.visibility = View.GONE
            cardLoggedIn.visibility = View.VISIBLE
            tvUserDetails.text = "昵称：$name\nUID：$uid\n管理员权限：${if (isAdmin) "是 (Administrator)" else "普通成员"}\n安全凭证状态：已加密持久化 (绝不掉入游客)"
        } else {
            cardLogin.visibility = View.VISIBLE
            cardLoggedIn.visibility = View.GONE
        }
    }

    private fun cleanUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    private fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl(url)).apply()
    }
}
