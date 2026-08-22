package com.kenjc.pagekit.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** MCP tool 共享辅助：错误封装、JSON schema 构建、参数解析。 */
internal object McpToolHelpers {

    val json = Json { encodeDefaults = false }

    suspend fun toolResult(block: suspend () -> JsonObject): CallToolResult = try {
        val structured = block()
        CallToolResult(
            content = listOf(TextContent(text = json.encodeToString(structured))),
            isError = false,
            structuredContent = structured,
        )
    } catch (error: Throwable) {
        CallToolResult(
            content = listOf(TextContent(text = error.message ?: error::class.java.simpleName)),
            isError = true,
        )
    }

    suspend fun booleanResult(block: suspend () -> Boolean): CallToolResult = toolResult {
        val ok = block()
        require(ok) { "browser operation failed; take a new browser_snapshot and retry" }
        buildJsonObject { put("ok", true) }
    }

    fun schema(
        vararg properties: Pair<String, JsonObject>,
        required: List<String> = emptyList(),
    ) = ToolSchema(
        properties = buildJsonObject { properties.forEach { (name, value) -> put(name, value) } },
        required = required,
    )

    fun stringProperty(description: String, enum: List<String> = emptyList()) = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum.isNotEmpty()) put("enum", JsonArray(enum.map(::JsonPrimitive)))
    }

    fun integerProperty(description: String, default: Int) = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("default", default)
    }

    fun booleanProperty(description: String, default: Boolean) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        put("default", default)
    }

    fun sessionProperty() = stringProperty("Browser session ID; defaults to the visible UI session")

    fun str(args: JsonObject?, name: String): String? =
        args?.get(name)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)

    fun requiredStr(args: JsonObject?, name: String): String =
        requireNotNull(str(args, name)) { "missing required argument: $name" }

    fun rawStr(args: JsonObject?, name: String): String =
        runCatching { args?.get(name)?.jsonPrimitive?.content }.getOrNull().orEmpty()

    fun intArg(args: JsonObject?, name: String): Int? = args?.get(name)?.jsonPrimitive?.intOrNull

    fun boolArg(args: JsonObject?, name: String): Boolean? = args?.get(name)?.jsonPrimitive?.booleanOrNull
}
