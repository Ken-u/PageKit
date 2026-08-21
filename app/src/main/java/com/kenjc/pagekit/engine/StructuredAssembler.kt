package com.kenjc.pagekit.engine

import android.content.Context
import android.webkit.WebView
import com.kenjc.pagekit.api.dto.CompressedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 规则化结构化装配（PLAN.md M4）：
 * 从当前页面 JS 快照确定性填充 JSON 骨架（SPECS.md Schema）。
 * 语义字段（summary/key_points/sections/warnings 归类等）V2 由 LLM 填充。
 *
 * 必须在主线程调用（WebView 约束）。
 */
class StructuredAssembler {

    suspend fun assemble(webView: WebView, elementHidingRules: List<String> = emptyList()): CompressedPage {
        // evaluateJavascript 必须主线程；JSON 解析留在当前上下文
        val selectors = (NoiseRules.SELECTORS + elementHidingRules).distinct()
        val raw = withContext(Dispatchers.Main.immediate) { evalJs(webView, snapshotScript(selectors)) } ?: "{}"
        return parse(raw, webView.url ?: "")
    }

    private fun parse(raw: String, url: String): CompressedPage = runCatching {
        val json = JSONObject(raw)
        CompressedPage(
            title = json.optString("title"),
            url = json.optString("url").ifBlank { url },
            // 确定性字段（规则填充）
            code_blocks = json.optJSONArray("codeBlocks")?.mapObjects { o ->
                CompressedPage.CodeBlock(
                    language = o.optString("language"),
                    content = o.optString("content"),
                )
            },
            tables = json.optJSONArray("tables")?.mapObjects { o ->
                CompressedPage.Table(
                    title = o.optString("title"),
                    markdown = o.optString("markdown"),
                )
            },
            commands = json.optJSONArray("commands")?.mapStrings { it },
            downloads = json.optJSONArray("downloads")?.mapObjects { o ->
                CompressedPage.Download(name = o.optString("name"), url = o.optString("url"))
            },
            links = json.optJSONArray("links")?.mapObjects { o ->
                CompressedPage.Link(text = o.optString("text"), url = o.optString("url"))
            },
            // V2 LLM 填充（V1 显式 null，与 SPECS.md 一致）
            interactive_elements = json.optJSONArray("interactive")?.mapStrings { it },
        )
    }.getOrDefault(CompressedPage(url = url))

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

        /**
         * 页面快照 JS：
         * - 在克隆 DOM 上按 NoiseRules 清洗后统计
         * - links 限正文（去导航/页脚/社交），最多 30 条
         * - commands：从 <code>/<pre> 与行内文本中匹配已知命令前缀，去重保序，最多 20 条
         */
        private fun snapshotScript(selectors: List<String>): String {
            val selectorsJson = Json.encodeToString(selectors)
            return """
(function(){
  var NOISE = $selectorsJson;
  var clone = document.cloneNode(true);
  for (var start = 0; start < NOISE.length; start += 100) {
    var chunk = NOISE.slice(start, start + 100);
    try {
      clone.querySelectorAll(chunk.join(',')).forEach(function(el){ el.remove(); });
    } catch(e) {
      chunk.forEach(function(s){ try { clone.querySelectorAll(s).forEach(function(el){ el.remove(); }); } catch(ignore){} });
    }
  }

  function txt(el){ return (el.textContent||'').replace(/\s+/g,' ').trim(); }

  var codeBlocks = [];
  clone.querySelectorAll('pre').forEach(function(pre){
    var lang = '';
    var c = pre.querySelector('code');
    if (c && c.className) {
      var m = c.className.match(/(?:language|lang)-([a-zA-Z0-9+#-]+)/);
      if (m) lang = m[1];
    }
    codeBlocks.push({ language: lang, content: (pre.textContent||'').trim() });
  });

  var tables = [];
  clone.querySelectorAll('table').forEach(function(t, i){
    var title = '';
    var cap = t.querySelector('caption');
    if (cap) title = txt(cap);
    else {
      var h = t.closest('section,div');
      if (h) {
        var prev = h.querySelector('h1,h2,h3,h4');
        if (prev) title = txt(prev);
      }
    }
    var rows = [];
    t.querySelectorAll('tr').forEach(function(tr){
      var cells = [];
      tr.querySelectorAll('th,td').forEach(function(td){ cells.push(txt(td)); });
      if (cells.length) rows.push('| ' + cells.join(' | ') + ' |');
    });
    if (rows.length > 1) {
      var head = rows[0];
      var n = head.split('|').length - 2;
      var line = '|' + new Array(n+1).join('---|');
      tables.push({ title: title || ('table-' + (i+1)), markdown: [head, line].concat(rows.slice(1)).join('\n') });
    }
  });

  var CMD_RE = /^(?:docker|pip3?|npm|yarn|pnpm|cargo|git|adb|fastboot|curl|wget|sudo|brew|apt(?:-get)?|yum|systemctl|make|cmake|python3?|node|java|mvn|gradle|kubectl|helm|nvidia-smi|export|cd|ls)\b/;
  var seen = {}, commands = [];
  function addCmd(s){
    s = s.trim();
    if (!s || seen[s]) return;
    if (CMD_RE.test(s)) { seen[s] = 1; commands.push(s); }
  }
  clone.querySelectorAll('pre,code').forEach(function(el){
    (el.textContent||'').split('\n').forEach(function(line){
      addCmd(line.replace(/^[$%>]\s+/, '').replace(/\\\s*$/, '').trim());
    });
  });
  commands = commands.slice(0, 20);

  var downloads = [];
  clone.querySelectorAll('a[href]').forEach(function(a){
    var h = a.getAttribute('href') || '';
    if (/\.(zip|tar\.gz|tgz|tar|apk|aar|jar|deb|rpm|dmg|exe|msi|whl|bin|img|gz|pdf)([?#]|$)/i.test(h)) {
      downloads.push({ name: txt(a) || h.split('/').pop(), url: new URL(h, location.href).href });
    }
  });

  var links = [], seenHref = {};
  clone.querySelectorAll('main a[href], article a[href]').forEach(function(a){
    var h = a.getAttribute('href') || '';
    if (!h || h.charAt(0) === '#' || seenHref[h]) return;
    if (/javascript:|mailto:/i.test(h)) return;
    seenHref[h] = 1;
    var t = txt(a);
    if (t && t.length <= 80) links.push({ text: t, url: new URL(h, location.href).href });
  });
  links = links.slice(0, 30);

  return JSON.stringify({
    title: document.title || '',
    url: location.href,
    codeBlocks: codeBlocks.slice(0, 30),
    tables: tables.slice(0, 10),
    commands: commands,
    downloads: downloads.slice(0, 20),
    links: links,
    interactive: []
  });
})()
            """.trimIndent()
        }
    }
}

private fun <T> org.json.JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(mapper) }

private fun org.json.JSONArray.mapStrings(mapper: (String) -> String): List<String> =
    (0 until length()).mapNotNull { i -> optString(i).takeIf(String::isNotBlank)?.let(mapper) }
