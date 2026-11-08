package com.vanmypq.autoanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    data class Item(val text: String, val rect: Rect, val node: AccessibilityNodeInfo?)

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var button: TextView? = null
    private var auto = false
    private var busy = false
    private var lastHash = 0
    private var currentHash = 0
    private var layoutHash = 0
    private var retries = 0
    private var cooldownUntil = 0L
    private var status = ""
    private var warnedNoOcr = false
    private var lastSent = ""

    private val loop = object : Runnable {
        override fun run() {
            if (!auto) return
            tick()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            maxWidth = 700
            setOnClickListener { toggle() }
            setOnLongClickListener {
                val cm = this@AutoAnswerService.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-input", lastSent))
                toast("Đã sao chép dữ liệu app đọc được (dán vào chat để xem)")
                true
            }
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
        currentHash = 0
        layoutHash = 0
        retries = 0
        cooldownUntil = 0L
        status = if (auto) "đang quét" else ""
        refresh()
        handler.removeCallbacks(loop)
        if (auto) handler.post(loop)
    }

    private fun refresh() {
        button?.apply {
            text = if (auto) "■ AI: $status" else "▶ AI"
            setBackgroundColor(if (auto) Color.parseColor("#CC2E7D32") else Color.parseColor("#CC424242"))
        }
    }

    private fun setStatus(s: String) {
        handler.post { status = s; refresh() }
    }

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun tick() {
        if (busy || SystemClock.uptimeMillis() < cooldownUntil) return
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
            setStatus("không đọc được chữ trên màn hình này")
        }
    }

    // Gom toàn bộ chữ của một nút và các nút con (dùng cho công thức toán bị ẩn khỏi màn hình).
    private fun gather(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 12) return ""
        val sb = StringBuilder()
        val t = (n.text ?: n.contentDescription)?.toString()
        if (!t.isNullOrEmpty()) sb.append(t)
        for (i in 0 until n.childCount) n.getChild(i)?.let { sb.append(gather(it, depth + 1)) }
        return sb.toString()
    }

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int, parentVisible: Boolean, parentRect: Rect) {
            if (out.size >= 200 || depth > 40) return
            if (n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty() && !r.isEmpty) out.add(Item(t.take(400), r, n))
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, true, r) }
            } else if (parentVisible) {
                // Nút ẩn nằm trong nút đang hiện (ví dụ công thức KaTeX/MathML): lấy chữ, chạm vào vùng của nút cha.
                val t = gather(n, 0).trim()
                if (t.isNotEmpty() && !parentRect.isEmpty) {
                    out.add(Item("(công thức) " + t.take(300), Rect(parentRect), n))
                }
            }
        }
        walk(root, 0, false, Rect())
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
                            .mapNotNull { l -> l.boundingBox?.let { Item(l.text, it, null) } }
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
        if (h != currentHash) { currentHash = h; retries = 0 }
        if (h == lastHash) return
        val lh = items.joinToString("|") { it.text + it.rect.flattenToString() }.hashCode()
        if (lh != layoutHash) {
            layoutHash = lh
            setStatus("chờ trang ổn định…")
            return
        }
        lastHash = h
        busy = true
        setStatus("đang hỏi AI…")
        val text = items.mapIndexed { i, it -> "[${i + 1}] ${it.text}" }.joinToString("\n")
        lastSent = text
        exec.execute {
            var gotId: Int? = null
            var gotErr: String? = null
            try {
                gotId = ClaudeClient.ask(key, text)
            } catch (e: Exception) {
                gotErr = e.message ?: "lỗi mạng"
            }
            val id: Int? = gotId
            val err: String? = gotErr
            handler.post {
                busy = false
                if (err != null) {
                    lastHash = 0
                    val wait = if (err.contains("429")) 30000L else 6000L
                    cooldownUntil = SystemClock.uptimeMillis() + wait
                    status = "lỗi: " + err.take(80)
                    refresh()
                    toast(err)
                } else if (id != null && id >= 1 && id <= items.size) {
                    retries = 0
                    status = "chọn $id: " + items[id - 1].text.take(25)
                    refresh()
                    cooldownUntil = SystemClock.uptimeMillis() + 3500
                    choose(items[id - 1], items.subList(0, id - 1).count { it.text == items[id - 1].text })
                } else if (retries < 2) {
                    retries++
                    lastHash = 0
                    status = "thử lại ($retries)"
                    refresh()
                } else {
                    status = "không thấy câu hỏi"
                    refresh()
                }
            }
        }
    }

    private fun layoutOf(list: List<Item>): Int =
        list.joinToString("|") { it.text + it.rect.flattenToString() }.hashCode()

    // Lấy lại tọa độ mới nhất của đáp án (trang có thể đã dịch chuyển trong lúc chờ AI) rồi chạm.
    private fun choose(item: Item, occurrence: Int) {
        val fresh = collect().filter { it.text == item.text }
        var target: Rect = item.rect
        if (fresh.size > occurrence) {
            target = fresh[occurrence].rect
        } else {
            val n = item.node
            if (n != null) {
                n.refresh()
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) target = r
            }
        }
        tap(target) { ok ->
            if (!ok) clickNode(item.node)
            afterTap()
        }
    }

    // Sau khi chạm, coi màn hình hiện tại (có thể có lời giải/đáp án đúng) là "đã xem" để không hỏi lại.
    private fun afterTap() {
        handler.postDelayed({
            val items2 = collect()
            val h2 = items2.joinToString("|") { it.text }.hashCode()
            lastHash = h2
            currentHash = h2
            layoutHash = layoutOf(items2)
        }, 1500)
    }

    private fun clickNode(n: AccessibilityNodeInfo?) {
        if (n == null) return
        n.refresh()
        val maxH = resources.displayMetrics.heightPixels * 0.5
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                val b = Rect(); cur.getBoundsInScreen(b)
                if (b.height() < maxH && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                break
            }
            cur = cur.parent
            depth++
        }
    }

    private fun tap(r: Rect, onDone: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val x = r.exactCenterX().coerceIn(2f, dm.widthPixels - 2f)
        val y = r.exactCenterY().coerceIn(2f, dm.heightPixels - 2f)
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 100)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        (getSystemService(WINDOW_SERVICE) as WindowManager).let { wm -> button?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
