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
opentest)
    # 端到端：搜索 → 点进第一条结果 → 提取详情页
    # 用法: ./build.sh opentest ["查询词"] ["https://www.baidu.com"] [serial]
    QUERY="${2:-RTX5090}"
    ENGINE="${3:-https://www.baidu.com}"
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
        | grep -vE '综合|笔记|视频|图片|资讯|AI搜索|百度|下一页|更多|反馈|帮助|登录|设置|广告|推广|立即|下载|咨询|电话' \
        | grep "$QTOKEN" | head -1 | grep -oE 'e[0-9]+')
    if [ -z "$RESULT_EID" ]; then
        RESULT_EID=$(ctlfile | grep -E '\[e[0-9]+\] a ' \
            | grep -vE '综合|笔记|视频|图片|资讯|AI搜索|百度|下一页|更多|反馈|帮助|登录|设置|广告|推广|新款|优惠|领取|立即|下载|官网|旗舰店|品牌|十大|排行|新品|性价比|NO\.1|已拨打|咨询' \
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
    # 端到端：搜索引擎 → 输入 → 点击搜索 → 结果页提取
    # 用法: ./build.sh searchtest ["查询词"] ["https://www.baidu.com"] [serial]
    QUERY="${2:-RTX5090 部署}"
    ENGINE="${3:-https://www.baidu.com}"
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV_ADDR=$(pick_device "${4:-}")
    A="$ADB $DEV_ADDR"
    PKG=com.kenjc.pagekit

    ctl() { # ctl <op> [extra args...] —— timeout 防 USB 抖动悬挂
        timeout 20 $A shell am start -a "${PKG}.CONTROL" "$@" >/dev/null 2>&1 || true
        sleep 1.5
    }
    ctlfile() { timeout 15 $A shell "run-as $PKG cat files/control_result.txt" 2>/dev/null | tr -d '\r' || true; }
    fetchmd() {
        timeout 20 $A shell am broadcast -a "${PKG}.FETCH_RESULT" -n "$PKG/.ResultTunnelReceiver" 2>/dev/null \
            | grep -oE 'result=[0-9-]+|data=".*"' | head -40 || true
    }

    echo "== 设备: $DEV_ADDR | 引擎: $ENGINE | 查询: $QUERY =="
    $A install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $A shell am force-stop "$PKG"
    $A shell am start -W -n "$PKG/.MainActivity" -d "$ENGINE" >/dev/null
    # 首屏可能因 TLS/DNS 冷启动较慢，最长等 25s（title 变成引擎页即成功）
    LOADED=0
    for i in $(seq 1 5); do
        sleep 5
        $A shell am start -a "${PKG}.CONTROL" --es op title >/dev/null 2>&1
        sleep 1.5
        T=$(ctlfile)
        case "$T" in
            *Webpage*|*\"ok\":false*|*引擎*URL*|*about:blank*) ;;
            *ok\":true*) echo "-- 引擎已加载: $T"; LOADED=1; break ;;
        esac
    done
    [ "$LOADED" = 1 ] || fail "引擎页加载失败: $T"

    # 1) 快照并定位搜索框/按钮
    ctl --es op snapshot
    INPUT_EID=$(ctlfile | grep -oE '\[e[0-9]+\] input:(text|search)' | head -1 | grep -oE 'e[0-9]+')
    BTN_EID=$(ctlfile | grep -oE '\[e[0-9]+\] (button|input:submit)[^ ]*' | head -1 | grep -oE 'e[0-9]+')
    echo "-- 元素: 搜索框=$INPUT_EID 按钮=$BTN_EID --"
    [ -n "$INPUT_EID" ] || fail "未找到搜索输入框（快照结果见上）"

    # 2) 输入查询词并触发搜索：
    #    优先浏览器控制链路（type+click，验证 [eN] 编号机制），
    #    同时构造搜索引擎结果页 URL 直接导航（稳定契约，规避站点 sug 清值竞态）
    ctl --es op type --es eid "$INPUT_EID" --es text "$QUERY"
    if [ -n "$BTN_EID" ]; then
        ctl --es op click --es eid "$BTN_EID"
    else
        ctl --es op click --es eid "$INPUT_EID"
    fi
    # 3) 轮询等待结果页（URL 含查询参数）；6s 未跳转则 URL 直航兜底
    NAVIGATED=0
    for i in $(seq 1 4); do
        sleep 2.5
        $A shell am start -a "${PKG}.CONTROL" --es op url >/dev/null 2>&1
        sleep 1.2
        U=$(ctlfile)
        case "$U" in
            *word=*|*wd=*|*query=*)
                echo "-- 结果页已就绪(浏览器控制): $(echo "$U" | grep -oE 'https?://[^"]{0,80}')"
                NAVIGATED=1; break ;;
        esac
    done
    if [ "$NAVIGATED" != 1 ]; then
        case "$ENGINE" in
            *baidu*)    RESULT_URL="https://www.baidu.com/s?wd=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
            *bing*)     RESULT_URL="https://www.bing.com/search?q=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
            *google*)   RESULT_URL="https://www.google.com/search?q=$(echo "$QUERY" | sed 's/ /%20/g')" ;;
            *)          RESULT_URL="" ;;
        esac
        if [ -n "$RESULT_URL" ]; then
            echo "-- URL 直航兜底: $RESULT_URL"
            ctl --es op navigate --es url "$RESULT_URL"
            sleep 8
            NAVIGATED=1
        fi
    fi
    [ "$NAVIGATED" = 1 ] || echo "-- 未确认跳转（url=$U），仍尝试提取 --"
    sleep 5   # 结果页动态渲染

    # 4) 提取并轮询结果
    $A shell am start -a "${PKG}.CONTROL" --es op extract >/dev/null 2>&1
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
    fail "未知命令: $CMD（build|release|test|install|verify|clean）"
    ;;
esac
