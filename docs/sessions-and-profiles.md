# Session 与 Profile 隔离

PageKit 把浏览器隔离拆成两层：

| 层级 | 隔离内容 | 共享内容 |
| --- | --- | --- |
| Session | WebView、URL/历史、DOM、JS Context、滚动、表单、`[eN]`、操作锁 | 同 Profile 的 Cookie/存储/HTTP 缓存 |
| Profile | Cookie、localStorage、IndexedDB、WebView HTTP 缓存、WebView 数据目录 | LLM 配置、广告规则 |

`default` Profile 位于主进程，包含 UI 的 `default` Session，并可再创建最多 3 个离屏 Session。
另有 3 个固定 worker 进程槽，每个槽承载一个隔离 Profile，每个 Profile 最多 4 个 Session。

隔离 Profile 进程在 Application 初始化最早阶段、创建任何 WebView 之前调用
`WebView.setDataDirectorySuffix("pagekit_profile_N")`。主进程通过 AIDL 调用 worker；返回正文使用
`ParcelFileDescriptor` 文件通道，因此不会把大篇 Markdown 塞进 Binder transaction。

## MCP 使用顺序

1. 创建 Profile：

   ```json
   {"name":"profile_create","arguments":{"profile_id":"work"}}
   ```

2. 在 Profile 内创建 Session：

   ```json
   {"name":"session_create","arguments":{"profile_id":"work"}}
   ```

3. 将返回的 `sessionId` 传给页面或浏览器工具：

   ```json
   {
     "name":"webfetch",
     "arguments":{
       "session_id":"s1_0123456789abcdef",
       "url":"https://example.com",
       "mode":"raw"
     }
   }
   ```

4. 不再使用时调用 `session_close`。`profile_delete` 会关闭该 Profile 全部 Session，并清除其
   Cookie、WebStorage、HTTP 缓存、WebView data directory 中的站点数据及 PageKit 页面缓存。

Profile 到进程槽的映射会持久化；普通 Session 是进程内资源，App 进程重启后需要重新创建。
Profile ID 支持 1–32 位字母、数字、点、下划线和短横线。

## 并发模型

- 同一个 Session 的所有操作严格串行，避免导航与元素编号相互污染。
- 不同 Session 有独立 Mutex，可以同时加载页面；WebView API 调度仍遵守 Android 主线程约束。
- `websearch(include_content=true)` 在其 Session 内依次抓取结果详情页。
- 达到 Session/Profile 上限时会明确报错，不会隐式销毁正在使用的 Session。

## 验证

```bash
./build.sh test
./build.sh sessiontest
```

实机测试会验证两个 Session 的 DOM 保持独立、两个 Profile worker 进程同时存在、独立
`app_webview_pagekit_profile_N` 数据目录、同 Profile Cookie 共享和跨 Profile Cookie 不泄漏。
