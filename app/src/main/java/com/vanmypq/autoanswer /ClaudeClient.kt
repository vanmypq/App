package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
// Tự dò model Flash mới nhất mà key được phép dùng, không cần sửa code khi Google đổi tên model.
object ClaudeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private var model: String? = null

    private const val SYSTEM = "Bạn là trợ lý làm bài trắc nghiệm. Người dùng gửi danh sách các phần tử " +
        "chữ trên màn hình điện thoại, mỗi dòng dạng [số] nội dung. Nếu có một câu hỏi trắc nghiệm kèm các " +
        "đáp án, hãy suy luận ngắn gọn rồi chọn đáp án đúng nhất. Dòng cuối cùng của câu trả lời phải là " +
        "JSON duy nhất: {\"id\": N} với N là số của phần tử đáp án cần bấm (ưu tiên phần tử chứa nội dung " +
        "đáp án, hoặc chữ cái A/B/C/D của đáp án đó). Nếu màn hình không có câu trắc nghiệm hoặc không chắc " +
        "chắn, trả {\"id\": null}."

    private fun pickModel(apiKey: String): String {
        model?.let { return it }
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("x-goog-api-key", apiKey)
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${txt.take(150)}")
        val arr = JSONObject(txt).optJSONArray("models") ?: JSONArray()
        val skip = listOf("lite", "image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp")
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (methods.contains("generateContent") && n.startsWith("gemini-") &&
                n.contains("flash") && skip.none { n.contains(it) }
            ) names.add(n)
        }
        val pick = if (names.contains("gemini-flash-latest")) "gemini-flash-latest"
        else names.sortedDescending().firstOrNull() ?: "gemini-flash-latest"
        model = pick
        return pick
    }

    fun ask(apiKey: String, screenText: String): Int? {
        val m = pickModel(apiKey)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", screenText)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
        val c = URL("$BASE/models/$m:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) {
            if (code == 404) model = null
            throw RuntimeException("HTTP $code ($m): ${resp.take(150)}")
        }
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return null
        if (cands.length() == 0) return null
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        val r = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(sb).lastOrNull() ?: return null
        return r.groupValues[1].toIntOrNull()
    }
}
