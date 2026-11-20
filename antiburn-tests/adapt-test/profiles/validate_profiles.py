#!/usr/bin/env python3
"""
profile CI 校验（计划 §8.3 / §8.4 落地）。

五项检查，任一失败即拒绝合并：
  1. JSON Schema
  2. 命名规范（文件名 = id，禁 final/v2/copy/最终 之类后缀）
  3. 危险字段扫描（递归找可执行语义的 key，§8.4 的安全边界）
  4. fixture 存在（没有 dump 的 profile 不许合并）
  5. dump 回放：锚点命中、锚点不是叶子、guard 命中所有疑似胶囊节点

用法：
  python3 validate_profiles.py <profiles_dir>          # 校验
  python3 validate_profiles.py --selftest <profiles_dir>  # 校验 + 反例自测
"""
import json
import os
import re
import sys
import glob
import fnmatch

# ---------------------------------------------------------------- 常量
DANGEROUS_KEYS = {
    "script", "exec", "execute", "shell", "sh", "cmd", "command", "eval",
    "url", "uri", "code", "import", "class", "function", "func", "python",
    "js", "javascript", "download", "fetch", "hook",
}
# class 是 Java 关键词但这里 profile 里确实要写 classes（复数），单数 class 视为危险
BAD_NAME_TOKENS = {"final", "v2", "v3", "copy", "test", "tmp", "backup", "bak", "new", "old", "副本", "最终"}
CAPSULE_HINTS = ("Capsule", "Island", "FluidCloud", "Atomic", "LiveUpdate", "LiveAlert", "MagicCapsule", "Ongoing")


def load_schema(profiles_dir):
    p = os.path.join(profiles_dir, "schema", "profile.schema.json")
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def scan_dangerous(node, path="", hits=None):
    """递归扫描危险 key。"""
    if hits is None:
        hits = []
    if isinstance(node, dict):
        for k, v in node.items():
            if k.lower() in DANGEROUS_KEYS:
                hits.append(f"{path}.{k}")
            scan_dangerous(v, f"{path}.{k}", hits)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            scan_dangerous(v, f"{path}[{i}]", hits)
    return hits


def walk(node):
    yield node
    for c in node.get("children") or []:
        yield from walk(c)


def find_by_id(node, target):
    """先序深度优先，模拟 findViewById。"""
    if node.get("id") == target:
        return node
    for c in node.get("children") or []:
        r = find_by_id(c, target)
        if r is not None:
            return r
    return None


def glob_match(cls, pattern):
    return fnmatch.fnmatchcase(cls, pattern)


def check_profile(path, schema, fixtures_dir, use_validator=True):
    """返回 (ok, list_of_issues, info)"""
    issues = []
    info = {}
    fname = os.path.basename(path)
    stem = fname[:-5]

    with open(path, encoding="utf-8") as f:
        raw = f.read()
    try:
        prof = json.loads(raw)
    except Exception as e:
        return False, [f"JSON 解析失败: {e}"], info

    # 1. schema
    if use_validator:
        import jsonschema
        try:
            jsonschema.validate(prof, schema)
        except jsonschema.ValidationError as e:
            issues.append(f"schema: {e.message} (at {list(e.absolute_path)})")

    # 2. 命名规范
    if prof.get("id") != stem:
        issues.append(f"命名: 文件名 {fname} 与 id {prof.get('id')} 不一致")
    tokens = set(re.split(r"[-_.]", stem.lower()))
    bad = tokens & BAD_NAME_TOKENS
    if bad:
        issues.append(f"命名: 含禁用词 {sorted(bad)}")

    # 3. 危险字段
    hits = scan_dangerous(prof)
    if hits:
        issues.append(f"安全: 存在可执行语义字段 {hits}")

    # 4. fixture
    fx = os.path.join(fixtures_dir, stem + ".json")
    if not os.path.exists(fx):
        issues.append(f"fixture: 缺少 {stem}.json（无 dump 不许合并）")
        return False, issues, info

    with open(fx, encoding="utf-8") as f:
        dump = json.load(f)
    bar = dump.get("statusBar") or {}

    # 5. 回放：锚点（schema 已失败时可能没有 anchors，直接返回）
    if "anchors" not in (prof.get("statusBar") or {}):
        issues.append("回放: 缺少 statusBar.anchors，跳过回放")
        return False, issues, info
    anchor = None
    hit_at = -1
    for i, a in enumerate(prof["statusBar"]["anchors"]):
        v = find_by_id(bar, a)
        if v is not None:
            anchor, hit_at = v, i
            break
    if anchor is None:
        issues.append("回放: 候选锚点全部未命中")
        return False, issues, info
    info["anchor"] = anchor.get("id")
    info["anchor_candidate_index"] = hit_at

    if not (anchor.get("children") or []):
        issues.append(f"回放: 锚点 {anchor.get('id')} 是叶子节点，隐藏它等于只灭一个图标")
    if anchor.get("id") == bar.get("id") and anchor.get("class") == bar.get("class"):
        info["warn"] = "锚点等于状态栏窗口根，隐藏范围过大"

    # 5. 回放：guard
    guards = [n for n in walk(anchor)
              for g in (prof["statusBar"].get("guard") or {}).get("classes", [])
              if glob_match(n.get("class", ""), g)]
    guard_classes_hit = {n["class"] for n in guards}
    info["guard_hits"] = sorted(guard_classes_hit)

    capsules = [n for n in walk(anchor) if any(h in n.get("class", "") for h in CAPSULE_HINTS)]
    info["capsule_like"] = sorted({n["class"].split(".")[-1] for n in capsules})
    missed = [n["class"].split(".")[-1] for n in capsules if n not in guards]
    if missed:
        issues.append(f"guard: 疑似胶囊未被保护区覆盖 {sorted(set(missed))}")

    return len(issues) == 0, issues, info


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    selftest = "--selftest" in sys.argv
    profiles_dir = args[0] if args else "profiles"
    fixtures_dir = os.path.join(profiles_dir, "_fixtures")
    schema = load_schema(profiles_dir)

    paths = sorted(glob.glob(os.path.join(profiles_dir, "*", "*.json")))
    paths = [p for p in paths
             if os.path.basename(os.path.dirname(p)) not in ("schema", "_fixtures")]
    print(f"扫描 {len(paths)} 个 profile\n")

    all_ok = True
    for p in paths:
        ok, issues, info = check_profile(p, schema, fixtures_dir)
        name = os.path.basename(p)
        print(("  [OK]   " if ok else "  [FAIL] ") + name)
        if info.get("anchor"):
            print(f"         锚点={info['anchor']} (候选#{info['anchor_candidate_index']}) "
                  f"guard命中={info.get('guard_hits')} 胶囊={info.get('capsule_like')}")
        if info.get("warn"):
            print(f"         warn: {info['warn']}")
        for i in issues:
            print(f"         - {i}")
        all_ok = all_ok and ok

    if selftest:
        all_ok = selftest_cases(schema, fixtures_dir) and all_ok

    print("\n结果:", "全部通过" if all_ok else "存在不通过项")
    return 0 if all_ok else 1


