"""离线核对一次隔离 QQ 人物回放。证据目录使用 result.json 与 memory-http.json，私有期望由调用方提供。"""
import argparse
import hashlib
import json
import re
import sqlite3
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path

from verify_qq_followup_replay import ancestors, only, require


def single(rows, label):
    matches = list(rows)
    require(len(matches) == 1, f"{label} 应唯一，实际有 {len(matches)} 条")
    return matches[0]


def instant(value):
    """所有边界时间都须携带时区，比较时保留绝对时刻。"""
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.utcoffset() is not None, "观测时间缺少时区")
    return parsed


def private_turn(trace, original, roster, expected):
    """绑定原消息、回放 run 和首个计划，从实收回执回溯本轮唯一答复。"""
    inbound = only(trace, "qq_message")
    account, peer = roster["account_id"], roster["peer_id"]
    chain = f"qq-private-{account}-{peer}"
    require(original["type"] == "qq_message" and original["chain"] == inbound["chain"] == chain
            and peer == expected["peer"] and inbound["data"] == original["data"]
            and inbound["data"]["messageId"] == expected["message_id"] and inbound["data"]["text"].strip()
            and original["runId"] != inbound["runId"] and inbound["node"] == "openqq",
            "消息、私聊受众或独立回放运行与原始入站不符")
    require(all(e["chain"] == chain and e["runId"] == inbound["runId"] for e in trace)
            and all(a["eventId"] < b["eventId"] for a, b in zip(trace, trace[1:]))
            and not any(e["type"].endswith(("_failed", "_unknown", "_cancelled")) for e in trace),
            "本轮审计含其他会话、乱序事件或业务失败")
    plan = next((e for e in trace if e["type"] == "chat_worker_turn_plan"), None)
    require(plan is not None and plan["node"] == "chat-worker", "缺少首次 Chat Worker 规划")
    delivery, send, reply = (only(trace, kind) for kind in ("qq_delivery", "qq_send_requested", "chat_worker_reply"))
    path = ancestors(trace, delivery)
    require(path[0] == inbound and plan in path and path[-3:] == [reply, send, delivery],
            "首次规划、唯一答复、发送请求和送达未连到本轮入站")
    return inbound, plan, reply, send, delivery


def author_query(trace, http, inbound, plan, scope, roster, expected):
    """从计划回溯作者解析、实际 HTTP 查询及消费回执，绑定同一受众与输入 ID。"""
    path = ancestors(trace, plan)
    before = [e for e in trace if e["eventId"] < plan["eventId"]]
    gate, lookup, call, recall, result = (only(before, kind) for kind in
                                         ("router_memory_gate", "qq_author_lookup", "memory_call",
                                          "memory_recall", "memory_call_result"))
    sequence = [gate, lookup, call, recall, result]
    require(path[0] == inbound and [e for e in path if e in sequence] == sequence,
            "首次规划前缺少本轮同一因果链上的作者解析和查询结果")
    name, author = expected["name"], expected["author"]
    resolved, cmd = lookup["data"], call["data"]
    require(gate["data"]["consumer"] == "CHAT_WORKER" and gate["data"]["operation"] == "RECALL"
            and lookup["node"] == "router" and resolved["status"] == "RESOLVED"
            and resolved["author_ref"] == cmd["author_ref"] == name
            and resolved["qq_sender_id"] == cmd["qq_sender_id"] == author
            and cmd["kind"] == "HISTORY", "当前称呼未唯一解析到预期作者")
    window = [row for row in http if row["path"] == "/v2/recall"
              and instant(inbound["recordedAt"]) <= instant(row["at"]) <= instant(plan["recordedAt"])
              and "qq_sender_id" in row["request"]]
    entry = single(window, "首次规划前实际按作者召回")
    req, response = entry["request"], entry["result"]
    context = req["context"]
    require(entry["status"] == 200 and req["kind"] == cmd["kind"] and req["qq_sender_id"] == author
            and req["query"] == cmd["query"] and req["use"] == "CITE" and context["role"] == "CHAT_WORKER"
            and context["conversation_id"] == scope and context["qq"]["account_id"] == roster["account_id"]
            and context["qq"]["peer_id"] == roster["peer_id"]
            and set(context["qq"]["member_group_ids"]) == set(roster["member_group_ids"])
            and instant(req["before"]) == instant(inbound["recordedAt"]), "Memory HTTP 的作者、受众或入站时间边界不符")
    consumed = plan["data"]["automaticInput"]
    items = response["items"]
    require(response["assessment"]["state"] == "DIRECT" and consumed["inputId"] and consumed["kind"] == "HISTORY"
            and len(items) == consumed["count"] == result["data"]["count"] > 0, "首轮计划未消费实际命中的作者材料")
    bindings = recall["data"]["bindings"]
    require(len(bindings) == len(items) and all(b["worker"] == "CHAT_WORKER" and b["scope"] == scope
            and b["kind"] == "HISTORY" and b["input"] == consumed["inputId"] for b in bindings),
            "召回绑定的受众、Worker 或输入 ID 与首次规划不一致")
    return items, bindings, result["data"]["sentences"], {e["type"]: e["eventId"] for e in sequence}


