package com.kenjc.pagekit.engine.adblock

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleHttpDownloaderTest {

    @Test
    fun `发送条件请求并保留响应元数据`() {
        val connection = FakeConnection(
            url = URL("https://rules.example/list.txt"),
            code = 200,
            body = "0.0.0.0 ads.example\n".toByteArray(),
            headers = mapOf("ETag" to "v2", "Last-Modified" to "Friday"),
        )
        val downloader = RuleHttpDownloader { connection }

        val result = downloader.fetch(source(maxBytes = 1_024), "v1", "Thursday")
            as RuleFetchResult.Downloaded

        assertArrayEquals(connection.body, result.bytes)
        assertEquals("v2", result.etag)
        assertEquals("Friday", result.lastModified)
        assertEquals("v1", connection.requests["If-None-Match"])
        assertEquals("Thursday", connection.requests["If-Modified-Since"])
    }

    @Test
    fun `304 不读取正文`() {
        val downloader = RuleHttpDownloader {
            FakeConnection(it, HttpURLConnection.HTTP_NOT_MODIFIED, ByteArray(0))
        }
        assertTrue(downloader.fetch(source(), "v1", null) is RuleFetchResult.NotModified)
    }

    @Test(expected = IOException::class)
    fun `超过上限的响应被拒绝`() {
        val downloader = RuleHttpDownloader {
            FakeConnection(it, HttpURLConnection.HTTP_OK, ByteArray(17))
        }
        downloader.fetch(source(maxBytes = 16), null, null)
    }

    @Test(expected = IOException::class)
    fun `拒绝降级到 http 的重定向`() {
        val downloader = RuleHttpDownloader {
            FakeConnection(
                it,
                HttpURLConnection.HTTP_MOVED_TEMP,
                ByteArray(0),
                headers = mapOf("Location" to "http://rules.example/list.txt"),
            )
        }
        downloader.fetch(source(), null, null)
    }

    private fun source(maxBytes: Int = 1_024) = RuleSource(
        id = "test",
        url = "https://rules.example/list.txt",
        cacheFileName = "list.txt",
        maxBytes = maxBytes,
    )

    private class FakeConnection(
        url: URL,
        private val code: Int,
        val body: ByteArray,
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(url) {
        val requests = linkedMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun setRequestProperty(key: String, value: String) {
            requests[key] = value
        }
    }
}
