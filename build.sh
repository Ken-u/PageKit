#!/usr/bin/env bash
# PageKit 构建脚本
#
# 用法：
#   ./build.sh              # 构建 debug APK（默认）
#   ./build.sh build        # 同上
#   ./build.sh release      # 构建 release APK
#   ./build.sh test         # JVM 单测
#   ./build.sh install [serial]   # 构建并安装到实机（默认取第一台 device）
#   ./build.sh verify [serial]    # 实机全链路验证（加载测试页→提取→md/json/prompt）
#   ./build.sh mcptest [serial]   # 实机 MCP 鉴权、协议握手与 tools/list 验证
#   ./build.sh providertest [serial] # 实机 Kimi SearchWeb provider 协议验证
#   ./build.sh llmtest [serial]   # 实机 compact/focus + expand 闭环（本地假 OpenAI 端点）
#   ./build.sh clean
#
# 环境说明（全部用户目录，无需 root/sudo）：
#   JDK 17        ~/.sdk/jdk-17           （AGP 8.x 要求 17+）
#   Gradle 8.10.2 ~/.sdk/gradle           （优先；缺失时回退 ./gradlew）
#   Android SDK   ~/.sdk/android-sdk      （platform-35 / build-tools 35.0.0 / platform-tools）
#   可用 PAGEKIT_SDK_ROOT 覆盖根目录，如 PAGEKIT_SDK_ROOT=/opt/sdk ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

# ---- 环境配置 ----
SDK_ROOT="${PAGEKIT_SDK_ROOT:-$HOME/.sdk}"
export JAVA_HOME="$SDK_ROOT/jdk-17"
export ANDROID_HOME="$SDK_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Gradle：优先本地安装（免下载发行包），否则回退项目 wrapper
GRADLE_CMD="$SDK_ROOT/gradle/bin/gradle"
if [ ! -x "$GRADLE_CMD" ]; then
    GRADLE_CMD="$PWD/gradlew"
fi

# ---- 前置检查 ----
fail() { echo "✗ $1" >&2; exit 1; }

[ -d "$JAVA_HOME" ] || fail "未找到 JDK17：$JAVA_HOME
  安装：mkdir -p ~/.sdk && tar -xzf jdk17.tar.gz -C ~/.sdk && mv ~/.sdk/jdk-17.* ~/.sdk/jdk-17"
[ -d "$ANDROID_HOME/platforms/android-35" ] || fail "未找到 platform-35：$ANDROID_HOME/platforms"
[ -x "$GRADLE_CMD" ] || fail "未找到 Gradle（$GRADLE_CMD）
  安装：unzip gradle-8.10.2-bin.zip -d ~/.sdk && mv ~/.sdk/gradle-8.10.2 ~/.sdk/gradle"
command -v "$JAVA_HOME/bin/java" >/dev/null || fail "JDK17 不可用：$JAVA_HOME/bin/java"

# adb：优先系统已装（避免与在跑的 adb server 版本冲突），否则用 SDK platform-tools
if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
else
    ADB="$ANDROID_HOME/platform-tools/adb"
fi

# ---- 命令 ----
CMD="${1:-build}"

DEFAULT_SERIAL="ATS3588002"   # 固定验证设备（USB）；可被环境变量 PAGEKIT_DEVICE 或命令参数覆盖

# 设备联网预检：1=有网 0=无网
device_online_ok() { # device_online_ok "-s serial" 或 "-t tid"
    "$ADB" $1 shell "ping -c 1 -W 2 223.5.5.5" >/dev/null 2>&1
}

