#!/usr/bin/env bash
# PageKit 部署脚本 —— 无需源码构建环境，只要 adb 就能用
#
# 适合直接用 release APK 的用户：
#   ./deploy.sh setup                 # 一键：下载 APK → 安装 → 启动 → 打印连接信息
#   ./deploy.sh install [apk路径]     # 安装 APK（缺省自动从 GitHub latest release 下载）
#   ./deploy.sh run                   # 启动 App → 打印连接信息（MCP/HTTP 地址 + token）
#   ./deploy.sh token                 # 打印设备上的 MCP token
#   ./deploy.sh set-token <token>     # 设置 token（换机/重装后恢复，客户端配置不用改）
#   ./deploy.sh load                  # 从 .env 读配置写入设备（token/代理/屏保/并发）
#   ./deploy.sh forward               # adb 端口转发（3000/3001 → 本机）
#
# 设备选择：默认取第一台，多台时用 PAGEKIT_DEVICE=<serial> 指定
# APK 来源：环境变量 PAGEKIT_APK 指定本地路径；缺省自动下载 GitHub latest release
set -euo pipefail

REPO="Ken-u/PageKit"
PKG="com.kenjc.pagekit"

fail() { echo "✗ $1" >&2; exit 1; }

# ---- adb ----
if command -v adb >/dev/null 2>&1; then
    ADB="$(command -v adb)"
elif [ -x "$HOME/.sdk/android-sdk/platform-tools/adb" ]; then
    ADB="$HOME/.sdk/android-sdk/platform-tools/adb"
elif [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
else
    fail "未找到 adb。安装 platform-tools 后重试：
  https://developer.android.com/tools/releases/platform-tools"
fi

# ---- 设备选择 ----
pick_device() {
    local serial="${PAGEKIT_DEVICE:-}"
    if [ -n "$serial" ] && "$ADB" -s "$serial" shell true >/dev/null 2>&1; then
        echo "-s $serial"; return
    fi
    if [ -n "$serial" ]; then echo "（提示：$serial 不可用，改用第一台在线设备）" >&2; fi
    local line
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        if "$ADB" -s "$line" shell true >/dev/null 2>&1; then
            echo "-s $line"; return
        fi
    done < <("$ADB" devices | awk '/device$/{print $1}')
    fail "没有已连接的 adb 设备（确认 USB 调试/无线调试已开启并授权）"
}

# ---- token 读取（广播触发 → logcat，release 包同样可用） ----
print_device_field() { # print_device_field <dev> <regex>  如 'token=[A-Za-z0-9_-]+' / 'pin=[0-9]+'
    local dev="$1" regex="$2" out="" i
    "$ADB" $dev logcat -c 2>/dev/null
    "$ADB" $dev shell "am broadcast -a ${PKG}.MCP_TOKEN -n ${PKG}/.mcp.McpTokenReceiver" >/dev/null 2>&1
    # 轮询 logcat，最多 5s（广播处理是异步的，固定 sleep 在慢设备上会漏）
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.5
        out=$("$ADB" $dev logcat -d -s PageKit.McpToken:I 2>/dev/null \
            | grep -oE "$regex" | tail -1 | cut -d= -f2)
        if [ -n "$out" ]; then echo "$out"; return 0; fi
    done
    return 1
}

print_device_token() { print_device_field "$1" 'token=[A-Za-z0-9_-]+'; }
print_device_pin()    { print_device_field "$1" 'pin=[0-9]+'; }

set_device_token() { # set_device_token <dev> <token>
    local dev="$1" token="$2" pin
    pin=$(print_device_pin "$dev")
    if [ -z "$pin" ]; then return 1; fi
    "$ADB" $dev logcat -c 2>/dev/null
    "$ADB" $dev shell "am broadcast -a ${PKG}.MCP_TOKEN -n ${PKG}/.mcp.McpTokenReceiver --es pin $pin --es set '$token'" >/dev/null 2>&1
    # 写入后重新广播读取，确认设备上的值已生效
    sleep 1
    local got i
    for i in 1 2 3; do
        got=$(print_device_token "$dev")
        if [ "$got" = "$token" ]; then return 0; fi
        sleep 1
    done
    return 1
}

# ---- APK 下载（GitHub latest release） ----
download_apk() { # download_apk <输出路径>
    local out="$1"
    command -v curl >/dev/null 2>&1 || fail "未找到 curl（下载 release APK 需要）"
    echo "→ 从 GitHub latest release 下载 APK..."
    local url
    url=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep -oE '"browser_download_url": *"[^"]+\.apk"' | head -1 | grep -oE 'https://[^"]+')
    [ -n "$url" ] || fail "latest release 未找到 APK 资产（检查 $REPO releases）"
    curl -fL --retry 3 -o "$out" "$url"
    [ -s "$out" ] || fail "下载失败：$url"
    echo "✓ 已下载: $out ($(du -h "$out" | cut -f1))"
}

