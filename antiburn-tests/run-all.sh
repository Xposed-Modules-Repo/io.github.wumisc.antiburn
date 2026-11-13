#!/bin/sh
# Antiburn 全量测试入口（纯 JVM，不需要真机 / 不需要 Android 运行时）
#
# 用法：
#   ./run-all.sh [工程根目录]
#
# 默认工程根为 /tmp/upstream/antiburn-lite。源码、编译产物、清单都从它派生，
# 避免「测试读 A 目录、构建写 B 目录」这种假绿（v2.5.2 就踩过一次：
# /tmp/v22 与交付目录不是同一份源码，测试全绿但测的不是要发的包）。
# 依赖：JDK 21、Gradle 已编译产物（app/build/intermediates/javac/debug/...）、android.jar
set -e

PROJ=${1:-/tmp/upstream/antiburn-lite}
SRC=$PROJ/app/src/main/java/io/github/wumisc/antiburn
CLS=$PROJ/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
AJAR=/opt/android-sdk/platforms/android-37.0/android.jar
JAVA_HOME=${JAVA_HOME:-/root/.sdkman/candidates/java/21.0.13.0+10-amzn}
JAVAC=$JAVA_HOME/bin/javac
JAVA=$JAVA_HOME/bin/java
OUT=/tmp/antiburn-tests

rm -rf "$OUT"
mkdir -p "$OUT"

echo "=============================================="
echo " 1/6 名单模式 + 界面结构（ListModeTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$AJAR" -d "$OUT/list" "$(dirname "$0")/list-mode-test/ListModeTest.java"
$JAVA -cp "$OUT/list:$CLS:$AJAR" ListModeTest "$SRC"

echo
echo "=============================================="
echo " 2/6 厂商适配（UiStyleTest）"
echo "=============================================="
$JAVAC -d "$OUT/ui" "$SRC/UiStyle.java" "$(dirname "$0")/ui-style-test/UiStyleTest.java"
$JAVA -cp "$OUT/ui" UiStyleTest "$SRC"

echo
echo "=============================================="
echo " 3/6 全功能矩阵审计（FeatureAuditTest）"
echo "=============================================="
$JAVAC -d "$OUT/audit" "$(dirname "$0")/feature-audit-test/FeatureAuditTest.java"
$JAVA -cp "$OUT/audit" FeatureAuditTest "$SRC"

echo
echo "=============================================="
echo " 4/6 UI 视觉审计（UiContrastTest）"
echo "=============================================="
$JAVAC -d "$OUT/contrast" "$(dirname "$0")/ui-contrast-test/UiContrastTest.java"
$JAVA -cp "$OUT/contrast" UiContrastTest "$SRC"

echo
echo "=============================================="
echo " 5/6 配置层行为（ConfStoreTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$AJAR" -d "$OUT/conf" "$(dirname "$0")/confstore-test/ConfStoreTest.java"
$JAVA -cp "$OUT/conf:$CLS:$AJAR" ConfStoreTest

echo
echo "=============================================="
echo " 6/6 双引擎：名单真源 / 引擎让位 / 遮盖几何 / 接线（EngineTest）"
echo "=============================================="
$JAVAC -cp "$CLS:$AJAR" -d "$OUT/engine" "$(dirname "$0")/engine-test/EngineTest.java"
$JAVA -cp "$OUT/engine:$CLS:$AJAR" EngineTest "$SRC"

echo
echo "全部套件通过。"
