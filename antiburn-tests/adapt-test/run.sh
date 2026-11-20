#!/bin/sh
# 一键复现：javac 编译 → A/B/C 组断言 → 导出适配包 → D 组 CI 校验
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -encoding UTF-8 -d out *.java
echo "===== A/B/C 组 ====="
java -Dfile.encoding=UTF-8 -cp out AdaptTest
echo
echo "===== 导出适配包 ====="
java -cp out DumpExport
echo
echo "===== D 组：profile CI 校验 ====="
python3 profiles/validate_profiles.py profiles --selftest
