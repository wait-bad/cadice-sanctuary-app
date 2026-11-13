package com.sanctuary.cadice

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var serverUrl: String = ""
    private var token: String = ""
    private var myUid: Int = -1
    private var myName: String = ""
    private var isDarkTheme: Boolean = true

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadSelectedAvatar(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(SanctuaryCore.PREFS_NAME, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(SanctuaryCore.KEY_SERVER_URL, "") ?: ""
        token = prefs.getString(SanctuaryCore.KEY_TOKEN, "") ?: ""
        myUid = prefs.getInt(SanctuaryCore.KEY_USER_UID, -1)
        myName = prefs.getString(SanctuaryCore.KEY_USER_NAME, "成员") ?: "成员"
        isDarkTheme = prefs.getBoolean(SanctuaryCore.KEY_DARK_THEME, true)

        applyTheme()

        findViewById<MaterialButton>(R.id.btn_settings_back).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.tv_settings_name).text = myName
        refreshAvatarPreview()

        findViewById<MaterialButton>(R.id.btn_pick_avatar).setOnClickListener {
            pickAvatarLauncher.launch("image/*")
        }

        setupFontSizeControl()
    }

    private fun refreshAvatarPreview() {
        val ivAvatar = findViewById<ImageView>(R.id.iv_settings_avatar)
        val tvAvatar = findViewById<TextView>(R.id.tv_settings_avatar)
        SanctuaryCore.loadAvatar(
            context = this,
            scope = lifecycleScope,
            serverUrl = serverUrl,
            token = token,
            isGroup = false,
            id = myUid,
            avatarUpdatedAt = SanctuaryCore.getAvatarUpdatedAt(myUid),
            fallbackText = myName.take(1).ifBlank { "我" },
            ivAvatar = ivAvatar,
            tvFallback = tvAvatar
        )
    }

    private fun uploadSelectedAvatar(uri: Uri) {
        val btnPick = findViewById<MaterialButton>(R.id.btn_pick_avatar)
        btnPick.isEnabled = false
        btnPick.text = "上传中..."

        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "读取图片失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val (ok, msg) = SanctuaryCore.uploadUserAvatar(
                    context = this@SettingsActivity,
                    serverUrl = serverUrl,
                    myUid = myUid,
                    rawBytes = bytes
                )
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                if (ok) {
                    refreshAvatarPreview()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "更换头像出错: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                btnPick.isEnabled = true
                btnPick.text = "更换头像"
            }
        }
    }

    private fun setupFontSizeControl() {
        val seekBar = findViewById<SeekBar>(R.id.seek_font_size)
        val tvFontValue = findViewById<TextView>(R.id.tv_font_value)
        val tvPreview = findViewById<TextView>(R.id.tv_font_preview)

        // progress 0..10 映射到 scale 0.85 .. 1.35 (步进 0.05，默认 progress=3 => 1.00)
        val currentScale = SanctuaryCore.getFontScale(prefs)
        val initialProgress = (((currentScale - 0.85f) / 0.05f) + 0.5f).toInt().coerceIn(0, 10)
        seekBar.progress = initialProgress

        fun updateFontLabelAndPreview(progress: Int) {
            val scale = 0.85f + progress * 0.05f
            val pct = (scale * 100f + 0.5f).toInt()
            val label = when {
                pct <= 90 -> "偏小 ($pct%)"
                pct in 95..105 -> "标准 ($pct%)"
                pct in 110..120 -> "偏大 ($pct%)"
                else -> "特大 ($pct%)"
            }
            tvFontValue.text = label
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        }

        updateFontLabelAndPreview(initialProgress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateFontLabelAndPreview(progress)
                if (fromUser) {
                    val newScale = 0.85f + progress * 0.05f
                    prefs.edit().putFloat(SanctuaryCore.KEY_FONT_SCALE, newScale).apply()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyTheme() {
        val root = findViewById<LinearLayout>(R.id.settings_root)
        val topBar = findViewById<LinearLayout>(R.id.settings_topbar)
        val btnBack = findViewById<MaterialButton>(R.id.btn_settings_back)
        val tvTitle = findViewById<TextView>(R.id.tv_settings_title)
        val tvSecProfile = findViewById<TextView>(R.id.tv_section_profile)
        val tvSecDisplay = findViewById<TextView>(R.id.tv_section_display)

        val cardAvatar = findViewById<MaterialCardView>(R.id.card_settings_avatar)
        val tvName = findViewById<TextView>(R.id.tv_settings_name)
        val tvAvatarDesc = findViewById<TextView>(R.id.tv_settings_avatar_desc)

        val cardFont = findViewById<MaterialCardView>(R.id.card_settings_font)
        val tvFontTitle = findViewById<TextView>(R.id.tv_font_title)
        val cardPreview = findViewById<MaterialCardView>(R.id.card_font_preview)
        val tvPreview = findViewById<TextView>(R.id.tv_font_preview)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isDarkTheme) {
            val bg = Color.parseColor("#0F172A")
            val card = Color.parseColor("#1E293B")
            val stroke = Color.parseColor("#334155")
            val textMain = Color.parseColor("#F8FAFC")
            val textSub = Color.parseColor("#94A3B8")

            window.statusBarColor = card
            window.navigationBarColor = bg
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            root.setBackgroundColor(bg)
            topBar.setBackgroundColor(card)
            btnBack.setTextColor(textMain)
            tvTitle.setTextColor(textMain)
            tvSecProfile.setTextColor(textSub)
            tvSecDisplay.setTextColor(textSub)

            cardAvatar.setCardBackgroundColor(card)
            cardAvatar.strokeColor = stroke
            tvName.setTextColor(textMain)
            tvAvatarDesc.setTextColor(textSub)

            cardFont.setCardBackgroundColor(card)
            cardFont.strokeColor = stroke
            tvFontTitle.setTextColor(textMain)
            cardPreview.setCardBackgroundColor(bg)
            cardPreview.strokeColor = stroke
            tvPreview.setTextColor(textMain)
        } else {
            val bg = Color.parseColor("#F8FAFC")
            val card = Color.parseColor("#FFFFFF")
            val stroke = Color.parseColor("#E2E8F0")
            val textMain = Color.parseColor("#0F172A")
            val textSub = Color.parseColor("#64748B")

            window.statusBarColor = card
            window.navigationBarColor = bg
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true

            root.setBackgroundColor(bg)
            topBar.setBackgroundColor(card)
            btnBack.setTextColor(textMain)
            tvTitle.setTextColor(textMain)
            tvSecProfile.setTextColor(textSub)
            tvSecDisplay.setTextColor(textSub)

            cardAvatar.setCardBackgroundColor(card)
            cardAvatar.strokeColor = stroke
            tvName.setTextColor(textMain)
            tvAvatarDesc.setTextColor(textSub)

            cardFont.setCardBackgroundColor(card)
            cardFont.strokeColor = stroke
            tvFontTitle.setTextColor(textMain)
            cardPreview.setCardBackgroundColor(bg)
            cardPreview.strokeColor = stroke
            tvPreview.setTextColor(textMain)
        }
    }
}