def plan_material(packets, question, expected, sentences):
    """只取第一个 plan_turn 的本轮 user 材料，历史对话及后续补查不能补足首次输入。"""
    packet = next((p for p in packets if any(t["function"]["name"] == "plan_turn" for t in p.get("tools", []))), None)
    require(packet is not None and packet["model"] == expected["model"], "未找到预期模型的首次 plan_turn 请求")
    last = packet["messages"][-1]
    _, separator, material = last["content"].partition(question)
    require(last["role"] == "user" and separator, "首次规划请求不含本轮实际入站")
    account_token = re.compile(r"(?<![0-9])" + re.escape(expected["author"]) + r"(?![0-9])")
    require(any(expected["name"] in s and account_token.search(s) and s in material for s in sentences),
            "首次规划的本轮材料缺少召回端给出的姓名与账号对应关系")
    return material


def historical_version(db, ref, cut, original_time):
    """精确版本必须同时早于原入站的水位和时刻。"""
    row = db.execute("SELECT seq, occurred, body FROM revisions WHERE record_id=? AND revision=?",
                     (ref["record_id"], ref["revision"])).fetchone()
    require(row is not None and row[0] <= cut and instant(row[1]) < original_time,
            "来源修订不存在于原消息前的历史截点")
    return row[0], row[1], json.loads(row[2])


def verify_sources(db, items, bindings, sentences, material, scope, roster, original, expected):
    """以截点副本的实际修订核对每条本人原话、共享范围和原时刻，再核对传给模型的北京时间。"""
    cut = expected["cut_seq"]
    require(db.execute("SELECT MAX(seq) FROM changes").fetchone()[0] == cut, "来源数据库的水位与预期历史截点不符")
    venues = {"qq_private": {scope}, "qq_group": {f"group:onebot:{roster['account_id']}:{group}"
                                                 for group in roster["member_group_ids"]}}
    evidence = []
    for item in items:
        ref, quote = item["ref"], item["text"]
        binding = single((b for b in bindings if b["item"]["ref"] == ref), "该修订的规划前绑定")
        require(binding["id"] == ref["record_id"] and all(binding["item"][key] == item[key]
                for key in ("text", "occurred_at", "status", "citation_scope")) and item["status"] == "ONLINE"
                and item["citation_scope"] == scope and quote.strip(), "实际命中与可引用绑定不一致")
        seq, occurred, source = historical_version(db, ref, cut, instant(original["recordedAt"]))
        channel = source["channel"]
        when = instant(occurred)
        require(when == instant(item["occurred_at"])
                and channel["platform"] == "qq" and channel["account_id"] == roster["account_id"]
                and channel["sender_id"] == expected["author"]
                and channel["conversation_id"] in venues.get(channel["type"], ())
                and quote in source["content"], "源修订的作者、原文、可见会话或历史时间越界")
        local = when.astimezone(timezone(timedelta(hours=8)))
        day = rf"{local.year}[-/.年]0?{local.month}[-/.月]0?{local.day}(?:日)?(?![0-9])"
        clock = (rf"(?<![0-9]){local.hour:02}(?:[:：]{local.minute:02}(?:[:：]{local.second:02})?(?![:：][0-9])"
                 rf"|时{local.minute:02}分(?:{local.second:02}秒)?(?![0-9]+秒))(?![0-9])")
        require(any(quote in s and s in material and re.search(day, s.replace(quote, ""))
                    and re.search(clock, s.replace(quote, ""))
                    for s in sentences) and re.search(r"北京时间|东八区|Asia/Shanghai|\+08:00|UTC\+8\b", material),
                "首次规划的本轮材料缺少与源修订对应的原话或北京时间")
        evidence.append(dict(ref=ref, seq=seq, author=channel["sender_id"], venue=channel["conversation_id"],
                             occurred_at=occurred, beijing=local.isoformat(), quote=quote))
    require(expected["source_quotes"] and all(s.strip() for s in expected["source_quotes"])
            and len({v["ref"]["record_id"] for v in evidence}) == len(evidence)
            and all(any(anchor in v["quote"] for v in evidence) for anchor in expected["source_quotes"]),
            "历史回放缺少预先指定的本人原话或重复了同一来源")
    return evidence


