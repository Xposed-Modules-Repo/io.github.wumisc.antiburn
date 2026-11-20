#!/bin/sh
# v2.5 专项断言：真实 ConfStore.java + Context/Base64 桩件 + 假 su
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/root/.sdkman/candidates/java/21.0.13.0+10-amzn}
SRC=${SRC:-/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn/ConfStore.java}
rm -rf bin25 files25 && mkdir -p bin25 files25 /tmp/lt
: > /tmp/lt/su.log
chmod +x fakebin/su
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d bin25 \
    stubs/android/content/Context.java stubs/android/util/Base64.java "$SRC" SaveV25Test.java
PATH="$PWD/fakebin:$PATH" "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 \
    -Dconf.test.dir="$PWD/files25" -cp bin25 SaveV25Test
