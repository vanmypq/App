package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
object ClaudeClient {
    private const val MODEL = "gemini-2.5-flash"

    private const val SYSTEM = "Bạn là trợ lý làm bài trắc nghiệm. Người dùng gửi danh sách các phần tử " +
        "chữ trên màn hình điện thoại, mỗi dòng dạng [số] nội dung. Nếu có một câu hỏi trắc nghiệm kèm các " +
        "đáp án, hãy suy luận ngắn gọn rồi chọn đáp án đúng nhất. Dòng cuối cùng của câu trả lời phải là " +
        "JSON duy nhất: {\"id\": N} với N là số của phần tử đáp án cần bấm (ưu tiên phần tử chứa nội dung " +
        "đáp án, hoặc chữ cái A/B/C/D của đáp án đó). Nếu màn hình không có câu trắc nghiệm hoặc không chắc " +
        "chắn, trả {\"id\": null}."

    fun ask(apiKey: String, screenText: String): Int? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", screenText)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${resp.take(150)}")
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return null
        if (cands.length() == 0) return null
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        val m = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(sb).lastOrNull() ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
