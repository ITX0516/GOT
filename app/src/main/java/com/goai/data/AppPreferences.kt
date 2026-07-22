package com.goai.data

import android.content.Context
import android.content.SharedPreferences

/** 应用偏好存储，管理引擎和 LLM 配置 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("goai_prefs", Context.MODE_PRIVATE)

    // ===== 引擎设置 =====

    /** 引擎模式：local / cloud / none */
    var engineMode: String
        get() = prefs.getString(KEY_ENGINE_MODE, "none") ?: "none"
        set(value) = prefs.edit().putString(KEY_ENGINE_MODE, value).apply()

    /** 棋盘大小 */
    var boardSize: Int
        get() = prefs.getInt(KEY_BOARD_SIZE, 19)
        set(value) = prefs.edit().putInt(KEY_BOARD_SIZE, value).apply()

    /** 贴目 */
    var komi: Float
        get() = prefs.getFloat(KEY_KOMI, 7.5f)
        set(value) = prefs.edit().putFloat(KEY_KOMI, value).apply()

    // ===== LLM 讲解设置 =====

    /** LLM 服务商：qwen / glm / openai / custom */
    var llmProvider: String
        get() = prefs.getString(KEY_LLM_PROVIDER, "qwen") ?: "qwen"
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value).apply()

    /** LLM API 地址（OpenAI 兼容格式） */
    var llmUrl: String
        get() = prefs.getString(KEY_LLM_URL, "https://dashscope.aliyuncs.com/compatible-mode/v1") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_URL, value).apply()

    /** LLM API Key */
    var llmKey: String
        get() = prefs.getString(KEY_LLM_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_KEY, value).apply()

    /** LLM 模型名称 */
    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, "qwen-vl-max") ?: "qwen-vl-max"
        set(value) = prefs.edit().putString(KEY_LLM_MODEL, value).apply()

    companion object {
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_BOARD_SIZE = "board_size"
        private const val KEY_KOMI = "komi"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_LLM_URL = "llm_url"
        private const val KEY_LLM_KEY = "llm_key"
        private const val KEY_LLM_MODEL = "llm_model"

        /** LLM 服务商预设 */
        val LLM_PRESETS = mapOf(
            "qwen" to LLMConfig("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max"),
            "glm" to LLMConfig("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v"),
            "openai" to LLMConfig("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            "custom" to LLMConfig("自定义", "", "")
        )
    }
}

/** LLM 服务商配置 */
data class LLMConfig(
    val displayName: String,
    val defaultUrl: String,
    val defaultModel: String
)
