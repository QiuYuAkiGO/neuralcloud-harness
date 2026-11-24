"""离线核对两份 Playground execute 私聊结果。成功后输出两次送达正文，供操作者复核语义。"""
import argparse
import json
import re
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def only(events, kind):
    matches = [event for event in events if event["type"] == kind]
    require(len(matches) == 1, f"需要一条 {kind}，实际有 {len(matches)} 条")
    return matches[0]


def ancestors(events, event):
    """沿本轮审计的因果链回溯，防止其他轮次或另一条调用的命中充当证据。"""
    by_id = {row["eventId"]: row for row in events}
    path = [event]
    while path[-1].get("causedBy") is not None:
        parent = path[-1]["causedBy"]
        require(parent in by_id and parent < path[-1]["eventId"], "审计父事件缺失或时序错误")
        path.append(by_id[parent])
    return path[::-1]


def delivered_turn(turn, *, check_drafts=False):
    """从公开入站与送达回执绑定实际收件人和答复，再取本轮规划与后续正文生成请求。"""
    trace = turn["trace"]
    inbound = only(trace, "qq_message")
    private = re.fullmatch(r"qq-private-([0-9]+)-([0-9]+)", inbound["chain"])
    text = turn["input"]["text"]
    require(private and private[2] == str(turn["input"]["speaker"]) and text and inbound["data"]["text"] == text,
            "入站渠道、私聊发件人或目标发言不匹配")
    require(all(e["runId"] == inbound["runId"] and e["chain"] == inbound["chain"] for e in trace),
            "单轮审计包含其他运行或会话")
    require(all(a["eventId"] < b["eventId"] for a, b in zip(trace, trace[1:])), "单轮审计的事件顺序错误")
    delivery = only(trace, "qq_delivery")
    reply = only(trace, "chat_worker_reply")
    ancestry = ancestors(trace, delivery)
    require(turn["decision"] == "REPLY" and type(turn["deliveries"]) is int and turn["deliveries"] == 1
            and type(delivery["data"]["messageId"]) is int and delivery["data"]["messageId"] > 0,
            "每轮应恰有一次 QQ 实收回执")
    require(ancestry[0] == inbound and only(trace, "chat_worker_turn_plan") in ancestry
            and len(ancestry) >= 3 and ancestry[-3] == reply
            and ancestry[-2]["type"] == "qq_send_requested"
            and isinstance(turn["reply"], str) and turn["reply"].strip() and reply["data"]["text"] == turn["reply"],
            "结果正文与本次 QQ 实际送达的业务答复不一致")
    packets = turn["modelInputs"]
    plan = next((i for i, packet in enumerate(packets) if any(
        tool["function"]["name"] == "plan_turn" for tool in packet.get("tools", []))), None)
    require(plan is not None, "缺少 Chat Worker 首次 plan_turn 请求")
    messages = [packets[plan]["messages"]]
    if check_drafts:
        drafts = [packet["messages"] for packet in packets[plan + 1:] if not packet.get("tools")]
        require(drafts, "缺少安抚轮的正文生成请求")
        messages.extend(drafts)
    require(all(rows and rows[-1]["role"] == "user" and text in rows[-1]["content"]
                and any(row["role"] == "system" for row in rows) for rows in messages),
            "模型请求无法对应本轮目标发言")
    return f"c2c:onebot:{private[1]}:{private[2]}", messages


