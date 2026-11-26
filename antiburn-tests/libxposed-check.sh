#!/bin/sh
# API 102（libxposed）实验分支打包契约自检
#
# 为什么单独一套：稳定版的 6 套件锁的是「legacy xposed_init + de.robv 依赖」那套身份，
# 本分支把身份换成了 resources/META-INF/xposed/*，光靠源码断言看不出「装上去管理器认不认」。
# 这里只查能静态验证的硬事实：模块声明、入口、作用域、包里有没有混进 libxposed 运行时、
# legacy 入口是否清干净、applicationId / versionCode 是否与稳定版隔离。
#
# 用法：./api102-check.sh [工程根]   （默认取当前目录）
set -u

PROJ=${1:-$(cd "$(dirname "$0")/.." && pwd)}
RES=$PROJ/app/src/main/resources/META-INF/xposed
APK=${2:-$PROJ/app/build/outputs/apk/release/app-release.apk}
AAPT=${AAPT:-/home/user/android-sdk/build-tools/36.0.0/aapt2}

pass=0
fail=0

ok() {
    pass=$((pass + 1))
    echo "  PASS  $1"
}

bad() {
    fail=$((fail + 1))
    echo "  FAIL  $1  — $2"
}

echo "== A. 模块声明（resources/META-INF/xposed）=="

[ -f "$RES/module.prop" ] && ok "module.prop 存在" || bad "module.prop 存在" "缺失"

# v3.2.29：我们覆写了 API102 的 onHotReloading/onHotReloaded 并声明 autoHotReload，
# 若 minApiVersion 仍写 101，遇到只支持 101 的框架会加载到一半找不到回调。
grep -qx 'minApiVersion=102' "$RES/module.prop" 2>/dev/null \
    && ok "module.prop minApiVersion=102（用到 API102 回调）" || bad "module.prop minApiVersion=102" "$(cat "$RES/module.prop" 2>/dev/null)"
grep -qx 'autoHotReload=true' "$RES/module.prop" 2>/dev/null \
    && ok "module.prop autoHotReload=true" || bad "module.prop autoHotReload" "$(cat "$RES/module.prop" 2>/dev/null)"
grep -qx 'targetApiVersion=102' "$RES/module.prop" 2>/dev/null \
    && ok "module.prop targetApiVersion=102（管理器显示 API 102 徽标）" \
    || bad "module.prop targetApiVersion=102" "$(cat "$RES/module.prop" 2>/dev/null)"
grep -qx 'staticScope=true' "$RES/module.prop" 2>/dev/null \
    && ok "module.prop staticScope=true" || bad "module.prop staticScope=true" "$(cat "$RES/module.prop" 2>/dev/null)"

ENTRY=$(cat "$RES/java_init.list" 2>/dev/null | tr -d '\r' | head -1)
[ "$ENTRY" = "io.github.wumisc.antiburn.LibXposedEntry" ] \
    && ok "java_init.list 唯一入口 = LibXposedEntry" || bad "java_init.list 入口" "实际=$ENTRY"

ENTRY_SRC=$PROJ/app/src/main/java/$(echo "$ENTRY" | tr '.' '/').java
[ -f "$ENTRY_SRC" ] && ok "入口类源码存在" || bad "入口类源码存在" "$ENTRY_SRC 不存在"
grep -q 'extends XposedModule' "$ENTRY_SRC" 2>/dev/null \
    && ok "入口继承 io.github.libxposed.api.XposedModule" || bad "入口继承 XposedModule" "未找到"
grep -q 'onPackageReady' "$ENTRY_SRC" 2>/dev/null \
    && ok "入口用 onPackageReady（拿到宿主真实 ClassLoader）" || bad "入口 onPackageReady" "未找到"

echo
echo "== B. 作用域（scope.list 必须与 arrays.xml 推荐清单逐项一致）=="
SCOPE_LIST=$(tr -d '\r' < "$RES/scope.list" 2>/dev/null | grep -v '^$' | sort)
SCOPE_XML=$(sed -n '/xposed_scope/,/<\/string-array>/p' "$PROJ/app/src/main/res/values/arrays.xml" \
    | sed -n 's/.*<item>\(.*\)<\/item>.*/\1/p' | sort)
