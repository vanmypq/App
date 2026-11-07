package com.vanmypq.autoanswer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Auto Trắc Nghiệm\n\n1) Nhập Claude API key và bấm Lưu.\n" +
                "2) Bấm \"Bật dịch vụ Trợ năng\", tìm \"Auto Trắc Nghiệm\" và bật.\n" +
                "3) Mở app/web có câu trắc nghiệm, bấm nút nổi \"▶ AI\" để bật chế độ tự bấm; bấm lại để tắt.\n\n" +
                "Chỉ dùng để ôn tập, luyện đề. Không dùng trong bài kiểm tra/thi thật."
            textSize = 15f
        })
        val et = EditText(this).apply {
            hint = "Claude API key (sk-ant-...)"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(et)
        root.addView(Button(this).apply {
            text = "Lưu API key"
            setOnClickListener {
                prefs.edit().putString("key", et.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Đã lưu", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Bật dịch vụ Trợ năng"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