pick_device() {
    local serial="${1:-${PAGEKIT_DEVICE:-$DEFAULT_SERIAL}}"
    # 指定 serial 可用且有网 → "-s serial"
    if "$ADB" -s "$serial" shell true >/dev/null 2>&1; then
        if device_online_ok "-s $serial"; then
            echo "-s $serial"
            return
        fi
        echo "（提示：$serial 无网络，寻找有网设备）" >&2
    fi
    # 遍历在线设备：优先有网的
    local line cand tid
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        if "$ADB" -s "$line" shell true >/dev/null 2>&1 && device_online_ok "-s $line"; then
            echo "-s $line"
            return
        fi
        tid="$("$ADB" devices -l | awk -v s="$line" '$1==s{for(i=1;i<=NF;i++) if($i~/^transport_id:/){sub("transport_id:","",$i);print $i;exit}}')"
        if [ -n "$tid" ] && "$ADB" -t "$tid" shell true >/dev/null 2>&1 && device_online_ok "-t $tid"; then
            echo "-t $tid"
            return
        fi
    done < <("$ADB" devices | awk '/device$/{print $1}')
    fail "没有联网的 adb 设备"
}

# 设备寻址说明：各命令内部调用 pick_device，返回 serial 或 "-t <tid>"（TCP serial 失效时回退）

case "$CMD" in
build)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon
    echo "✓ APK: app/build/outputs/apk/debug/app-debug.apk"
    ;;
release)
    "$GRADLE_CMD" :app:assembleRelease --no-daemon
    echo "✓ APK: app/build/outputs/apk/release/app-release-unsigned.apk"
    ;;
test)
    "$GRADLE_CMD" :app:testDebugUnitTest --no-daemon
    echo "✓ 单测通过"
    ;;
install)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${2:-}")
    "$ADB" $DEV_ADDR install -r app/build/outputs/apk/debug/app-debug.apk
    echo "✓ 已安装到 $DEV_ADDR"
    ;;
