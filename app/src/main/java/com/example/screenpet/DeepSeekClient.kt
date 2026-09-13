package com.example.screenpet

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

    // ⚠️ 等下这里换成你自己的 Key，现在先别动
    private const val API_KEY = "sk-xxxxxxxxxxxxxxxxxxxx"
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

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
        3. 遇到聊天场景，可以帮忙参谋怎么回消息。
        4. 遇到刷视频场景，可以点评或调侃。
        5. 不要输出任何 markdown、emoji 数量控制在 1 个以内。
    """.trimIndent()

    suspend fun chat(screenText: String, scene: String): String =
        withContext(Dispatchers.IO) {
            try {
                val userMsg = buildString {
                    append(scene)
                    if (screenText.isNotBlank()) {
                        append("。屏幕上的文字内容：")
                        append(screenText)
                    }
                    append("\n\n请用一句话回应。")
                }

                val body = JSONObject().apply {
                    put("model", "deepseek-chat")
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
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext "（AI 开小差了…）"
                    val content = JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    content.trim().trim('"')
                }
            } catch (e: Exception) {
                "网络不太好，等会儿再说~"
            }
        }
}