def selftest_cases(schema, fixtures_dir):
    """反例自测：每个坏样本都必须被拒绝，好样本必须通过。"""
    import tempfile
    base = {
        "id": "demo-x", "family": "aosp", "displayName": "Demo 样本",
        "match": {"ids": ["status_bar_contents"]},
        "statusBar": {"anchors": ["status_bar_contents"], "hide": "alpha",
                      "guard": {"classes": ["*Capsule*"]}},
        "nav": {"anchors": ["navigation_bar"], "onlyInMode": ["three_button"]},
        "fallback": "noop", "confidence": "unverified"
    }

    def mutate(**kw):
        import copy
        d = copy.deepcopy(base)
        for k, v in kw.items():
            d[k] = v
        return d

    cases = []

    # D-02 缺 anchors
    bad = mutate()
    bad["statusBar"] = {"hide": "alpha", "guard": {"classes": ["*Capsule*"]}}
    cases.append(("D-02 缺 anchors", bad, "demo-x", False))

    # D-03 非法 hide
    bad = mutate()
    bad["statusBar"] = {"anchors": ["status_bar"], "hide": "display_none"}
    cases.append(("D-03 非法 hide 值", bad, "demo-x", False))

    # D-04 可执行字段
    bad = mutate()
    bad["statusBar"]["script"] = "rm -rf /"
    cases.append(("D-04 含 script 字段", bad, "demo-x", False))

    # D-05 命名违规
    good = mutate()
    cases.append(("D-05 命名 xxx-final-v2", good, "xxx-final-v2", False))

    # D-06 无 fixture
    good = mutate()
    cases.append(("D-06 无 dump（自定义 id）", good, "no-dump-profile", False))

    # D-07 合法样本（用 aosp dump）
    good = mutate()
    good["id"] = "generic-aosp"
    cases.append(("D-07 合法样本回放通过", good, "generic-aosp", True))

    # D-08 跨家族套用：OriginOS 的原子岛不在 *Capsule* 保护范围内
    bad = mutate()
    bad["id"] = "originos-4-7"
    bad["statusBar"] = {"anchors": ["status_bar_contents"], "hide": "alpha",
                        "guard": {"classes": ["*Capsule*"]}}
    cases.append(("D-08 跨家族套用 → guard 漏掉原子岛", bad, "originos-4-7", False))

    print("\n---- 反例自测 ----")
    ok_all = True
    with tempfile.TemporaryDirectory() as td:
        for label, prof, stem, expect_ok in cases:
            f = os.path.join(td, stem + ".json")
            with open(f, "w", encoding="utf-8") as fh:
                json.dump(prof, fh, ensure_ascii=False)
            ok, issues, info = check_profile(f, schema, fixtures_dir)
            verdict = "通过" if ok else "拒绝"
            good = (ok == expect_ok)
            ok_all = ok_all and good
            print(f"  [{'PASS' if good else 'FAIL'}] {label}: 期望{'通过' if expect_ok else '拒绝'}，实际{verdict}")
            for i in issues:
                print(f"         - {i}")
    return ok_all


if __name__ == "__main__":
    sys.exit(main())
