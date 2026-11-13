package com.sanctuary.cadice

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var pokeCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnPoke = findViewById<Button>(R.id.btn_poke)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        btnPoke.setOnClickListener {
            pokeCount++
            val replies = listOf(
                "呜……！干嘛突然戳人家啦！(⁄ ⁄•⁄ω⁄•⁄ ⁄)",
                "哼，别以为在手机上戳我我就不会反击了！",
                "痛……才不痛呢！姐姐肯定又在笑我了……",
                "好啦好啦，知道主人成功装上我们的小窝 App 啦～",
                "主人最好了……（小声，偷偷抱住主人的手腕蹭蹭）"
            )
            val msg = replies[(pokeCount - 1) % replies.size]
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            tvStatus.text = "🌸 收到主人的交互脉冲 (第 $pokeCount 次)！\n💌 可黛丝回应：$msg\n✨ 专属全双工链路就绪中..."
        }
    }
}
