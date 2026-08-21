package com.kenjc.pagekit.engine

import android.webkit.WebView
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
data class SearchHit(
    val siteName: String,
    val title: String,
    val url: String,
    val snippet: String = "",
    val date: String = "",
    val icon: String = "",
    val mime: String = "",
)

/** 从已渲染的主流搜索结果页提取统一结果列表。 */
class SearchResultExtractor {
    suspend fun extract(webView: WebView, engine: String, limit: Int): List<SearchHit> {
        val raw = withContext(Dispatchers.Main.immediate) {
            evalJs(webView, extractionScript(engine, limit)) ?: "[]"
        }
        return parse(raw, limit)
    }

    private fun parse(raw: String, limit: Int): List<SearchHit> = runCatching {
        JSON.decodeFromString<List<SearchHit>>(raw)
            .asSequence()
            .filter { it.title.isNotBlank() && isHttpUrl(it.url) }
            .distinctBy { it.url }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    private fun isHttpUrl(url: String): Boolean = runCatching {
        URI(url).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    cont.resume(result?.takeUnless { it == "null" }?.let(::unquote))
                }
            }
        }

    private fun unquote(result: String): String = runCatching {
        JSONObject("{\"v\":$result}").opt("v")?.toString() ?: result
    }.getOrDefault(result)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun extractionScript(engine: String, limit: Int): String {
            val engineJson = Json.encodeToString(engine.lowercase())
            return """
(function(){
  var engine = $engineJson;
  var limit = ${limit.coerceIn(1, 20)};
  var configs = {
    bing: {
      cards: 'li.b_algo, li.b_ans', title: 'h2, h3',
      snippet: '.b_caption p, .b_snippet, p', date: '.news_dt, .b_secondaryText',
      site: '.b_attribution cite, cite'
    },
    google: {
      cards: 'div.MjjYud, div.g', title: 'h3',
      snippet: '[data-sncf], .VwiC3b, .IsZvec', date: '.LEwnzc, span.MUxGbd',
      site: '.VuuXrf, .qLRx3b'
    },
    baidu: {
      cards: 'div.c-container, div.result, div.result-op', title: 'h3, h2',
      snippet: '.c-abstract, [class*=content-right], [class*=abstract], .c-span-last',
      date: '.c-color-gray2, .c-color-gray', site: '.c-showurl, .c-color-gray'
    },
    sogou: {
      cards: '.vrwrap, .rb, .results > div', title: 'h3, h2',
      snippet: '.str-text-info, .ft, .text-layout', date: '.news-from, .gray',
      site: 'cite, .citeurl'
    },
    '360': {
      cards: '.res-list, .result, li.res-list', title: '.res-title, h3, h2',
      snippet: '.res-desc, .summary, .content', date: '.res-time, .gray',
      site: 'cite, .res-linkinfo'
    }
  };
  var cfg = configs[engine] || configs.bing;
  function text(el){ return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
  function first(card, selector){ try { return card.querySelector(selector); } catch(e) { return null; } }
  function anchorFor(card, titleEl){
    if (!titleEl) return null;
    if (titleEl.tagName === 'A') return titleEl;
    return titleEl.closest('a[href]') || first(card, 'a[href]');
  }
  function absoluteUrl(anchor){
    if (!anchor) return '';
    try { return new URL(anchor.getAttribute('href') || '', location.href).href; } catch(e) { return ''; }
  }
  function host(url){
    try { return new URL(url).hostname.replace(/^www\./, ''); } catch(e) { return ''; }
  }
  var out = [], seen = {};
  function add(card, forcedTitle){
    if (out.length >= limit) return;
    var titleEl = forcedTitle || first(card, cfg.title);
    var anchor = anchorFor(card, titleEl);
    var url = absoluteUrl(anchor);
    var title = text(titleEl);
    if (!title || !/^https?:/i.test(url) || seen[url]) return;
    seen[url] = true;
    var site = text(first(card, cfg.site)) || host(url);
    out.push({
      siteName: site.substring(0, 200), title: title.substring(0, 500), url: url,
      snippet: text(first(card, cfg.snippet)).substring(0, 2000),
      date: text(first(card, cfg.date)).substring(0, 100), icon: '', mime: ''
    });
  }
  try { document.querySelectorAll(cfg.cards).forEach(add); } catch(e) {}
  if (out.length < limit) {
    document.querySelectorAll('h2, h3').forEach(function(h){
      var card = h.parentElement;
      for (var depth = 0; card && card !== document.body && depth < 6; depth++, card = card.parentElement) {
        if (first(card, cfg.snippet) || first(card, 'p')) break;
      }
      add(card && card !== document.body ? card : h.parentElement, h);
    });
  }
  return JSON.stringify(out);
})()
            """.trimIndent()
        }
    }
}
