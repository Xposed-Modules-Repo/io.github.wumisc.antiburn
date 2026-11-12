#!/bin/sh
# Antiburn 全量测试入口
#
# 纯 JVM：不需要真机，不需要 Android 运行时。测试要么直接加载 Gradle 编译产物
# 并调用真实方法（名单判定、配置解析），要么对源码做结构断言（功能接线、配色）。
#
# 用法：
#   cd antiburn-lite
#   gradle :app:compileDebugKotlin :app:compileDebugJavaWithJavac
#   cd ..
#   ./antiburn-tests/run-all.sh
#
# 可用环境变量覆盖：
#   JAVA_HOME      JDK 21 所在目录
#   ANDROID_HOME   Android SDK 根目录（用于定位 android.jar）
#   ANDROID_JAR    直接指定 android.jar 路径
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROJ=$ROOT/antiburn-lite
SRC=$PROJ/app/src/main/java/io/github/wumisc/antiburn
CLS=$PROJ/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes

if [ -z "$ANDROID_JAR" ]; then
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
    ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
fi

if [ -z "$JAVA_HOME" ]; then
    echo "错误：请先设置 JAVA_HOME 指向 JDK 21" >&2
    exit 1
fi
JAVAC=$JAVA_HOME/bin/javac
JAVA=$JAVA_HOME/bin/java

if [ ! -d "$CLS" ]; then
    echo "错误：找不到编译产物 $CLS" >&2
    echo "请先在 antiburn-lite 下执行：gradle :app:compileDebugKotlin :app:compileDebugJavaWithJavac" >&2
    exit 1
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "错误：找不到 android.jar，请设置 ANDROID_HOME 或 ANDROID_JAR" >&2
    exit 1
fi

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/list" "$OUT/ui" "$OUT/audit" "$OUT/contrast" "$OUT/conf" "$OUT/engine"

T=$ROOT/antiburn-tests

echo "=============================================="
echo " 1/6 名单模式 + 界面结构（ListModeTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$ANDROID_JAR" -d "$OUT/list" "$T/list-mode-test/ListModeTest.java"
$JAVA -cp "$OUT/list:$CLS:$ANDROID_JAR" ListModeTest "$SRC"

echo
echo "=============================================="
echo " 2/6 厂商适配（UiStyleTest）"
echo "=============================================="
$JAVAC -d "$OUT/ui" "$SRC/UiStyle.java" "$T/ui-style-test/UiStyleTest.java"
$JAVA -cp "$OUT/ui" UiStyleTest "$SRC"

echo
echo "=============================================="
echo " 3/6 全功能矩阵审计（FeatureAuditTest）"
echo "=============================================="
$JAVAC -d "$OUT/audit" "$T/feature-audit-test/FeatureAuditTest.java"
$JAVA -cp "$OUT/audit" FeatureAuditTest "$SRC"

echo
echo "=============================================="
echo " 4/6 UI 视觉审计（UiContrastTest）"
echo "=============================================="
$JAVAC -d "$OUT/contrast" "$T/ui-contrast-test/UiContrastTest.java"
$JAVA -cp "$OUT/contrast" UiContrastTest "$SRC"

echo
echo "=============================================="
echo " 5/6 配置层行为（ConfStoreTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$ANDROID_JAR" -d "$OUT/conf" "$T/confstore-test/ConfStoreTest.java"
$JAVA -cp "$OUT/conf:$CLS:$ANDROID_JAR" ConfStoreTest

echo
echo "=============================================="
echo " 6/6 双引擎：名单真源 / 引擎让位 / 遮盖几何 / 接线（EngineTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$ANDROID_JAR" -d "$OUT/engine" "$T/engine-test/EngineTest.java"
$JAVA -cp "$OUT/engine:$CLS:$ANDROID_JAR" EngineTest "$SRC"

echo
echo "全部套件通过。"
