package app.touchbot

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Anthropic Messages API client (blocking; call from a worker thread). */
object ClaudeApi {
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    /** Posts [body] and returns the parsed response, retrying rate limits and server errors. */
    fun call(
        apiKey: String,
        body: JSONObject,
        readTimeoutMs: Int = 90_000,
        cancelled: () -> Boolean = { false },
    ): JSONObject {
        val payload = body.toString().toByteArray()
        var lastError = "unknown error"
        for (attempt in 0 until 4) {
            if (cancelled()) throw IllegalStateException("Stopped")
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) return JSONObject(text)
                lastError = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) { "HTTP $code" }
                if (code != 429 && code != 529 && code < 500) throw IllegalStateException("API error: $lastError")
            } catch (e: java.io.IOException) {
                lastError = e.message ?: "network error"
            } finally {
                conn.disconnect()
            }
            Thread.sleep(1500L * (attempt + 1))
        }
        throw IllegalStateException("API failed: $lastError")
    }

    fun textBlock(t: String): JSONObject = JSONObject().put("type", "text").put("text", t)

    fun imageBlock(jpegBase64: String): JSONObject = JSONObject().put("type", "image").put(
        "source",
        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64),
    )

    /** First block of the given [type] in a response's content, or null. */
    fun firstBlock(content: JSONArray, type: String): JSONObject? {
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == type) return b
        }
        return null
    }

    /**
     * Copy of an assistant turn without its thinking blocks. Histories that are trimmed
     * client-side must not replay thinking blocks, or newer models reject the request.
     */
    fun withoutThinking(content: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            val t = b.optString("type")
            if (t != "thinking" && t != "redacted_thinking") out.put(b)
        }
        return out
    }
}
