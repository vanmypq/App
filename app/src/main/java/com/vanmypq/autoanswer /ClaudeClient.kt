package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
// Ưu tiên model Flash đầy đủ (suy luận tốt hơn); chỉ tạm chuyển sang Flash-Lite khi lỗi/hết hạn mức,
// và sau 5 phút tự thử lại model tốt nhất.
object ClaudeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private var models: List<String>? = null
    private var idx = 0
    private var switchedAt = 0L
    private val modeOf = HashMap<String, Int>()
    // mode 0: thinkingBudget=1024 | 1: thinkingLevel=low | 2: không cấu hình suy nghĩ | 3: như 2 nhưng bỏ ảnh

    @Volatile var lastInfo: String = ""

    private const val SYSTEM = "Bạn là giáo viên Toán/Khoa học THCS Việt Nam giải bài trắc nghiệm. Người dùng gửi " +
        "danh sách các phần tử chữ trên màn hình điện thoại, mỗi dòng dạng [số] nội dung, và có thể kèm ẢNH " +
        "CHỤP đúng màn hình hiện tại. Hãy dùng ảnh để đọc công thức toán, hình vẽ, bảng biểu mà danh sách chữ " +
        "không có hoặc bị dính chữ (ví dụ số mũ viết liền 'y2' nghĩa là y bình phương). Dòng '(công thức)' là " +
        "công thức đã được ghép lại và nằm ngay sau chữ cái đáp án đứng trước nó. Danh sách chữ dùng để xác " +
        "định số [id] của phần tử cần bấm. Nếu có một câu hỏi trắc nghiệm kèm các đáp án: đọc kỹ câu hỏi, " +
        "đặc biệt câu PHỦ ĐỊNH (KHÔNG PHẢI, KHÔNG LÀ, SAI, NGOẠI TRỪ thì phải chọn phương án KHÔNG thỏa điều " +
        "kiện), áp dụng đúng định nghĩa trong sách giáo khoa, kiểm tra TỪNG phương án (chú ý các bẫy như hệ " +
        "số bằng 0, số mũ, mẫu số chứa ẩn) rồi mới chọn. Chỉ ghi phân tích ngắn gọn (tối đa 6 dòng). Dòng " +
        "cuối cùng của câu trả lời phải là JSON duy nhất: {\"id\": N} với N là số của phần tử đáp án cần bấm " +
        "(ưu tiên phần tử chữ cái A/B/C/D của đáp án đó, hoặc phần tử chứa nội dung đáp án). Nếu màn hình " +
        "không có câu trắc nghiệm hoặc không chắc chắn, trả {\"id\": null}."

    private fun loadModels(apiKey: String): List<String> {
        models?.let { return it }
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("x-goog-api-key", apiKey)
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${txt.take(150)}")
        val arr = JSONObject(txt).optJSONArray("models") ?: JSONArray()
        val skip = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp")
        val flash = ArrayList<String>()
        val lite = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent") || !n.startsWith("gemini-") ||
                !n.contains("flash") || skip.any { n.contains(it) }
            ) continue
            if (n.contains("lite")) lite.add(n) else flash.add(n)
        }
        val list = ArrayList<String>()
        list.add(if (flash.contains("gemini-flash-latest")) "gemini-flash-latest"
                 else flash.sortedDescending().firstOrNull() ?: "gemini-flash-latest")
        list.add(if (lite.contains("gemini-flash-lite-latest")) "gemini-flash-lite-latest"
                 else lite.sortedDescending().firstOrNull() ?: "gemini-flash-lite-latest")
        list.addAll(flash.sortedDescending())
        list.addAll(lite.sortedDescending())
        val result = list.distinct()
        models = result
        return result
    }

    private fun buildBody(screenText: String, mode: Int, img: String?): JSONObject {
        val parts = JSONArray()
        if (img != null && mode < 3) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        }
        parts.put(JSONObject().put("text", screenText))
        val gen = JSONObject().put("maxOutputTokens", 4096)
        if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 1024))
        if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun post(apiKey: String, m: String, body: JSONObject): Pair<Int, String> {
        try {
            val c = URL("$BASE/models/$m:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            return Pair(code, resp)
        } catch (e: SocketTimeoutException) {
            return Pair(408, "quá thời gian chờ")
        }
    }

    private fun replyText(resp: String): String {
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return ""
        if (cands.length() == 0) return ""
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    fun ask(apiKey: String, screenText: String): Int? {
        val img: String? = try { CaptureService.grab() } catch (e: Exception) { null }
        val list = loadModels(apiKey)
        if (idx != 0 && System.currentTimeMillis() - switchedAt > 300000L) idx = 0
        var lastErr = "không có model"
        var tries = 0
        while (tries < list.size) {
            val m = list[idx % list.size]
            var mode = modeOf[m] ?: 0
            var r = post(apiKey, m, buildBody(screenText, mode, img))
            while (r.first == 400 && mode < 3 && !r.second.contains("API key", ignoreCase = true)) {
                mode++
                if (mode == 3 && img == null) break
                r = post(apiKey, m, buildBody(screenText, mode, img))
            }
            if (r.first in 200..299) {
                modeOf[m] = mode
                val text = replyText(r.second)
                lastInfo = "Model: $m | Ảnh: " + (if (img != null && mode < 3) "CÓ" else "KHÔNG") +
                    " | Chế độ suy nghĩ: $mode\nAI trả lời:\n$text"
                val f = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(text).lastOrNull() ?: return null
                return f.groupValues[1].toIntOrNull()
            }
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            if (r.first == 429 || r.first == 404 || r.first == 408 || r.first == 500 || r.first == 503) {
                idx++
                switchedAt = System.currentTimeMillis()
                tries++
                continue
            }
            throw RuntimeException(lastErr)
        }
        models = null
        throw RuntimeException(lastErr)
    }
}