def ref_key(ref):
    return ref["record_id"], ref["revision"]


def check_delivery_refs(db, refs, evidence, http, scope, roster, start, end, original, expected):
    """已送达自述可带出其可引用来源，规划后补查也可贡献证据。仅认可当轮受众实际获准的版本。"""
    checked = {ref_key(e["ref"]): dict(ref=e["ref"], origin="first_author") for e in evidence}
    author_quotes = {ref_key(e["ref"]): e["quote"] for e in evidence}
    wanted = {ref_key(ref) for ref in refs}
    cut, original_time = expected["cut_seq"], instant(original["recordedAt"])
    for record in http:
        path = record["path"]
        if (path not in ("/v2/read", "/v2/recall") or record["status"] != 200
                or not (start <= instant(record["at"]) <= end)):
            continue
        req = record["request"]
        context = req["context"]
        qq = context.get("qq", {})
        if (req.get("use") != "CITE" or context["conversation_id"] != scope or qq.get("account_id") != roster["account_id"]
                or qq.get("peer_id") != roster["peer_id"]
                or not set(qq.get("member_group_ids", [])) <= set(roster["member_group_ids"])):
            continue
        for item in record["result"]["items"]:
            if item["status"] != "ONLINE" or item["citation_scope"] != scope:
                continue
            parent = item["ref"]
            if path == "/v2/read" and not any(t["record_id"] == parent["record_id"]
                    and t.get("revision", parent["revision"]) == parent["revision"] for t in req["targets"]):
                continue
            related = [ref for ref in [parent, *item.get("source_refs", [])]
                       if ref_key(ref) in wanted and ref_key(ref) not in checked]
            if not related:
                continue
            _, occurred, source = historical_version(db, parent, cut, original_time)
            require(instant(occurred) == instant(item["occurred_at"]), "上下文授权项与历史修订的发生时间不符")
            by_author = path == "/v2/recall" and req.get("qq_sender_id") == expected["author"]
            if by_author:
                channel = source["channel"]
                require(channel["sender_id"] == expected["author"] and channel["account_id"] == roster["account_id"]
                        and item["text"] in source["content"], "补查作者来源与历史原文不符")
            for ref in related:
                if ref == parent:
                    origin = "author_recall" if by_author else "authorized_context"
                    cited = source
                else:
                    _, _, cited = historical_version(db, ref, cut, original_time)
                    origin = "context_source"
                author = cited.get("channel", {})
                if author.get("sender_id") == expected["author"] and author.get("account_id") == roster["account_id"]:
                    author_quotes[ref_key(ref)] = cited["content"]
                checked[ref_key(ref)] = dict(ref=ref, origin=origin, via=parent, path=path)
    require(wanted <= checked.keys(), "送达含本轮受众未获准的引用版本或无来源引用")
    return [checked[ref_key(ref)] for ref in refs], [author_quotes[key] for key in wanted if key in author_quotes]


