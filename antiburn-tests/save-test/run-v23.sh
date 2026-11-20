#!/bin/sh
# v2.3 保存链路验证：真实 ConfStore.java + Context/Base64 桩件 + 假 su
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/root/.sdkman/candidates/java/21.0.13.0+10-amzn}
SRC=${SRC:-/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn/ConfStore.java}
rm -rf bin23 files23 && mkdir -p bin23 files23
chmod +x fakebin/su
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d bin23 \
    stubs/android/content/Context.java stubs/android/util/Base64.java "$SRC" SaveV23Test.java
PATH="$PWD/fakebin:$PATH" "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 \
    -Dconf.test.dir="$PWD/files23" -cp bin23 SaveV23Test
