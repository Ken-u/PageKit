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

pick_device() {
    local serial="${1:-}"
    if [ -z "$serial" ]; then
        serial="$("$ADB" devices | awk '/device$/{print $1; exit}')" || true
    fi
    [ -n "$serial" ] || fail "未找到 adb 设备（adb devices 查看序列号）"
    echo "$serial"
}

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
    DEV=$(pick_device "${2:-}")
    "$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
    echo "✓ 已安装到 $DEV"
    ;;
verify)
    "$GRADLE_CMD" :app:assembleDebug --no-daemon >/dev/null
    DEV=$(pick_device "${2:-}")
    PKG=com.kenjc.pagekit
    echo "== 设备: $DEV =="
    "$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    "$ADB" -s "$DEV" shell am force-stop "$PKG"
    "$ADB" -s "$DEV" logcat -c
    "$ADB" -s "$DEV" shell am start -W -n "$PKG/.MainActivity" \
        -d "file:///android_asset/test/testpage.html" --ez extract true >/dev/null
    sleep 6
    echo "-- Markdown --"
    "$ADB" -s "$DEV" shell am broadcast -a "${PKG}.FETCH_RESULT" \
        -n "$PKG/.ResultTunnelReceiver" 2>/dev/null | grep -oE 'result=[0-9-]+|data="[^"]{0,60}'
    echo "-- JSON --"
    "$ADB" -s "$DEV" shell am broadcast -a "${PKG}.FETCH_RESULT" --es format json \
        -n "$PKG/.ResultTunnelReceiver" 2>/dev/null | grep -oE 'result=[0-9-]+'
    echo "-- Prompt --"
    "$ADB" -s "$DEV" shell am start -a "${PKG}.CONTROL" --es op prompt >/dev/null 2>&1
    sleep 1
    "$ADB" -s "$DEV" shell "run-as $PKG wc -c files/control_result.txt" | awk '{print "prompt bytes:", $1}'
    "$ADB" -s "$DEV" shell pidof "$PKG" >/dev/null && echo "== 进程存活 ✓ ==" || fail "进程已退出"
    ;;
clean)
    "$GRADLE_CMD" clean --no-daemon
    ;;
*)
    fail "未知命令: $CMD（build|release|test|install|verify|clean）"
    ;;
esac
