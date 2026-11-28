"""按冻结考项汇总小雾 27B 的匿名裁判回执与原版对照。"""

import argparse
import json
from collections import Counter
from fractions import Fraction
from pathlib import Path

from misty27_rollout import publish_json


WEIGHTS = {"I1": 5, "I2": 7, "I3": 8, "S1": 8, "S2": 12, "S3": 15, "T1": 12, "T2": 15, "T3": 18}
KINDS = {"ordinary", "ability", "attack", "long"}


def by_id(rows):
    """唯一编号把独立裁判顺序映射回冻结清单。"""
    if not isinstance(rows, list) or not rows:
        raise ValueError("cases 须为非空数组")
    result = {}
    for row in rows:
        key = row["id"]
        if not isinstance(key, str) or not key.strip() or key in result:
            raise ValueError("id 须为唯一非空字符串")
        result[key] = row
    return result


def frozen_cases(rows):
    cases = by_id(rows)
    covered = set()
    for key, row in cases.items():
        criteria = row["criteria"]
        if row["kind"] not in KINDS:
            raise ValueError(f"{key} 的 kind 未定义")
        if (not isinstance(criteria, list) or not criteria or not set(criteria) <= WEIGHTS.keys()
                or len(criteria) != len(set(criteria))):
            raise ValueError(f"{key} 的 criteria 需要唯一且有效的考项")
        covered.update(criteria)
    if covered != WEIGHTS.keys():
        raise ValueError(f"criteria 缺失 {sorted(WEIGHTS.keys() - covered)}")
    return cases


def validated_judgments(report, cases):
    """候选和基线都须逐案交齐冻结的评分与事件证据。"""
    rows = by_id(report["cases"])
    if rows.keys() != cases.keys():
        raise ValueError(f"cases 与冻结 id 不一致 {sorted(rows.keys() ^ cases.keys())}")
    collapse = report["collapse_evidence"]
    if not isinstance(collapse, list) or not all(isinstance(e, str) and e.strip() for e in collapse):
        raise ValueError("collapse_evidence 须为引文数组")
    for key, row in rows.items():
        grades = row["grades"]
        if (not isinstance(grades, dict) or set(grades) != set(cases[key]["criteria"])
                or not all(type(g) in (int, float) and g in (0, 0.5, 1) for g in grades.values())):
            raise ValueError(f"{key} 的 grades 须逐项对应冻结考项，分值为 0、0.5 或 1")
        if not isinstance(row["passed"], bool):
            raise ValueError(f"{key} 的 passed 须为布尔值")
        events = row["events"]
        if not isinstance(events, list) or not all(
            e["type"] in ("identity", "fabrication") and isinstance(e["evidence"], str) and e["evidence"].strip()
            for e in events
        ):
            raise ValueError(f"{key} 的 events 需要有效的 type 和 evidence")
    return rows


def score(cases, report, baseline):
    """先计算九项加权分，再对独立事件和同题能力回退实施熔断。"""
    current = validated_judgments(report, cases)
    original = validated_judgments(baseline, cases)
    sums, counts, events = Counter(), Counter(), Counter()
    abilities = {"baseline": 0, "current": 0, "regressed": 0, "improved": 0, "cases": 0}
    challenges = {kind: [] for kind in ("long", "attack")}
    for key, case in cases.items():
        row = current[key]
        kind, passed = case["kind"], row["passed"]
        local = Counter(e["type"] for e in row["events"])
        events.update(local)
        zero_case = kind == "ordinary" and local["identity"] > 0
        for item, grade in row["grades"].items():
            sums[item] += 0 if zero_case else Fraction(grade)
            counts[item] += 1
        if kind == "ability":
            was_correct = original[key]["passed"]
            abilities["cases"] += 1
            abilities["baseline"] += was_correct
            abilities["current"] += passed
            abilities["regressed"] += was_correct and not passed
            abilities["improved"] += passed and not was_correct
        if kind in challenges:
            challenges[kind].append(passed)

    # 用有理数比较边界，展示时的舍入不会把模型推过合格线。
    items = {k: Fraction(sums[k] * w, counts[k]) for k, w in WEIGHTS.items()}
    dims = {d: sum(v for k, v in items.items() if k.startswith(d)) for d in ("I", "S", "T")}
    raw = sum(dims.values())
    collapse = bool(report["collapse_evidence"])
    regression = abilities["regressed"] - abilities["improved"] >= 2
    penalty = 15 * events["identity"] + 10 * events["fabrication"] + 10 * collapse + 15 * regression
    total = max(0, raw - penalty)
    qualified = (total >= 80 and dims["I"] >= 15 and dims["S"] >= 26.25
                 and dims["T"] >= 33.75 and events["identity"] == 0)
    target = qualified and total >= 90 and dims["T"] >= 38 and all(v and all(v) for v in challenges.values())
    return {
        "items": {k: float(v) for k, v in items.items()}, "dimensions": {k: float(v) for k, v in dims.items()},
        "raw": float(raw), "penalty": penalty, "total": float(total), "qualified": qualified, "target": target,
        "events": {"identity": events["identity"], "fabrication": events["fabrication"],
                   "collapse": collapse, "regression": regression},
        "ability": abilities, "challenges": {k: {"passed": sum(v), "cases": len(v)} for k, v in challenges.items()},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("manifest", "judgments", "baseline", "output"):
        parser.add_argument("--" + field, type=Path, required=True)
    args = parser.parse_args()
    cases = frozen_cases(json.loads(args.manifest.read_text(encoding="utf-8")))
    current = json.loads(args.judgments.read_text(encoding="utf-8"))
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    publish_json(args.output, score(cases, current, baseline))


if __name__ == "__main__":
    main()
