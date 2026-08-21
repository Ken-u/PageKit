package com.kenjc.pagekit.compress

import android.content.Context
import java.net.URI

data class LlmConfig(
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiKey: String = "",
    val maxInputChars: Int = 200_000,
    val maxOutputTokens: Int = 4_096,
) {
    val configured: Boolean get() = endpoint.isNotBlank() && model.isNotBlank()

    fun validated(): LlmConfig {
        val uri = runCatching { URI(endpoint.trim()) }.getOrElse { error("invalid LLM endpoint") }
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "LLM endpoint must be an absolute URL without credentials/query/fragment"
        }
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "LLM endpoint must use HTTPS (HTTP is allowed only for loopback)"
        }
        require(model.isNotBlank()) { "LLM model is not configured" }
        require(maxInputChars in 10_000..1_000_000)
        require(maxOutputTokens in 512..32_768)
        return copy(endpoint = endpoint.trim().trimEnd('/'), model = model.trim(), apiKey = apiKey.trim())
    }
}

data class LlmConfigStatus(
    val endpoint: String,
    val model: String,
    val configured: Boolean,
    val hasApiKey: Boolean,
)

interface LlmSettings {
    fun load(): LlmConfig
    fun save(config: LlmConfig)
    fun status(): LlmConfigStatus = load().let {
        LlmConfigStatus(it.endpoint, it.model, it.configured, it.apiKey.isNotBlank())
    }
}

class SharedPreferencesLlmSettings(context: Context) : LlmSettings {
    private val preferences = context.getSharedPreferences("pagekit_llm", Context.MODE_PRIVATE)

    override fun load() = LlmConfig(
        endpoint = preferences.getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
        model = preferences.getString("model", "").orEmpty(),
        apiKey = preferences.getString("api_key", "").orEmpty(),
        maxInputChars = preferences.getInt("max_input_chars", 200_000),
        maxOutputTokens = preferences.getInt("max_output_tokens", 4_096),
    )

    override fun save(config: LlmConfig) {
        val safe = config.validated()
        check(
            preferences.edit()
                .putString("endpoint", safe.endpoint)
                .putString("model", safe.model)
                .putString("api_key", safe.apiKey)
                .putInt("max_input_chars", safe.maxInputChars)
                .putInt("max_output_tokens", safe.maxOutputTokens)
                .commit(),
        ) { "failed to persist LLM settings" }
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
    }
}
