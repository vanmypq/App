package com.vanmypq.autoanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.annotation.TargetApi
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class AutoAnswerService : AccessibilityService() {
    data class Item(val text: String, val rect: Rect)

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var button: TextView? = null
    private var auto = false
    private var busy = false
    private var lastHash = 0
    private var warnedNoOcr = false

    private val loop = object : Runnable {
        override fun run() {
            if (!auto) return
            tick()
            handler.postDelayed(this, 2500)
        }
    }

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener { toggle() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }
        wm.addView(tv, lp)
        button = tv
        refresh()
    }

    private fun toggle() {
        auto = !auto
        lastHash = 0
        refresh()
        handler.removeCallbacks(loop)
        if (auto) handler.post(loop)
    }

    private fun refresh() {
        button?.apply {
            text = if (auto) "■ AI đang chạy" else "▶ AI"
            setBackgroundColor(if (auto) Color.parseColor("#CC2E7D32") else Color.parseColor("#CC424242"))
        }
    }

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun tick() {
        if (busy) return
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            toast("Chưa nhập API key"); auto = false; refresh(); return
        }
        val items = collect()
        if (items.sumOf { it.text.length } >= 40) {
            process(items, key)
        } else if (Build.VERSION.SDK_INT >= 30) {
            ocrThenProcess(key)
        } else if (!warnedNoOcr) {
            warnedNoOcr = true
            toast("Android 10 không hỗ trợ OCR: màn hình này không đọc được chữ")
        }
    }

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= 150 || depth > 40) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (!r.isEmpty) out.add(Item(t.take(400), r))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    @TargetApi(30)
    private fun ocrThenProcess(key: String) {
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (bmp == null) { busy = false; return }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { res ->
                        val list = res.textBlocks.flatMap { it.lines }
                            .mapNotNull { l -> l.boundingBox?.let { Item(l.text, it) } }
                        busy = false
                        process(list, key)
                    }
                    .addOnFailureListener { busy = false }
            }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun process(items: List<Item>, key: String) {
        if (items.isEmpty()) return
        val h = items.joinToString("|") { it.text }.hashCode()
        if (h == lastHash) return
        lastHash = h
        busy = true
        val text = items.mapIndexed { i, it -> "[${i + 1}] ${it.text}" }.joinToString("\n")
        exec.execute {
            var id: Int? = null
            try {
                id = ClaudeClient.ask(key, text)
            } catch (e: Exception) {
                toast("Lỗi: ${e.message}")
                lastHash = 0
            }
            handler.post {
                busy = false
                if (auto && id != null && id in 1..items.size) tap(items[id - 1].rect)
            }
        }
    }

    private fun tap(r: Rect) {
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        dispatchGesture(g, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        (getSystemService(WINDOW_SERVICE) as WindowManager).let { wm -> button?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
