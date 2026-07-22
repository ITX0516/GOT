package com.goai.ai

import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.goai.model.GameState
import com.goai.model.Move
import com.goai.model.Stone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** LLM 讲解服务：手机端直连 OpenAI 兼容接口 */
class LLMService(
    private val apiUrl: String,
    private val apiKey: String,
    private val model: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** 是否已配置 */
    val isConfigured: Boolean
        get() = apiKey.isNotEmpty() && apiUrl.isNotEmpty() && model.isNotEmpty()

    /**
     * 讲解当前局面
     * @param gameState 当前游戏状态
     * @param boardBitmap 棋盘截图（可选，多模态）
     * @param userQuestion 用户提问（可选）
     * @return 讲解文本
     */
    suspend fun explain(
        gameState: GameState,
        boardBitmap: Bitmap? = null,
        userQuestion: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext "请先在设置中配置 LLM API Key。"
        }

        // 构造消息内容
        val contentParts = mutableListOf<JsonObject>()

        // 文本部分：描述局面
        val moves = gameState.getMoves()
        val moveCount = moves.size
        val currentPlayer = if (gameState.currentPlayer == Stone.BLACK) "黑棋" else "白棋"
        val movesText = moves.takeLast(10).joinToString(" ") { move ->
            if (move.isPass) "pass" else move.toGTP(gameState.boardSize)
        }

        val prompt = buildString {
            append("你是一位围棋老师，请用简洁的中文讲解当前局面。\n")
            append("当前已走 $moveCount 手，轮到$currentPlayer。\n")
            append("最近几手：$movesText\n")
            if (userQuestion != null) {
                append("学生提问：$userQuestion\n")
            }
            append("请分析当前局面，给出建议。")
        }

        contentParts.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", prompt)
        })

        // 图片部分（如果有）
        if (boardBitmap != null) {
            val base64Image = bitmapToBase64(boardBitmap)
            contentParts.add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/png;base64,$base64Image")
                    addProperty("detail", "high")
                })
            })
        }

        // 构造请求体
        val requestBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 1024)
            addProperty("temperature", 0.7)
            add("messages", gson.toJsonTree(listOf(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", "你是一位专业的围棋老师，擅长用通俗易懂的中文讲解棋局。")
                },
                JsonObject().apply {
                    addProperty("role", "user")
                    add("content", gson.toJsonTree(contentParts))
                }
            )))
        }

        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext "AI 讲解请求失败：${response.code}"
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val content = json
                .getAsJsonArray("choices")
                ?.get(0)
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
                ?: "AI 返回为空"

            return@withContext content
        } catch (e: Exception) {
            return@withContext "AI 讲解暂不可用：${e.message}"
        }
    }

    /** Bitmap 转 base64 */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
