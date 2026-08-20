package com.kenjc.pagekit.engine

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 浏览器控制（PLAN.md M5）：
 * - 快照枚举可交互元素 → 稳定编号 [eN]（data-pk-eid 属性绑定到元素本身）
 * - click/type 派发真实事件（type 走原生 value setter + input/change，兼容 React 类框架）
 * - scroll 用 window.scrollBy；annotate 可视化标注开关
 * 元素失配（页面变化后元素被移除）→ not-found 错误，调用方重新快照。
 */
class BrowserController {

    suspend fun snapshot(webView: WebView): List<String> {
        val raw = eval(webView, SNAPSHOT_JS) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                JSONArray(raw).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** 以下操作返回 JSON：{"ok":bool,"result"/"error":...} */
    suspend fun click(webView: WebView, eid: String): String = eval(
        webView,
        opJs(eid) {
            """el.scrollIntoView({block:'center',inline:'nearest'});el.click();return ok('clicked:$eid');"""
        },
    ).orEvalFailed()

    suspend fun type(webView: WebView, eid: String, text: String): String = eval(
        webView,
        opJs(eid) {
            """
            if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') return err('not-inputtable:$eid');
            el.focus();
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            var v = ${jsString(text)};
            if (desc && desc.set) desc.set.call(el, v); else el.value = v;
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return ok('typed:$eid=' + v);
            """.trimIndent()
        },
    ).orEvalFailed()

    suspend fun scroll(webView: WebView, dx: Int, dy: Int): String = eval(
        webView,
        """(function(){window.scrollBy($dx,$dy);return JSON.stringify({ok:true,result:'y='+window.scrollY})})()""",
    ).orEvalFailed()

    suspend fun annotate(webView: WebView, on: Boolean): String {
        val color = if (on) "'2px solid #FF5722'" else "''"
        return eval(
            webView,
            """(function(){var c=$color;document.querySelectorAll('[data-pk-eid]').forEach(function(el){el.style.outline=c});""" +
                """return JSON.stringify({ok:true,result:'annotate=$on'})})()""",
        ).orEvalFailed()
    }

    suspend fun title(webView: WebView): String = eval(
        webView,
        """(function(){return JSON.stringify({ok:true,result:document.title})})()""",
    ).orEvalFailed()

    // ---- internals ----

    private fun String?.orEvalFailed(): String =
        this ?: """{"ok":false,"error":"eval-failed"}"""

    /** 构造按 eid 查找元素的操作 JS（eid 白名单校验防注入） */
    private fun opJs(eid: String, body: () -> String): String {
        require(eid.matches(Regex("e\\d+"))) { "bad eid: $eid" }
        return """
(function(){
  function ok(r){return JSON.stringify({ok:true,result:r})}
  function err(e){return JSON.stringify({ok:false,error:e})}
  var el = document.querySelector('[data-pk-eid="$eid"]');
  if (!el) return err('not-found:$eid');
  ${body()}
})()
        """.trimIndent()
    }

    /** JS 单引号字符串字面量 */
    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'"

    private suspend fun eval(webView: WebView, script: String): String? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) {
                        cont.resume(result?.takeUnless { it == "null" }?.let(::unquote))
                    }
                }
            }
        }

    /** evaluateJavascript 对字符串返回值包一层 JSON 引号，此处解包 */
    private fun unquote(result: String): String = runCatching {
        JSONObject("{\"v\":$result}").opt("v")?.toString() ?: result
    }.getOrDefault(result)

    private companion object {
        private val SNAPSHOT_JS = """
(function(){
  if (!window.__pkNextEid) window.__pkNextEid = 1;
  var sels = 'button, input, select, textarea, a[href], [role=button], [role=tab], [role=link], [role=switch], [onclick]';
  var els = Array.prototype.slice.call(document.querySelectorAll(sels));
  var out = [];
  els.forEach(function(el){
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return;
    var st = window.getComputedStyle(el);
    if (st.display === 'none' || st.visibility === 'hidden') return;
    var eid = el.getAttribute('data-pk-eid');
    if (!eid) { eid = 'e' + (window.__pkNextEid++); el.setAttribute('data-pk-eid', eid); }
    var tag = el.tagName.toLowerCase();
    if (tag === 'input' && el.type) tag = 'input:' + el.type;
    var label = (el.getAttribute('aria-label') || el.getAttribute('placeholder') ||
                 el.getAttribute('title') || el.value ||
                 (el.textContent || '').replace(/\s+/g,' ').trim() || el.getAttribute('name') || '').slice(0, 40);
    out.push('[' + eid + '] ' + tag + (label ? ' ' + label : ''));
  });
  return JSON.stringify(out);
})()
        """.trimIndent()
    }
}
