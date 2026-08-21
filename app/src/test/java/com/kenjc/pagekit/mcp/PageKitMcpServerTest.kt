package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.compress.LlmConfig
import com.kenjc.pagekit.compress.LlmSettings
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKitMcpServerTest {

    @Test
    fun `官方 MCP client 可发现并调用 PageKit tools`() = runBlocking {
        val runtime = FakeRuntime()
        val settings = FakeSettings()
        val server = PageKitMcpTools(DefaultPageKitApi(runtime), settings).createServer()
        val transports = ChannelTransport.createLinkedPair()
        server.createSession(transports.serverTransport)
        val client = Client(Implementation(name = "pagekit-test", version = "1"))
        client.connect(transports.clientTransport)

        val names = client.listTools().tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "webfetch", "websearch", "expand", "browser_snapshot", "browser_click", "browser_type",
                "browser_scroll", "llm_status", "llm_configure",
            ),
            names,
        )

        val snapshot = client.callTool("browser_snapshot", emptyMap())
        assertFalse(snapshot.isError == true)
        assertNotNull(snapshot.structuredContent?.get("elements"))

        val fetch = client.callTool(
            name = "webfetch",
            arguments = mapOf("url" to "https://example.com", "mode" to "raw"),
        )
        assertFalse(fetch.isError == true)
        assertEquals("https://example.com", runtime.lastRequest?.url)
        assertTrue(fetch.content.isNotEmpty())

        val expanded = client.callTool("expand", mapOf("section" to "s1", "page_id" to "page-1"))
        assertFalse(expanded.isError == true)
        assertEquals("s1", expanded.structuredContent?.get("section_id")?.toString()?.trim('"'))

        val configured = client.callTool(
            "llm_configure",
            mapOf("endpoint" to "https://llm.example/v1", "model" to "test-model", "api_key" to "secret"),
        )
        assertFalse(configured.isError == true)
        assertEquals("secret", settings.config.apiKey)
        assertFalse(configured.content.toString().contains("secret"))

        val rejected = client.callTool(
            name = "webfetch",
            arguments = mapOf("url" to "file:///data/local/tmp/secret"),
        )
        assertTrue(rejected.isError == true)

        client.close()
        server.close()
    }

    private class FakeRuntime : PageKitRuntime {
        var lastRequest: FetchRequest? = null

        override suspend fun fetch(request: FetchRequest): RuntimePageResult {
            lastRequest = request
            return RuntimePageResult(
                page = CompressedPage(title = "Example", url = request.url),
                markdown = "# Example",
                byline = "",
                extractionMode = "test",
                durationMs = 1,
            )
        }

        override suspend fun extractCurrent(request: FetchRequest): RuntimePageResult = fetch(request)
        override suspend fun expand(pageId: String?, section: String): ExpandedSection =
            ExpandedSection(pageId ?: "latest", section, "Heading", "# Heading\nOriginal")
        override suspend fun listInteractiveElements(): List<String> = listOf("[e1] button Go")
        override suspend fun click(elementId: String): String = ok()
        override suspend fun type(elementId: String, text: String): String = ok()
        override suspend fun scroll(dx: Int, dy: Int): String = ok()
        override suspend fun annotate(on: Boolean): String = ok()
        override suspend fun title(): String = ok()
        override suspend fun inspect(elementId: String): String = ok()
        override suspend fun armSubmitHook(): String = ok()

        private fun ok() = """{"ok":true}"""
    }

    private class FakeSettings : LlmSettings {
        var config = LlmConfig()
        override fun load(): LlmConfig = config
        override fun save(config: LlmConfig) {
            this.config = config.validated()
        }
    }
}
