"""将每次运行的结果与人工标签对照。不同任务使用各自的分母。"""


def summarize(items):
    counts = {name: {"count": 0, "total": 0} for name in ("unwanted", "missed")}
    report = {kind: dict(passed=0, total=0, pending=0, failed=0) for kind in ("group", "private")}
    links = {key: [] for key in ("unwanted", "missed", "unwantedTotal", "missedTotal", "either",
                                 "unlabelled", "direct", "observe", "group", "private", "pending", "failed")}
    policies = {name: {"passed":0,"total":0} for name in ("direct","observe")}
    for row in items:
        kind = row["kind"]
        quality = report[kind]
        if row["status"] == "failed":
            quality["failed"] += 1
            links["failed"].append(row["id"])
            continue
        expected = row["expected"]
        if kind == "group":
            if row["policy"] == "OBSERVE_REPLY" and row["addressing"] == "AMBIENT":
                if expected in ("REPLY", "SKIP"):
                    name = "unwanted" if expected == "SKIP" else "missed"
                    counts[name]["total"] += 1
                    links[name+"Total"].append(row["id"])
                    if row["decision"] != expected:
                        counts[name]["count"] += 1
                        links[name].append(row["id"])
                else:
                    links["either" if expected == "EITHER" else "unlabelled"].append(row["id"])
            else:
                name = "direct" if row["policy"] == "OBSERVE_REPLY" else "observe"
                policies[name]["total"] += 1
                policies[name]["passed"] += int(row["decision"] == ("REPLY" if name=="direct" else "SKIP"))
                links[name].append(row["id"])
        if row["decision"] != "REPLY":
            continue
        if not row["persona"] or not row["reasonable"]:
            quality["pending"] += 1
            links["pending"].append(row["id"])
            continue
        quality["total"] += 1
        quality["passed"] += int(row["persona"] == row["reasonable"] == "PASS")
        links[kind].append(row["id"])
    return {**report, "participation": counts, "policies": policies, "links": links}