device_ip() { # device_ip <dev>  dev 形如 "-s <serial>"
    "$ADB" $1 shell "ip -4 addr" 2>/dev/null \
        | sed -n 's/.*inet \([0-9.]*\)\/.*scope global.*/\1/p' | head -1 | tr -d '\r\n'
}

print_connection() { # print_connection <dev>
    local dev="$1" token ip
    token=$(print_device_token "$dev")
    [ -n "$token" ] || fail "token 读取失败：确认 App 已启动"
    ip=$(device_ip "$dev")
    echo ""
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║              PageKit 已就绪 ✓                        ║"
    echo "╠══════════════════════════════════════════════════════╣"
    if [ -n "$ip" ]; then
        echo "║  设备 IP:        $ip"
        echo "║  MCP 使用端:     http://${ip}:3000/mcp"
        echo "║  MCP 管理端:     http://${ip}:3001/mcp"
        echo "║  WebSearch:      http://${ip}:3000/v1/search"
        echo "║  WebFetch:       http://${ip}:3000/v1/fetch"
        echo "║  Token:          $token"
    else
        echo "║  （未取到设备 IP，可用 ./deploy.sh forward 走 adb 转发）"
        echo "║  Token:          $token"
    fi
    echo "╚══════════════════════════════════════════════════════╝"
    if [ -n "$ip" ]; then
        echo ""
        echo "── Claude Code 接入 ──"
        echo "claude mcp add --transport http pagekit http://${ip}:3000/mcp \\"
        echo "  --header \"Authorization: Bearer ${token}\""
        echo ""
        echo "── HTTP API（Kimi SearchWeb 兼容） ──"
        echo "curl http://${ip}:3000/v1/search -H \"Authorization: Bearer ${token}\" \\"
        echo "  -H 'Content-Type: application/json' -d '{\"text_query\":\"test\"}'"
    fi
}

CMD="${1:-help}"
case "$CMD" in
setup)
    # 一键：下载/定位 APK → 安装 → 启动 → 打印连接信息
    DEV_ADDR=$(pick_device)
    APK="${PAGEKIT_APK:-}"
    if [ -z "$APK" ] || [ ! -f "$APK" ]; then
        APK="$(mktemp -d)/pagekit.apk"
        download_apk "$APK"
    fi
    echo "== 设备: $DEV_ADDR | APK: $APK =="
    "$ADB" $DEV_ADDR install -r "$APK"
    "$ADB" $DEV_ADDR shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 3
    print_connection "$DEV_ADDR"
    ;;
install)
    APK="${2:-${PAGEKIT_APK:-}}"
    if [ -z "$APK" ] || [ ! -f "$APK" ]; then
        APK="$(mktemp -d)/pagekit.apk"
        download_apk "$APK"
    fi
    DEV_ADDR=$(pick_device)
    echo "== 设备: $DEV_ADDR | APK: $APK =="
    "$ADB" $DEV_ADDR install -r "$APK"
    echo "✓ 已安装（./deploy.sh run 启动并查看连接信息）"
    ;;
run)
    DEV_ADDR=$(pick_device "${2:-}")
    "$ADB" $DEV_ADDR shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 2
    print_connection "$DEV_ADDR"
    ;;
token)
    DEV_ADDR=$(pick_device "${2:-}")
    TOKEN=$(print_device_token "$DEV_ADDR")
    [ -n "$TOKEN" ] || fail "读取失败：确认 App 已启动、设备已授权 adb"
    IP=$(device_ip "$DEV_ADDR")
    echo "Token:  $TOKEN"
    if [ -n "$IP" ]; then echo "MCP:    http://${IP}:3000/mcp"; fi
    ;;