def verify(trial, http, original, rosters, db, expected):
    """从独立采集的正式入口、Memory 及模型边界和 OneBot 收件记录生成可复核回执。"""
    roster = rosters["qq"]
    inbound, plan, reply, send, delivery = private_turn(trial["trace"], original, roster, expected)
    scope = f"c2c:onebot:{roster['account_id']}:{roster['peer_id']}"
    groups = [rosters["by_group"][group] for group in roster["member_group_ids"]]
    found = {str(member["user_id"]) for group in groups for member in group["rows"]
             if expected["name"] in (member["nickname"], member["card"])}
    require(instant(rosters["completed_at"]) <= instant(original["recordedAt"])
            and all(group["verified_membership"] for group in groups) and found == {expected["author"]},
            "原消息前的共享群名册未唯一对应目标作者")
    items, bindings, sentences, events = author_query(trial["trace"], http, inbound, plan, scope, roster, expected)
    material = plan_material(trial["modelInputs"], inbound["data"]["text"], expected, sentences)
    evidence = verify_sources(db, items, bindings, sentences, material, scope, roster, original, expected)
    actual = single(trial["sent"], "隔离 OneBot 实收")
    action = single((a for a in trial["onebotActions"] if a["action"].startswith("send_")), "隔离 OneBot 发信动作")
    require(action["action"] == "send_private_msg", "实际收件动作并非本轮私聊发送")
    params, text = actual["params"], reply["data"]["text"]
    message = params["message"]
    if isinstance(message, list):
        require(all(s["type"] == "text" for s in message), "实收消息含未核对的非文本段")
        message = "".join(s["data"]["text"] for s in message)
    require(str(params["user_id"]) == roster["peer_id"] and type(actual["receipt"]) is int
            and actual["receipt"] == delivery["data"]["messageId"] > 0 and message == text and text.strip()
            and instant(send["recordedAt"]) <= instant(actual["at"]) <= instant(delivery["recordedAt"]),
            "OneBot 收件人、时间、回执或正文与唯一业务答复不一致")
    refs = delivery["data"].get("source_refs", [])
    checked, cited_quotes = check_delivery_refs(db, refs, evidence, http, scope, roster, instant(inbound["recordedAt"]),
                                                instant(actual["at"]), original, expected)
    # 回复逐字引用哪条已知原话，核对该条对应的来源；概括型回复可沿用当轮其他有依据的引用。
    for segments in re.findall(r'“([^”]+)”|「([^」]+)」|"([^"]+)"', text):
        quote = next(part for part in segments if part)
        if any(row["quote"] == quote for row in evidence):
            require(any(quote in body for body in cited_quotes), "实收答复逐字引用本人原话却缺对应来源版本")
    require(all(re.search(p, text) for p in expected["reply_require"])
            and not any(re.search(p, text) for p in expected["reply_forbid"]), "实收答复违背本轮预定的归属判据")
    return dict(message_id=inbound["data"]["messageId"], run=inbound["runId"], first_plan=plan["eventId"], events=events,
                source_cut=expected["cut_seq"], evidence=evidence, source_refs=refs, checked_refs=checked, peer=roster["peer_id"],
                model=expected["model"], receipt=actual["receipt"], reply=text,
                reply_sha256=hashlib.sha256(text.encode("utf-8")).hexdigest())


def read_json(path):
    return json.loads(path.read_bytes())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trial", type=Path, help="隔离回放产生的外部观测目录")
    for name in ("original", "rosters", "snapshot", "expect"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--output", type=Path, help="向此文件写 UTF-8 回执，含实收答复和核对过的原话")
    args = parser.parse_args()
    try:
        with closing(sqlite3.connect(args.snapshot.resolve().as_uri() + "?mode=ro", uri=True)) as database:
            result = verify(read_json(args.trial / "result.json"), read_json(args.trial / "memory-http.json"),
                            read_json(args.original), read_json(args.rosters), database, read_json(args.expect))
        receipt = json.dumps(result, ensure_ascii=False, indent=2)
        if args.output:
            args.output.write_text(receipt + "\n", encoding="utf-8")
        else:
            print(receipt)
    except (OSError, ValueError, KeyError, TypeError, IndexError, sqlite3.Error) as error:
        parser.exit(1, f"核验失败，{error}\n")
