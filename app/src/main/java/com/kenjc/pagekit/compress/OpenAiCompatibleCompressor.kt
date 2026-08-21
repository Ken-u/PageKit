package com.kenjc.pagekit.compress

import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LlmPrompts(
    val system: String,
    val developer: String,
    val user: String,
)

fun interface LlmCompletionClient {
    suspend fun complete(config: LlmConfig, prompts: LlmPrompts): String
}

fun interface LlmHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class OpenAiChatCompletionClient(
    private val connectionFactory: LlmHttpConnectionFactory = LlmHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) : LlmCompletionClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(config: LlmConfig, prompts: LlmPrompts): String = withContext(Dispatchers.IO) {
        val safe = config.validated()
        val endpoint = chatCompletionsUrl(safe.endpoint)
        val body = buildJsonObject {
            put("model", safe.model)
            put("max_tokens", safe.maxOutputTokens)
            put("messages", buildJsonArray {
                // system+developer 合并为标准 system role，兼容尚不认识 developer role 的端侧服务。
                add(message("system", "${prompts.system}\n\n=== DEVELOPER ===\n${prompts.developer}"))
                add(message("user", prompts.user))
            })
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = connectionFactory.open(endpoint)
        try {
            connection.instanceFollowRedirects = false // 不把 Authorization 转发到其他主机
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (safe.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${safe.apiKey}")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseBytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { readBounded(it, MAX_RESPONSE_BYTES) }
                ?: ByteArray(0)
            val response = responseBytes.toString(Charsets.UTF_8)
            if (status !in 200..299) {
                throw IOException("LLM endpoint returned HTTP $status: ${response.take(1_000)}")
            }
            val root = json.parseToJsonElement(response).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: error("LLM response missing choices[0].message.content")
        } finally {
            connection.disconnect()
        }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun chatCompletionsUrl(endpoint: String): URL {
        val uri = URI(endpoint)
        val path = uri.path.trimEnd('/')
        val finalPath = if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
        return URI(uri.scheme, null, uri.host, uri.port, finalPath, null, null).toURL()
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("LLM response exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

/** raw 离线透传；compact/focus 调用 OpenAI-compatible JSON chat completion。 */
class OpenAiCompatibleCompressor(
    private val settings: LlmSettings,
    private val client: LlmCompletionClient = OpenAiChatCompletionClient(),
) : Compressor {
    override val name: String = "openai-compatible"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun compress(request: FetchRequest, context: PageContext): CompressedPage {
        val mode = request.mode.lowercase()
        require(mode in MODES) { "unsupported mode: ${request.mode}" }
        if (mode == "raw") return context.structured.copy(page_id = context.pageId)
        if (mode == "focus") require(!request.intent.isNullOrBlank()) { "focus mode requires intent" }

        val config = settings.load().validated()
        require(config.configured) { "LLM is not configured" }
        require(context.markdown.length <= config.maxInputChars) {
            "page Markdown exceeds configured LLM input limit (${config.maxInputChars} chars); use raw mode"
        }
        val content = client.complete(
            config,
            LlmPrompts(
                system = PromptBuilder.systemPrompt(),
                developer = PromptBuilder.developerPrompt(mode),
                user = PromptBuilder.userPrompt(
                    context.structured.url.ifBlank { request.url },
                    context.title,
                    request.intent,
                    context.markdown,
                ),
            ),
        ).stripJsonFence()
        val semantic = json.decodeFromString<CompressedPage>(content)
        require(!semantic.summary.isNullOrBlank()) { "LLM output missing summary" }

        return semantic.copy(
            page_id = context.pageId,
            title = context.structured.title.ifBlank { context.title },
            url = context.structured.url.ifBlank { request.url },
            summary = semantic.summary.trim().take(150),
            key_points = semantic.key_points.orEmpty().map(String::trim).filter(String::isNotEmpty).take(10),
            sections = semantic.sections.orEmpty().map {
                it.copy(heading = it.heading.trim(), summary = it.summary.trim().take(80))
            }.filter { it.heading.isNotEmpty() },
            code_blocks = context.structured.code_blocks,
            tables = context.structured.tables,
            commands = context.structured.commands,
            downloads = context.structured.downloads,
            links = context.structured.links,
            interactive_elements = context.structured.interactive_elements,
            remaining_information = expansionManifest(context),
        )
    }

    private fun expansionManifest(context: PageContext): String = buildString {
        appendLine("page_id=${context.pageId}")
        context.sections.take(100).forEach { appendLine("${it.sectionId}\t${it.heading}") }
        if (context.sections.size > 100) append("… ${context.sections.size - 100} more sections")
    }.trimEnd()

    private fun String.stripJsonFence(): String {
        val value = trim()
        if (!value.startsWith("```")) return value
        val lines = value.lines().drop(1)
        return (if (lines.lastOrNull()?.trim()?.startsWith("```") == true) lines.dropLast(1) else lines)
            .joinToString("\n")
            .trim()
    }

    private companion object {
        val MODES = setOf("raw", "compact", "focus")
    }
}
