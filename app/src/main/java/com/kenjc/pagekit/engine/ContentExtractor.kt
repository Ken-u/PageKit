package com.kenjc.pagekit.engine

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 主内容提取器（PLAN.md M3）：
 * 1. 注入 Readability.js（asset，Apache-2.0）
 * 2. 克隆 document → NoiseRules 清洗
 * 3. Readability.parse()；失败兜底：<main>/<article>/最大文本块
 * 4. 返回主内容 HTML（后续由 HtmlToMarkdown 转换）
 *
 * 必须在主线程调用（WebView 约束）。
 */
class ContentExtractor(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val mode: String, // "readability" | "fallback"
        val title: String,
        val byline: String,
        val contentHtml: String,
    )

    /** Readability.js 内容不变：读一次缓存字符串，避免每次提取重复 asset IO 与大字符串分配。 */
    private val readabilityJs: String by lazy {
        context.assets.open("readability/Readability.js").bufferedReader().use { it.readText() }
    }

    /** 每次提取都注入：同 URL reload 也会创建全新的 JS document/global。 */
    private fun ensureReadability(webView: WebView) {
        // 顶层 var 在全局作用域求值 → window.Readability；尾部 module.exports 分支在浏览器上下文自动跳过
        webView.evaluateJavascript(readabilityJs, null)
    }

    suspend fun extract(webView: WebView, elementHidingRules: List<String> = emptyList()): Result {
        if (webView.url == null) return Result(false, "none", "", "", "")
        ensureReadability(webView)
        val selectors = (NoiseRules.SELECTORS + elementHidingRules).distinct()
        val raw = evalJs(webView, extractScript(selectors)) ?: return Result(false, "none", "", "", "")
        return withContext(Dispatchers.Default) {
            runCatching {
                val json = JSONObject(raw)
                Result(
                    ok = json.optBoolean("ok", false),
                    mode = json.optString("mode", "none"),
                    title = json.optString("title"),
                    byline = json.optString("byline"),
                    contentHtml = json.optString("html"),
                )
            }.getOrDefault(Result(false, "none", "", "", ""))
        }
    }

    private suspend fun evalJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    cont.resume(result?.takeUnless { it == "null" }?.let(::unquoteJsonString))
                }
            }
        }

    private fun unquoteJsonString(result: String): String = runCatching {
        JSONObject("{\"v\":$result}").opt("v")?.toString() ?: result
    }.getOrDefault(result)

    private companion object {
        // 注意：三引号字符串内不能用 \" 转义，JS 字符串一律用单引号
        private fun extractScript(selectors: List<String>): String {
            val selectorsJson = Json.encodeToString(selectors)
            return """
(function(){
  var NOISE = $selectorsJson;
  var docClone = document.cloneNode(true);
  for (var start = 0; start < NOISE.length; start += 100) {
    var chunk = NOISE.slice(start, start + 100);
    try {
      docClone.querySelectorAll(chunk.join(',')).forEach(function(el){ el.remove(); });
    } catch(e) {
      chunk.forEach(function(s){ try { docClone.querySelectorAll(s).forEach(function(el){ el.remove(); }); } catch(ignore){} });
    }
  }
  var result = null, mode = "readability";
  try {
    if (typeof Readability !== "undefined") {
      var parsed = new Readability(docClone).parse();
      if (parsed && parsed.content && parsed.content.length > 200) result = parsed;
    }
  } catch(e){}
  if (!result) {
    mode = "fallback";
    var cands = docClone.querySelectorAll("main,article,[role=main],#content,.content,.post,.entry-content");
    if (!cands.length) cands = docClone.querySelectorAll("div,section");
    var best = null, bestLen = 0;
    cands.forEach(function(el){
      var len = (el.textContent || "").trim().length;
      if (len > bestLen) { bestLen = len; best = el; }
    });
    if (best && bestLen > 200) {
      result = { title: document.title, byline: "", content: best.innerHTML };
    }
  }
  if (!result) return JSON.stringify({ok:false});
  return JSON.stringify({
    ok: true, mode: mode,
    title: result.title || document.title || "",
    byline: result.byline || "",
    html: result.content || ""
  });
})()
            """.trimIndent()
        }
    }
}
