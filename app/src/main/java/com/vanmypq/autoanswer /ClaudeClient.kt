package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {
    private const val SYSTEM = "Bạn là trợ lý làm bài trắc nghiệm. Người dùng gửi danh sách các phần tử " +
        "chữ trên màn hình điện thoại, mỗi dòng dạng [số] nội dung. Nếu có một câu hỏi trắc nghiệm kèm các " +
        "đáp án, hãy suy luận ngắn gọn rồi chọn đáp án đúng nhất. Dòng cuối cùng của câu trả lời phải là " +
        "JSON duy nhất: {\"id\": N} với N là số của phần tử đáp án cần bấm (ưu tiên phần tử chứa nội dung " +
        "đáp án, hoặc chữ cái A/B/C/D của đáp án đó). Nếu màn hình không có câu trắc nghiệm hoặc không chắc " +
        "chắn, trả {\"id\": null}."

    fun ask(apiKey: String, screenText: String): Int? {
        val body = JSONObject()
            .put("model", "claude-sonnet-5-5")
            .put("max_tokens", 1000)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", screenText)))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${resp.take(120)}")
        val content = JSONObject(resp).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.getString("text"))
        }
        val m = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(sb).lastOrNull() ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