verify)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${2:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit
    echo "== 设备: $DEV_ADDR =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    $A logcat -c
    $A shell am start -W -n "$PKG/.MainActivity" \
        -d "file:///android_asset/test/testpage.html" --ez extract true >/dev/null
    sleep 6
    echo "-- Markdown --"
    $A shell am broadcast -a "${PKG}.FETCH_RESULT" \
        -n "$PKG/.ResultTunnelReceiver" 2>/dev/null | grep -oE 'result=[0-9-]+|data="[^"]{0,60}'
    echo "-- JSON --"
    $A shell am broadcast -a "${PKG}.FETCH_RESULT" --es format json \
        -n "$PKG/.ResultTunnelReceiver" 2>/dev/null | grep -oE 'result=[0-9-]+'
    echo "-- Prompt --"
    $A shell am start -a "${PKG}.CONTROL" --es op prompt >/dev/null 2>&1
    sleep 1
    $A shell "run-as $PKG wc -c files/control_result.txt" | awk '{print "prompt bytes:", $1}'
    $A shell pidof "$PKG" >/dev/null && echo "== 进程存活 ✓ ==" || fail "进程已退出"
    ;;
mcptest)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${2:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit
    HOST_PORT="${PAGEKIT_MCP_PORT:-19300}"
    MCP_URL="http://127.0.0.1:${HOST_PORT}/mcp"

    echo "== 设备: $DEV_ADDR | MCP: $MCP_URL =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    $A shell am start -W -n "$PKG/.MainActivity" \
        -d "file:///android_asset/test/testpage.html" >/dev/null
    sleep 3
    $A forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
    $A forward "tcp:${HOST_PORT}" tcp:3000 >/dev/null

    TOKEN=$($A shell "run-as $PKG cat files/mcp_token.txt" | tr -d '\r\n')
    [ "${#TOKEN}" -ge 32 ] || fail "未能读取应用私有 MCP token"

    UNAUTHORIZED=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        -X POST -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pagekit-smoke","version":"1"}}}' \
        "$MCP_URL")
    [ "$UNAUTHORIZED" = 401 ] || fail "无 token 请求应返回 401，实际 $UNAUTHORIZED"

    FORBIDDEN=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        -X POST -H "Authorization: Bearer $TOKEN" -H 'Origin: https://attacker.example' \
        -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pagekit-smoke","version":"1"}}}' \
        "$MCP_URL")
    [ "$FORBIDDEN" = 403 ] || fail "非回环 Origin 应返回 403，实际 $FORBIDDEN"

    INIT=$(curl --noproxy '*' -sS -i -X POST \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pagekit-smoke","version":"1"}}}' \
        "$MCP_URL" | tr -d '\r')
    SESSION=$(printf '%s\n' "$INIT" | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2; exit}')
    [ -n "$SESSION" ] || fail "initialize 未返回 mcp-session-id"
    printf '%s\n' "$INIT" | grep -q '"name":"pagekit"' || fail "initialize 响应缺少 PageKit serverInfo"

    curl --noproxy '*' -sS -o /dev/null -X POST \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SESSION" \
        -H 'MCP-Protocol-Version: 2025-06-18' -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$MCP_URL"
    TOOLS=$(curl --noproxy '*' -sS -X POST \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SESSION" \
        -H 'MCP-Protocol-Version: 2025-06-18' -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' "$MCP_URL")
    printf '%s\n' "$TOOLS" | grep -q '"name":"webfetch"' || fail "tools/list 缺少 webfetch"
    printf '%s\n' "$TOOLS" | grep -q '"name":"browser_snapshot"' || fail "tools/list 缺少 browser_snapshot"
    $A shell dumpsys activity services "$PKG/.mcp.McpServerService" | grep -q McpServerService \
        || fail "MCP 前台服务未运行"
    echo "== MCP auth + Origin + initialize + tools/list + service lifecycle ✓ =="
    ;;
providertest)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${2:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit
    HOST_PORT="${PAGEKIT_MCP_PORT:-19300}"
    SEARCH_URL="http://127.0.0.1:${HOST_PORT}/v1/search"

    echo "== 设备: $DEV_ADDR | WebSearchProvider: $SEARCH_URL =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    $A shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 3
    $A forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
    $A forward "tcp:${HOST_PORT}" tcp:3000 >/dev/null
    TOKEN=$($A shell "run-as $PKG cat files/mcp_token.txt" | tr -d '\r\n')
    [ "${#TOKEN}" -ge 32 ] || fail "未能读取应用私有 token"

    UNAUTHORIZED=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        -X POST -H 'Content-Type: application/json' \
        --data '{"text_query":"PageKit","limit":1}' "$SEARCH_URL")
    [ "$UNAUTHORIZED" = 401 ] || fail "无 token 请求应返回 401，实际 $UNAUTHORIZED"

    FORBIDDEN=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' \
        -X POST -H "Authorization: Bearer $TOKEN" -H 'Origin: https://attacker.example' \
        -H 'Content-Type: application/json' --data '{"text_query":"PageKit","limit":1}' \
        "$SEARCH_URL")
    [ "$FORBIDDEN" = 403 ] || fail "非回环 Origin 应返回 403，实际 $FORBIDDEN"

    RESPONSE=$(curl --noproxy '*' --max-time 60 -sS -X POST \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        --data '{"text_query":"PageKit Android","limit":3,"enable_page_crawling":false,"timeout_seconds":30}' \
        "$SEARCH_URL")
    printf '%s' "$RESPONSE" | python3 -c '
import json, sys
data = json.load(sys.stdin)
results = data["search_results"]
assert results, "search_results is empty"
required = {"site_name", "title", "url", "snippet", "content", "date", "icon", "mime"}
assert required <= set(results[0]), f"missing fields: {required - set(results[0])}"
assert results[0]["url"].startswith(("http://", "https://"))
print("first result:", results[0]["title"], results[0]["url"])
'
    echo "== Bearer auth + Origin + Kimi SearchWeb request/response contract ✓ =="
    ;;
llmtest)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${2:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit
    HOST_PORT="${PAGEKIT_MCP_PORT:-19300}"
    FAKE_PORT="${PAGEKIT_FAKE_LLM_PORT:-18080}"
    MCP_URL="http://127.0.0.1:${HOST_PORT}/mcp"
    LLM_CONFIG_EXISTED=-1

    python3 scripts/fake_openai_server.py "$FAKE_PORT" &
    FAKE_PID=$!
    cleanup_llmtest() {
        kill "$FAKE_PID" >/dev/null 2>&1 || true
        wait "$FAKE_PID" >/dev/null 2>&1 || true
        $A shell am force-stop "$PKG" >/dev/null 2>&1 || true
        case "$LLM_CONFIG_EXISTED" in
            1) $A shell "run-as $PKG cp cache/llmtest-pagekit-llm.xml shared_prefs/pagekit_llm.xml" >/dev/null 2>&1 || true ;;
            0) $A shell "run-as $PKG rm -f shared_prefs/pagekit_llm.xml" >/dev/null 2>&1 || true ;;
        esac
        if [ "$LLM_CONFIG_EXISTED" != -1 ]; then
            $A shell "run-as $PKG rm -f cache/llmtest-pagekit-llm.xml" >/dev/null 2>&1 || true
        fi
    }
    trap cleanup_llmtest EXIT
    sleep 1

    echo "== 设备: $DEV_ADDR | compact/focus/expand =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    if $A shell "run-as $PKG test -f shared_prefs/pagekit_llm.xml"; then
        $A shell "run-as $PKG cp shared_prefs/pagekit_llm.xml cache/llmtest-pagekit-llm.xml"
        LLM_CONFIG_EXISTED=1
    else
        LLM_CONFIG_EXISTED=0
    fi
    $A shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 3
    $A forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
    $A forward "tcp:${HOST_PORT}" tcp:3000 >/dev/null
    $A reverse --remove "tcp:${FAKE_PORT}" >/dev/null 2>&1 || true
    $A reverse "tcp:${FAKE_PORT}" "tcp:${FAKE_PORT}" >/dev/null
    TOKEN=$($A shell "run-as $PKG cat files/mcp_token.txt" | tr -d '\r\n')

    INIT=$(curl --noproxy '*' -sS -i -X POST \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"pagekit-llm-smoke","version":"1"}}}' \
        "$MCP_URL" | tr -d '\r')
    SESSION=$(printf '%s\n' "$INIT" | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2; exit}')
    [ -n "$SESSION" ] || fail "LLM smoke initialize 未返回 session"
    curl --noproxy '*' -sS -o /dev/null -X POST \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SESSION" \
        -H 'MCP-Protocol-Version: 2025-06-18' -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$MCP_URL"

    mcp_call() {
        local id="$1" name="$2" arguments="$3"
        curl --noproxy '*' -sS -X POST \
            -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: $SESSION" \
            -H 'MCP-Protocol-Version: 2025-06-18' -H 'Content-Type: application/json' \
            -H 'Accept: application/json, text/event-stream' \
            --data "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$arguments}}" \
            "$MCP_URL"
    }

    CONFIG=$(mcp_call 2 llm_configure \
        "{\"endpoint\":\"http://127.0.0.1:${FAKE_PORT}/v1\",\"model\":\"fixture\",\"api_key\":\"smoke-secret\"}")
    printf '%s\n' "$CONFIG" | grep -q '"configured":true' || fail "LLM 配置失败"

    COMPACT=$(mcp_call 3 webfetch \
        "{\"url\":\"http://127.0.0.1:${FAKE_PORT}/page\",\"mode\":\"compact\"}")
    printf '%s\n' "$COMPACT" | grep -q 'compact-ok' || fail "compact 未返回模型语义字段"
    printf '%s\n' "$COMPACT" | grep -q 'ORIGINAL_CODE' || fail "确定性代码字段未保真"
    if printf '%s\n' "$COMPACT" | grep -q 'MODEL_CHANGED_CODE'; then
        fail "模型改写的代码污染了结果"
    fi
    PAGE_ID=$(printf '%s\n' "$COMPACT" | python3 -c 'import json,sys; s=sys.stdin.read(); d=[x[6:] for x in s.splitlines() if x.startswith("data: ")]; o=json.loads(d[-1] if d else s); print(o["result"]["structuredContent"]["page"]["page_id"])')
    [ "${#PAGE_ID}" = 24 ] || fail "compact 未返回有效 page_id"

    FOCUS=$(mcp_call 4 webfetch \
        "{\"url\":\"http://127.0.0.1:${FAKE_PORT}/page\",\"mode\":\"focus\",\"intent\":\"只看安装\"}")
    if ! printf '%s\n' "$FOCUS" | grep -q 'focus-ok'; then
        printf '%s\n' "$FOCUS" | head -c 2000 >&2 || true
        fail "focus 模式/意图未传到模型"
    fi

    EXPANDED=$(mcp_call 5 expand "{\"page_id\":\"$PAGE_ID\",\"section\":\"Install\"}")
    if ! printf '%s\n' "$EXPANDED" | grep -q 'original installation detail'; then
        printf '%s\n' "$EXPANDED" | head -c 2000 >&2 || true
        fail "expand 未返回缓存中的原始章节"
    fi
    echo "== compact + focus + deterministic merge + page_id + expand ✓ =="
    ;;