set-token)
    SET_VALUE="${2:-}"
    [ -n "$SET_VALUE" ] || fail "用法: ./deploy.sh set-token <token> [serial]"
    DEV_ADDR=$(pick_device "${3:-}")
    if set_device_token "$DEV_ADDR" "$SET_VALUE"; then
        echo "✓ Token 已设置并验证：$SET_VALUE"
    else
        fail "设置失败：确认 App 已启动、token 格式正确（32-128 位 A-Za-z0-9_-）"
    fi
    ;;
load)
    ENV_FILE="${PAGEKIT_ENV:-.env}"
    [ -f "$ENV_FILE" ] || fail "未找到 $ENV_FILE（没有模板时手动创建，字段见 README）"
    DEV_ADDR=$(pick_device "${2:-}")
    pin=$(print_device_pin "$DEV_ADDR")
    [ -n "$pin" ] || fail "读取 PIN 失败：确认 App 已启动"

    ARGS=(-a "${PKG}.MCP_TOKEN" -n "${PKG}/.mcp.McpTokenReceiver" --es pin "$pin")
    DESC=()

    env_value() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1 | sed 's/^["'\'']//;s/["'\'']$//'; }

    TOKEN_V=$(env_value PAGEKIT_TOKEN)
    if [ -n "$TOKEN_V" ]; then ARGS+=(--es set "$TOKEN_V"); DESC+=("token"); fi

    PROXY_HOST_V=$(env_value PAGEKIT_PROXY_HOST)
    PROXY_PORT_V=$(env_value PAGEKIT_PROXY_PORT)
    if [ -n "$PROXY_HOST_V" ] && [ -n "$PROXY_PORT_V" ]; then
        ARGS+=(--es proxy_host "$PROXY_HOST_V" --ei proxy_port "$PROXY_PORT_V")
        if [ -n "$(env_value PAGEKIT_PROXY_BYPASS)" ]; then ARGS+=(--es proxy_bypass "$(env_value PAGEKIT_PROXY_BYPASS)"); fi
        case "$(env_value PAGEKIT_PROXY_ENABLED)" in
            true|1|yes|y|Y|TRUE) ARGS+=(--ez proxy_enabled true);;
            *) ARGS+=(--ez proxy_enabled false);;
        esac
        DESC+=("proxy")
    fi

    SS_V=$(env_value PAGEKIT_SCREENSAVER_TIMEOUT_MS)
    if [ -n "$SS_V" ]; then ARGS+=(--el screensaver_timeout_ms "$SS_V"); DESC+=("screensaver=${SS_V}ms"); fi

    MS_V=$(env_value PAGEKIT_MAX_SESSIONS)
    if [ -n "$MS_V" ]; then ARGS+=(--ei max_sessions "$MS_V"); DESC+=("max_sessions=$MS_V"); fi

    [ ${#DESC[@]} -gt 0 ] || fail ".env 里没有可配置项"

    "$ADB" $DEV_ADDR logcat -c 2>/dev/null
    "$ADB" $DEV_ADDR shell am broadcast "${ARGS[@]}" >/dev/null 2>&1
    RESULT=""
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.5
        RESULT=$("$ADB" $DEV_ADDR logcat -d -s PageKit.McpToken:I 2>/dev/null | grep 'config applied' | tail -1)
        if [ -n "$RESULT" ]; then break; fi
    done
    echo "已写入: ${DESC[*]}"
    echo "$RESULT"
    echo "$RESULT" | grep -q "config applied" || fail "写入未确认：检查 PIN/App 状态"
    ;;
forward)
    DEV_ADDR=$(pick_device "${2:-}")
    "$ADB" $DEV_ADDR forward tcp:3000 tcp:3000 >/dev/null
    "$ADB" $DEV_ADDR forward tcp:3001 tcp:3001 >/dev/null
    echo "✓ localhost:3000 → 设备 3000（使用端 /mcp /v1/search /v1/fetch）"
    echo "✓ localhost:3001 → 设备 3001（管理端 /mcp）"
    echo "  MCP 地址: http://localhost:3000/mcp"
    ;;
help|*)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
