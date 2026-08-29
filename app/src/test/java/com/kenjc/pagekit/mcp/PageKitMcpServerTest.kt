package com.kenjc.pagekit.mcp

import com.kenjc.pagekit.api.DefaultPageKitApi
import com.kenjc.pagekit.api.dto.CompressedPage
import com.kenjc.pagekit.api.dto.FileDownloadResult
import com.kenjc.pagekit.api.dto.FetchRequest
import com.kenjc.pagekit.api.dto.ExpandedSection
import com.kenjc.pagekit.api.dto.FileInfo
import com.kenjc.pagekit.compress.LlmConfig
import com.kenjc.pagekit.compress.LlmSettings
import com.kenjc.pagekit.engine.SearchHit
import com.kenjc.pagekit.net.ProxyConfig
import com.kenjc.pagekit.net.ProxySettings
import com.kenjc.pagekit.runtime.PageKitRuntime
import com.kenjc.pagekit.runtime.RuntimePageResult
import com.kenjc.pagekit.session.SingleSessionGateway
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKitMcpServerTest {

    @Test
    fun `使用端 MCP client 可发现并调用 usage tools`() = runBlocking {
        val runtime = FakeRuntime()
        val gateway = SingleSessionGateway(DefaultPageKitApi(runtime))
        val server = PageKitUsageTools(gateway).createServer()
        val transports = ChannelTransport.createLinkedPair()
        server.createSession(transports.serverTransport)
        val client = Client(Implementation(name = "pagekit-test", version = "1"))
        client.connect(transports.clientTransport)

        val names = client.listTools().tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "webfetch", "websearch", "expand",
                "browser_snapshot", "browser_click", "browser_type", "browser_scroll",
                "browser_select", "browser_navigate", "browser_back", "browser_url",
                "file_download", "file_list", "file_delete",
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

        val search = client.callTool(
            name = "websearch",
            arguments = mapOf("query" to "PageKit", "limit" to 1, "include_content" to false),
        )
        assertFalse(search.isError == true)
        assertEquals(
            "Example",
            search.structuredContent?.get("results")
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("title")
                ?.jsonPrimitive
                ?.content,
        )

        val expanded = client.callTool("expand", mapOf("section" to "s1", "page_id" to "page-1"))
        assertFalse(expanded.isError == true)
        assertEquals("s1", expanded.structuredContent?.get("section_id")?.toString()?.trim('"'))

        val rejected = client.callTool(
            name = "webfetch",
            arguments = mapOf("url" to "file:///data/local/tmp/secret"),
        )
        assertTrue(rejected.isError == true)

        client.close()
        server.close()
    }

    @Test
    fun `管理端 MCP client 可发现并调用 management tools`() = runBlocking {
        val runtime = FakeRuntime()
        val gateway = SingleSessionGateway(DefaultPageKitApi(runtime))
        val settings = FakeLlmSettings()
        val proxySettings = FakeProxySettings()
        val server = PageKitManagementTools(gateway, settings, proxySettings, onProxyChanged = {}).createServer()
        val transports = ChannelTransport.createLinkedPair()
        server.createSession(transports.serverTransport)
        val client = Client(Implementation(name = "pagekit-admin-test", version = "1"))
        client.connect(transports.clientTransport)

        val names = client.listTools().tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "profile_create", "profile_list", "profile_delete",
                "session_create", "session_list", "session_close", "session_close_idle",
                "llm_status", "llm_configure",
                "proxy_status", "proxy_configure",
            ),
            names,
        )

        val profiles = client.callTool("profile_list", emptyMap())
        assertFalse(profiles.isError == true)
        assertEquals(
            "default",
            profiles.structuredContent?.get("profiles")?.jsonArray?.single()?.jsonObject
                ?.get("profileId")?.jsonPrimitive?.content,
        )

        val sessions = client.callTool("session_list", emptyMap())
        assertFalse(sessions.isError == true)
        assertEquals(
            "default",
            sessions.structuredContent?.get("sessions")?.jsonArray?.single()?.jsonObject
                ?.get("sessionId")?.jsonPrimitive?.content,
        )

        val configured = client.callTool(
            "llm_configure",
            mapOf("endpoint" to "https://llm.example/v1", "model" to "test-model", "api_key" to "secret"),
        )
        assertFalse(configured.isError == true)
        assertEquals("secret", settings.config.apiKey)
        assertFalse(configured.content.toString().contains("secret"))

        val proxyStatus = client.callTool("proxy_status", emptyMap())
        assertFalse(proxyStatus.isError == true)
        assertEquals(false, proxyStatus.structuredContent?.get("enabled")?.jsonPrimitive?.booleanOrNull)

        val proxyConfigured = client.callTool(
            "proxy_configure",
            mapOf("enabled" to true, "host" to "127.0.0.1", "port" to 7890),
        )
        assertFalse(proxyConfigured.isError == true)
        assertEquals(true, proxyConfigured.structuredContent?.get("enabled")?.jsonPrimitive?.booleanOrNull)
        assertEquals("127.0.0.1", proxyConfigured.structuredContent?.get("host")?.jsonPrimitive?.content)
        assertEquals(7890, proxyConfigured.structuredContent?.get("port")?.jsonPrimitive?.intOrNull)

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

        override suspend fun search(url: String, engine: String, limit: Int): List<SearchHit> =
            listOf(SearchHit(siteName = "example.com", title = "Example", url = "https://example.com"))

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
        override suspend fun currentUrl(): String = lastRequest?.url.orEmpty()
        override suspend fun select(elementId: String, value: String): String = ok()
        override suspend fun navigate(url: String): Pair<Boolean, String> = true to url
        override suspend fun goBack(): Pair<Boolean, String> = false to (lastRequest?.url.orEmpty())

        override suspend fun downloadFile(url: String, suggestedFileName: String) =
            throw UnsupportedOperationException("not used in this test")
        override suspend fun downloadPendingFile(): FileDownloadResult? = null
        override suspend fun listDownloadedFiles(): List<FileInfo> = emptyList()
        override suspend fun deleteDownloadedFile(fileName: String): Boolean = false

        private fun ok() = """{"ok":true}"""
    }

    private class FakeLlmSettings : LlmSettings {
        var config = LlmConfig()
        override fun load(): LlmConfig = config
        override fun save(config: LlmConfig) {
            this.config = config.validated()
        }
    }

    private class FakeProxySettings : ProxySettings {
        var config = ProxyConfig()
        override fun load(): ProxyConfig = config
        override fun save(config: ProxyConfig) {
            this.config = config
        }
    }
}
