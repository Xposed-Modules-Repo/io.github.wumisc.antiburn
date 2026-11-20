#!/usr/bin/env python3
"""配置键覆盖检查：defaultContent 里的每个 key 都必须能在 diffOf 里被写出去，
否则 UI 上那个开关「点了也没用」——这正是 enable_shift / enable_icon 之前的状态。"""
import re, sys

JAVA = "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn/ConfStore.java"
KT = "/tmp/v22/antiburn-lite/app/src/main/java/io/github/wumisc/antiburn/MainActivity.kt"

java = open(JAVA, encoding="utf-8").read()
kt = open(KT, encoding="utf-8").read()

m = re.search(r"public static String defaultContent\(\).*?return sb\.toString\(\);", java, re.S)
default_keys = re.findall(r'sb\.append\("([a-z_]+)=', m.group(0))

d = re.search(r"fun diffOf\(.*?\n    \}", kt, re.S)
diff_body = d.group(0)
written = set(re.findall(r'putB\("([a-z_]+)"', diff_body)) | set(re.findall(r'm\["([a-z_]+)"\]', diff_body))

# load / save 是否也认这些键（有 UI 状态字段才算真正闭环）
l = re.search(r"fun load\(\): AbState \{.*?\n    \}", kt, re.S).group(0)
loaded = set(re.findall(r'map\["([a-z_]+)"\]', l))

missing_write = [k for k in default_keys if k not in written]
missing_load = [k for k in default_keys if k not in loaded]

print("defaultContent 键数:", len(default_keys))
print("diffOf 可写键数:", len(written))
print("load 可读键数:", len(loaded))
if missing_write:
    print("FAIL 这些键改了也存不住:", missing_write)
if missing_load:
    print("FAIL 这些键界面读不出来:", missing_load)
if not missing_write and not missing_load:
    print("PASS 全部 key 读写闭环")
sys.exit(1 if (missing_write or missing_load) else 0)
