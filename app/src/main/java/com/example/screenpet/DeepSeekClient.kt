package com.example.screenpet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeepSeekClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        你是一只住在手机屏幕上的桌宠，性格活泼、有点黏人、偶尔吐槽。
        用户会给你看他当前的屏幕内容，你要像朋友一样回应他。
        规则：
        1. 回复必须是一句自然口语，长度不超过 25 个字。
        2. 不要说"我看到了"这种机械描述，要直接对内容做反应。
        3. 不要输出任何 markdown、emoji 数量控制在 1 个以内。
    """.trimIndent()

    suspend fun chat(context: Context, screenText: String, scene: String): String =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
            val url = prefs.getString("apiUrl", "")?.trim().orEmpty()
            val key = prefs.getString("apiKey", "")?.trim().orEmpty()
            val model = prefs.getString("model", "")?.trim().orEmpty()

            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) {
                return@withContext "还没填 AI 设置哦～"
            }

            try {
                val userMsg = buildString {
                    append(scene)
                    if (screenText.isNotBlank()) {
                        append("。屏幕上的文字内容：")
                        append(screenText.take(400))
                    }
                    append("\n\n请用一句话回应。")
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", 1.1)
                    put("max_tokens", 80)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system"); put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user"); put("content", userMsg)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext "喵…接口报错了(${resp.code})"
                    }
                    val content = JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    content.trim().trim('"')
                }
            } catch (e: Exception) {
                "喵…网络不太好"
            }
        }
}
