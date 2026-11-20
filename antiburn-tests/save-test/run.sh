#!/bin/sh
# 保存链路验证：真实 ConfStore.java + Context/Base64 桩件 + 假 su
# 断言 7 项：点击即落盘、重开能读回、连续切换不丢、写失败不谎报、被覆盖能发现、缺键判失败、日志路径引号正确
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/root/.sdkman/candidates/java/21.0.13.0+10-amzn}
SRC=${SRC:-/root/.codebuddy/artifact/work/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn/ConfStore.java}
rm -rf bin files && mkdir -p bin files
chmod +x fakebin/su
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d bin \
    stubs/android/content/Context.java stubs/android/util/Base64.java "$SRC" SaveV22Test.java
PATH="$PWD/fakebin:$PATH" "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 \
    -Dconf.test.dir="$PWD/files" -cp bin SaveV22Test
