package com.kenjc.pagekit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    @Test
    fun `内置五引擎 URL 模板拼接`() {
        val url = SearchEngine.buildResultUrl("bing", "RTX5090 部署")
        assertEquals("https://www.bing.com/search?q=RTX5090%20%E9%83%A8%E7%BD%B2", url)
        assertNotNull(SearchEngine.buildResultUrl("baidu", "x"))
        assertNotNull(SearchEngine.buildResultUrl("sogou", "x"))
        assertNotNull(SearchEngine.buildResultUrl("360", "x"))
        assertNotNull(SearchEngine.buildResultUrl("google", "x"))
    }

    @Test
    fun `未注册引擎返回 null`() {
        assertNull(SearchEngine.buildResultUrl("duckduckgo", "x"))
    }

    @Test
    fun `引擎名宽松解析 大小写与URL形式`() {
        assertEquals("bing", SearchEngine.resolve("Bing"))
        assertEquals("bing", SearchEngine.resolve("https://www.bing.com/"))
        assertEquals("baidu", SearchEngine.resolve("www.baidu.com"))
        assertNull(SearchEngine.resolve("unknown"))
    }

    @Test
    fun `自定义引擎注册`() {
        SearchEngine.addEngine("zhihu", "https://www.zhihu.com/search?q={q}")
        assertEquals(
            "https://www.zhihu.com/search?q=%E6%B5%8B%E8%AF%95",
            SearchEngine.buildResultUrl("zhihu", "测试"),
        )
    }

    @Test
    fun `默认引擎与清单`() {
        assertEquals("bing", SearchEngine.DEFAULT)
        assertTrue(SearchEngine.names.containsAll(listOf("bing", "baidu", "sogou", "360", "google")))
    }
}
