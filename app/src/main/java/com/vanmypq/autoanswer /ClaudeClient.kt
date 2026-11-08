package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
// Tự dò model, nếu model này hết hạn mức (429) hoặc không có (404) thì tự thử model kế tiếp.
object ClaudeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private var models: List<String>? = null
    private var idx = 0
    private var useNoThinking = true

    private const val SYSTEM = "Bạn là trợ lý làm bài trắc nghiệm. Người dùng gửi danh sách các phần tử " +
        "chữ trên màn hình điện thoại, mỗi dòng dạng [số] nội dung. Nếu có một câu hỏi trắc nghiệm kèm các " +
        "đáp án, hãy giải thật ngắn gọn (tối đa 3 câu) rồi chọn đáp án đúng nhất. Dòng cuối cùng của câu " +
        "trả lời phải là JSON duy nhất: {\"id\": N} với N là số của phần tử đáp án cần bấm (ưu tiên phần tử " +
        "chứa nội dung đáp án, hoặc chữ cái A/B/C/D của đáp án đó). Nếu màn hình không có câu trắc nghiệm " +
        "hoặc không chắc chắn, trả {\"id\": null}."

    private fun loadModels(apiKey: String): List<String> {
        models?.let { return it }
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
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

    private fun buildBody(screenText: String, noThinking: Boolean): JSONObject {
        val gen = JSONObject().put("maxOutputTokens", 800)
        if (noThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", screenText)))))
            .put("generationConfig", gen)
    }

    private fun post(apiKey: String, m: String, body: JSONObject): Pair<Int, String> {
        val c = URL("$BASE/models/$m:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 40000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        return Pair(code, resp)
    }

    private fun parse(resp: String): Int? {
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return null
        if (cands.length() == 0) return null
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        val f = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(sb).lastOrNull() ?: return null
        return f.groupValues[1].toIntOrNull()
    }

    fun ask(apiKey: String, screenText: String): Int? {
        val list = loadModels(apiKey)
        var lastErr = "không có model"
        var tries = 0
        while (tries < list.size) {
            val m = list[idx % list.size]
            var r = post(apiKey, m, buildBody(screenText, useNoThinking))
            if (r.first == 400 && useNoThinking) {
                useNoThinking = false
                r = post(apiKey, m, buildBody(screenText, false))
            }
            if (r.first in 200..299) return parse(r.second)
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            if (r.first == 429 || r.first == 404) {
                idx++
                tries++
                continue
            }
            throw RuntimeException(lastErr)
        }
        models = null
        throw RuntimeException(lastErr)
    }
}