opentest)
    # 端到端：搜索 → 点进第一条结果 → 提取详情页
    # 用法: ./build.sh opentest ["查询词"] ["https://www.bing.com"] [serial]
    QUERY="${2:-RTX5090}"
    ENGINE="${3:-https://www.bing.com}"
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${4:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit

    ctl() { timeout 20 $A shell am start -a "${PKG}.CONTROL" "$@" >/dev/null 2>&1 || true; sleep 1.5; }
    ctlfile() { timeout 15 $A shell "run-as $PKG cat files/control_result.txt" 2>/dev/null | tr -d '\r' || true; }

    echo "== 设备: $DEV_ADDR | 引擎: $ENGINE | 查询: $QUERY =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    # 直航结果页（复用 searchtest 已验证的稳定链路）
    case "$ENGINE" in
        *baidu*)  RESULT_URL="https://www.baidu.com/s?wd=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
        *bing*)   RESULT_URL="https://www.bing.com/search?q=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
        *sogou*)  RESULT_URL="https://www.sogou.com/web?query=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
        *so.com*|*360*) RESULT_URL="https://www.so.com/s?q=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
        *)        RESULT_URL="$ENGINE" ;;
    esac
    $A shell am start -W -n "$PKG/.MainActivity" -d "$RESULT_URL" >/dev/null
    sleep 10

    # 快照并选第一条「真实结果」链接：
    #   优先 label 含查询主词（广告卡片通常是「新款/十大/新品上市」类营销词，不含查询词）；
    #   排除 tab/筛选/广告类标签
    ctl --es op snapshot
    QTOKEN=$(echo "$QUERY" | awk '{print $1}')   # 查询主词（如 RTX5090）
    RESULT_EID=$(ctlfile | grep -E '\[e[0-9]+\] a ' \
        | grep -vE '综合|笔记|视频|图片|资讯|AI搜索|百度|下一页|更多|反馈|帮助|登录|设置|广告|推广|立即|下载|咨询|电话|Bing|Preferences|Homepage|Flights|Dict|Academic|Images|Videos|News|Maps|Shopping|Actions' \
        | grep "$QTOKEN" | head -1 | grep -oE 'e[0-9]+')
    if [ -z "$RESULT_EID" ]; then
        RESULT_EID=$(ctlfile | grep -E '\[e[0-9]+\] a ' \
            | grep -vE '综合|笔记|视频|图片|资讯|AI搜索|百度|下一页|更多|反馈|帮助|登录|设置|广告|推广|新款|优惠|领取|立即|下载|官网|旗舰店|品牌|十大|排行|新品|性价比|NO\.1|已拨打|咨询|Bing|Preferences|Homepage|Flights|Dict|Academic|Images|Videos|News|Maps|Shopping|Actions|Web page' \
            | head -1 | grep -oE 'e[0-9]+')
    fi
    echo "-- 结果链接: $RESULT_EID ($(ctlfile | grep "\[$RESULT_EID\]" | head -c 60)) --"
    [ -n "$RESULT_EID" ] || fail "结果页无可用链接（先跑 searchtest 排查）"

    ctl --es op click --es eid "$RESULT_EID"
    CLICKED=$(ctlfile)
    echo "-- click: $CLICKED"
    sleep 8
    ctl --es op title
    echo "-- 详情页标题: $(ctlfile)"

    ctl --es op extract
    OK=0
    for i in $(seq 1 15); do
        R=$(timeout 20 $A shell am broadcast -a "${PKG}.FETCH_RESULT" \
            -n "$PKG/.ResultTunnelReceiver" 2>/dev/null | grep -oE 'result=[0-9-]+' | head -1)
        case "$R" in result=-*) ;; result=*) OK=1; break ;; esac
        sleep 1
    done
    [ "$OK" = 1 ] || fail "详情页提取超时"
    echo "-- 详情页提取 --"
    timeout 15 $A shell "run-as $PKG cat files/last_result.txt" 2>/dev/null | tr -d '\r' | head -12
    $A shell pidof "$PKG" >/dev/null && echo "== opentest ✓ ==" || fail "进程已退出"
    ;;