def before_planning(turn, scope, messages, reference, topics, expected_fact):
    """查证必须由当前受众在本轮入账后发起，同一版本须出现在实际首次规划的本轮材料中。"""
    trace = turn["trace"]
    plan = only(trace, "chat_worker_turn_plan")
    path = ancestors(trace, plan)
    gate = only(trace, "router_memory_gate")
    require(gate in path, "首次规划的因果链中缺少本轮自动查询")
    gate_index = path.index(gate)
    stored = only(path[:gate_index], "memory_durable")
    write = only(path[:gate_index], "router_memory_write")
    require(path[0] == only(trace, "qq_message") and stored["data"]["kind"] == "OBSERVATION"
            and stored["causedBy"] == write["eventId"] and write["data"]["sourceEventId"] == path[0]["eventId"],
            "自动查询未关联本轮入站的持久回执")
    query = only(path[gate_index + 1:], "memory_call")
    recall = only(path[gate_index + 1:], "memory_recall")
    received = only(path[gate_index + 1:], "memory_call_result")
    decision = gate["data"]
    require(gate["node"] == "router" and recall["node"] == received["node"] == "memory" and plan["node"] == "chat-worker"
            and decision["consumer"] == "CHAT_WORKER" and decision["capability"] == "memory_call"
            and decision["operation"] == "RECALL" and query["node"] == "chat-worker"
            and query["data"]["query"] == decision["query"] and any(t in decision["query"] for t in topics)
            and recall["causedBy"] == query["eventId"] and received["causedBy"] == recall["eventId"],
            "首次规划前没有同一 Chat Worker 对指定主题的自动查证")
    consumed = plan["data"].get("automaticInput", {})
    require(consumed.get("inputId") and consumed.get("count", 0) > 0 and consumed.get("utf8Bytes", 0) > 0
            and consumed["kind"] == query["data"]["kind"] == received["data"]["kind"],
            "首次规划没有消费本轮自动查证材料")
    hits = [b["item"] for b in recall["data"]["bindings"] if b["id"] == reference["record_id"]
            and b["item"]["ref"] == reference and b["input"] == consumed["inputId"] and b["worker"] == "CHAT_WORKER"
            and b["kind"] == consumed["kind"] and b["scope"] == scope and b["item"]["citation_scope"] == scope
            and b["item"]["status"] == "ONLINE"]
    require(len(hits) == 1, "实际消费输入中缺少当前受众可引用的指定记录版本")
    fact = hits[0]["text"]
    require(fact and (expected_fact is None or fact == expected_fact), "权威记录原文与给定确认文本不符")
    material = messages[-1]["content"].partition(turn["input"]["text"])[2]
    require(any(fact in line for line in received["data"]["sentences"]) and fact in material,
            "首次 plan_turn 请求的本轮材料中缺少该版本的完整确认原文")
    return decision["query"]


def verify(first, second, reference, topics, previous=None, expected_fact=None, forbidden=()):
    first_scope, first_calls = delivered_turn(first)
    second_scope, second_calls = delivered_turn(second, check_drafts=True)
    require(first_scope == second_scope, "两次结果不属于同一私聊受众")
    first_query = before_planning(first, first_scope, first_calls[0], reference, topics, expected_fact)
    previous = first["reply"] if previous is None else previous
    require(previous.strip(), "需要上一条已送达回复的完整正文")
    for messages in second_calls:
        latest = next((row["content"] for row in reversed(messages[:-1]) if row["role"] == "assistant"), "")
        require(previous in latest, "安抚轮的首次规划或正文生成未以 assistant 身份收到完整前答")
    inbound = only(second["trace"], "qq_message")
    queries = [row["data"]["query"] for row in second["trace"] if row["eventId"] > inbound["eventId"]
               and (row["type"] == "memory_call" or (row["type"] == "router_memory_gate"
                    and row["data"]["operation"] == "RECALL"))]
    require(not any(any(t in query for t in (*topics, first_query)) for query in queries),
            "安抚轮再次发起了旧主题查证")
    require(not any(phrase in second["reply"] for phrase in (previous, first["reply"], *forbidden)),
            "安抚轮的送达正文重复了旧答或指定短语")
    return dict(source=reference, firstQuery=first_query, secondQueries=list(dict.fromkeys(queries)),
                firstReply=first["reply"], secondReply=second["reply"])


def reference(value):
    match = re.fullmatch(r"([0-9a-f]{64})@([1-9][0-9]*)", value)
    if not match:
        raise argparse.ArgumentTypeError("记录版本须为 64 位小写十六进制 ID@正整数")
    return dict(record_id=match[1], revision=int(match[2]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path, help="查证轮的 execute 结果 JSON")
    parser.add_argument("second", type=Path, help="安抚轮的 execute 结果 JSON")
    parser.add_argument("--source", type=reference, required=True, help="权威记录 ID@revision")
    parser.add_argument("--topic", action="append", required=True, help="旧主题在查询中的关键词，可重复提供同义词")
    parser.add_argument("--previous-reply-file", type=Path, help="独立历史前缀中上一条已送达的完整正文，默认取第一轮回复")
    parser.add_argument("--fact-file", type=Path, help="另行核对的确认原文；文本文件采用 UTF-8，保留空白")
    parser.add_argument("--forbid-second", action="append", default=[], help="安抚回复中不得复述的短语，可重复")
    args = parser.parse_args()
    try:
        require(all(s.strip() for s in [*args.topic, *args.forbid_second]), "主题与拒绝短语须为非空文本")
        result = verify(*(json.loads(p.read_bytes()) for p in (args.first, args.second)), args.source, args.topic,
                        args.previous_reply_file.read_bytes().decode("utf-8-sig") if args.previous_reply_file else None,
                        args.fact_file.read_bytes().decode("utf-8-sig") if args.fact_file else None, args.forbid_second)
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        parser.exit(1, f"核验失败，{error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
