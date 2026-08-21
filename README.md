# PageKit

**WebFetch 信息压缩引擎**的 Android 实现（见 [SPECS.md](SPECS.md)）——把已完整渲染的网页转换成高信息密度、低 Token 消耗、几乎无信息损失的结构化结果，供下游 LLM/Agent 继续推理。

> 保真压缩（Information Compression），而不是内容摘要（Summary）。

## V1 功能

| 能力 | 说明 |
| --- | --- |
| 网页渲染 | 应用内 WebView（JS/Cookie/登录态可用），顶部 Tab 真实可交互 |
| 搜索引擎直航 | 五引擎 URL 模板（Bing 默认 / Baidu / Sogou / 360 / Google），输入关键词直接拼接结果页 URL 导航，无需进首页 |
| 主内容提取 | Readability.js + 克隆 DOM 去噪（SPECS.md 忽略清单），最大文本块兜底 |
| Markdown 输出 | flexmark html2md，代码/表格/链接原文保留 |
| 结构化 JSON | SPECS.md Schema：确定性字段（code_blocks/tables/commands/links/downloads）规则填充，语义字段留待 LLM |
| 浏览器控制 | 元素快照 `[eN]` 编号 + click/type/scroll + 元素标注开关 |
| Prompt 导出 | SPECS.md System/Developer/User 三段完整 Prompt，可复制贴给任意 LLM |
| MCP 契约预留 | `api/PageKitApi`（fetch/webSearch/expand/交互元素/浏览器控制），V2 薄封装上 MCP |

## 架构

```text
URL / 关键词 → SearchEngine(引擎URL模板) → WebPageLoader(共享WebView)
    → ContentExtractor(Readability+NoiseRules 去噪) → HtmlToMarkdown
    → StructuredAssembler(JSON 骨架)
    → compress/(Compressor V1=Noop | V2=LLM)
    → ui/(网页/Markdown/JSON/Prompt 四 Tab)

engine/BrowserController: [eN] 快照 + click/type/scroll/annotate
engine/SearchEngine: 引擎注册表(bing/baidu/sogou/360/google)，URL 模板拼接
api/PageKitApi: MCP 工具契约（fetch/webSearch/expand/交互元素/浏览器控制）
engine/adblock/AdBlocker: SPI 预留（V2 挂 StevenBlack hosts / EasyList）
```

## 构建

```bash
# 一键脚本（自动配 JAVA_HOME/ANDROID_HOME，优先系统 adb）
./build.sh build            # debug APK
./build.sh test             # JVM 单测
./build.sh install [serial] # 安装到实机
./build.sh verify [serial]  # 实机全链路验证（加载→提取→md/json/prompt）
./build.sh searchtest ["查询词"] ["https://www.bing.com"] [serial]  # 搜索结果页直航→提取（支持 baidu/bing/sogou/360/google）
./build.sh opentest ["查询词"] ["https://www.bing.com"] [serial]   # 搜索→点进第一条真实结果→详情页提取
./build.sh release          # release APK
./build.sh clean

# 手动方式：需 JDK 17 + Android SDK（platform-35 / build-tools 35）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## adb 验证通道

```bash
# 加载页面并自动提取（结果落盘 files/last_result.txt）
adb shell am start -n com.kenjc.pagekit/.MainActivity \
  -d "file:///android_asset/test/testpage.html" --ez extract true

# 读取 Markdown / JSON
adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT \
  -n com.kenjc.pagekit/.ResultTunnelReceiver
adb shell am broadcast -a com.kenjc.pagekit.FETCH_RESULT --es format json \
  -n com.kenjc.pagekit/.ResultTunnelReceiver

# 浏览器控制（结果落盘 files/control_result.txt，run-as 读取）
adb shell am start -a com.kenjc.pagekit.CONTROL --es op snapshot
adb shell am start -a com.kenjc.pagekit.CONTROL --es op type --es eid e4 --es text "关键词"
adb shell am start -a com.kenjc.pagekit.CONTROL --es op click --es eid e5
adb shell am start -a com.kenjc.pagekit.CONTROL --es op scroll --ei dy 400
adb shell am start -a com.kenjc.pagekit.CONTROL --es op prompt --es intent "部署指南"
adb shell run-as com.kenjc.pagekit cat files/control_result.txt

# 搜索引擎直航（关键词 → 结果页 URL 拼接 → 加载并提取）
adb shell am start -n com.kenjc.pagekit/.MainActivity \
  -a com.kenjc.pagekit.SEARCH --es query "RTX5090 部署" --es engine bing

# 单测（JVM）
./gradlew :app:testDebugUnitTest
```

## V2 路线

- LLM Compressor（OpenAI 兼容 / 端侧），Compact/Focus 模式语义压缩，`expand()` 章节缓存
- MCP server（工具面已冻结，见 `api/PageKitApi.kt`）
- `AdBlocker` 开源规则接入（StevenBlack hosts MIT / EasyList 元素隐藏）
- OkHttp 静态抓取快速通道

## 许可

- Readability.js：Apache-2.0（`app/src/main/assets/readability/`）