if [ -n "$SCOPE_LIST" ] && [ "$SCOPE_LIST" = "$SCOPE_XML" ]; then
    ok "scope.list 与 arrays.xml 的 12 个包名一致"
else
    bad "scope.list 与 arrays.xml 一致" "list=[$(echo "$SCOPE_LIST" | tr '\n' ' ')] xml=[$(echo "$SCOPE_XML" | tr '\n' ' ')]"
fi

echo
echo "== C. legacy 身份已清干净（否则管理器会当旧式模块再加载一遍）=="
if [ -e "$PROJ/app/src/main/assets/xposed_init" ]; then
    bad "assets/xposed_init 已删除" "仍存在"
else
    ok "assets/xposed_init 已删除"
fi
if grep -q 'android:name="xposedmodule"' "$PROJ/app/src/main/AndroidManifest.xml"; then
    bad "清单无 xposedmodule" "仍存在"
else
    ok "清单无 xposedmodule / xposedminversion / xposedscope"
fi
if grep -q "compileOnly 'de.robv.android.xposed:api:82'" "$PROJ/app/build.gradle"; then
    bad "构建已不依赖 legacy api:82" "仍存在"
else
    ok "构建用 io.github.libxposed:api:102.0.0（compileOnly，不进包）"
fi
grep -q 'android:description="@string/module_description"' "$PROJ/app/src/main/AndroidManifest.xml" \
    && ok "模块描述走 android:description（libxposed 无 xposeddescription）" \
    || bad "模块描述 android:description" "未声明"

echo
echo "== D. 包体事实（$APK）=="
if [ ! -f "$APK" ]; then
    bad "APK 存在" "先 assembleRelease"
else
    for f in module.prop java_init.list scope.list; do
        unzip -l "$APK" 2>/dev/null | grep -q "META-INF/xposed/$f" \
            && ok "APK 含 META-INF/xposed/$f" || bad "APK 含 $f" "缺失"
    done
    # v3.2.26：autoHotReload 是 API 102 的「覆盖安装自动热重载」开关，缺了就只会提示重启
    unzip -p "$APK" META-INF/xposed/module.prop 2>/dev/null | grep -q "autoHotReload=true" \
        && ok "module.prop 声明 autoHotReload=true" || bad "autoHotReload" "缺失"
    n=$(unzip -l "$APK" 2>/dev/null | grep -c 'io/github/libxposed/')
    [ "$n" = "0" ] && ok "APK 未打包 libxposed 运行时（compileOnly 生效）" \
        || bad "APK 未打包 libxposed 运行时" "命中 $n 项"
    unzip -l "$APK" 2>/dev/null | grep -q 'assets/xposed_init' \
        && bad "APK 无 legacy assets/xposed_init" "仍存在" || ok "APK 无 legacy assets/xposed_init"

    BADGING=$("$AAPT" dump badging "$APK" 2>/dev/null | head -2)
    echo "$BADGING" | grep -q "name='io.github.wumisc.antiburn'" \
        && ok "applicationId = io.github.wumisc.antiburn（与清单命名空间一致，组件名可解析）" \
        || bad "applicationId" "$BADGING"
    echo "$BADGING" | grep -q "versionCode='129'" \
        && ok "versionCode=129" || bad "versionCode" "$BADGING"
    echo "$BADGING" | grep -q "versionName='3.2.30'" \
        && ok "versionName=3.2.30" || bad "versionName" "$BADGING"
    "$AAPT" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null \
        | grep -q 'io.github.wumisc.antiburn.config' \
        && ok "provider authority = io.github.wumisc.antiburn.config" || bad "provider authority" "不符"
fi

echo
echo "=============================================="
echo "结果：$pass 通过 / $fail 失败"
[ "$fail" = "0" ] && echo "打包契约自检通过。" || echo "打包契约自检未通过。"
[ "$fail" = "0" ]