searchtest)
    # 端到端：拼接结果页 URL 直航 → 提取（引擎 URL 模板即稳定契约，无需首页交互）
    # 用法: ./build.sh searchtest ["查询词"] ["https://www.bing.com"] [serial]
    QUERY="${2:-RTX5090 部署}"
    ENGINE="${3:-https://www.bing.com}"
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${4:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit

    fetchmd() {
        timeout 20 $A shell am broadcast -a "${PKG}.FETCH_RESULT" -n "$PKG/.ResultTunnelReceiver" 2>/dev/null \
            | grep -oE 'result=[0-9-]+|data=".*"' | head -40 || true
    }

    # 结果页 URL 模板
    QENC=$(echo "$QUERY" | sed 's/ /%20/g')
    case "$ENGINE" in
        *baidu*)        RESULT_URL="https://www.baidu.com/s?wd=$QENC" ;;
        *bing*)         RESULT_URL="https://www.bing.com/search?q=$QENC" ;;
        *sogou*)        RESULT_URL="https://www.sogou.com/web?query=$QENC" ;;
        *so.com*|*360*) RESULT_URL="https://www.so.com/s?q=$QENC" ;;
        *google*)       RESULT_URL="https://www.google.com/search?q=$QENC" ;;
        *)              fail "未知引擎: $ENGINE（支持 baidu/bing/sogou/360/google）" ;;
    esac

    echo "== 设备: $DEV_ADDR | 引擎: $ENGINE | 查询: $QUERY =="
    echo "== 结果页直航: $RESULT_URL =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    $A shell am start -W -n "$PKG/.MainActivity" -d "$RESULT_URL" >/dev/null
    sleep 10   # TLS/DNS 冷启动 + 结果页动态渲染

    # 提取并轮询结果
    timeout 20 $A shell am start -a "${PKG}.CONTROL" --es op extract >/dev/null 2>&1 || true
    OK=0
    for i in $(seq 1 15); do
        R=$(fetchmd | head -1)
        case "$R" in
            result=-*) ;;        # result=-1：尚未完成，继续轮询
            result=*) OK=1; break ;;
        esac
        sleep 1
    done
    [ "$OK" = 1 ] || fail "提取超时（15s）"
    echo "-- 提取结果（前 40 行） --"
    fetchmd
    $A shell pidof "$PKG" >/dev/null && echo "== searchtest ✓ ==" || fail "进程已退出"
    ;;
clean)
    "$GRADLE_CMD" clean --no-daemon
    ;;
*)
    fail "未知命令: $CMD（build|release|test|install|verify|mcptest|providertest|llmtest|searchtest|opentest|clean）"
    ;;
esac
