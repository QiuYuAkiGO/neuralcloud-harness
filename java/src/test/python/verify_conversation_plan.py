"""复核正式控制台已完成的“周六看展”两轮会话。用法见 java/README.md。"""

import argparse
import json
from urllib.parse import quote
from urllib.request import urlopen


def verify(events):
    """从每轮的送达回执逆查 Worker 的轮次规划与查证；跨轮使用 Memory 的记录标识和版本与首轮原文相连。"""
    by_id = {(e["runId"], e["eventId"]): e for e in events}

    def parent(event, kind):
        previous = by_id[event["runId"], event["causedBy"]]
        assert previous["type"] == kind, (event["eventId"], kind, previous["type"])
        return previous

    assert not any(e["type"] == "node_failed" for e in events), "会话包含失败事件"
    deliveries = [e for e in events if e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"]
    assert len(deliveries) == 2, "需要两次已送达且持久化的回复"
    assert not any(e["type"] in {"subject_conflict", "intent", "worker_dispatch"} for e in events), "日常回忆不应进入终审"
    questions = ["我周六下午要去看展", "我周六要干嘛来着？"]
    results = []
    for question, durable in zip(questions, deliveries):
        written = parent(parent(durable, "router_memory_write"), "console_response_written")
        reply = parent(written, "chat_worker_reply")
        previous = by_id[reply["runId"], reply["causedBy"]]
        lookup = []
        if previous["type"] == "memory_call_result":
            received = previous
            recall = parent(received, "memory_recall")
            query = parent(recall, "memory_call")
            lookup = [query, recall, received]
            assert query["node"] == "chat-worker"
            plan = parent(query, "chat_worker_turn_plan")
        else:
            plan = previous
        assert plan["type"] == "chat_worker_turn_plan" and plan["node"] == "chat-worker"
        route = by_id[plan["runId"], plan["causedBy"]]
        if route["type"] == "memory_context_reset":
            route = parent(route, "router_route")
        assert route["type"] == "router_route" and route["data"]["route"] == "CONVERSATION"
        source = parent(route, "memory_durable")
        message = parent(parent(source, "router_memory_write"), "operator_message")
        assert message["node"] == "conversation" and message["data"]["text"] == question
        assert source["data"]["kind"] == "OBSERVATION" and reply["node"] == "chat-worker"
        if results:
            assert lookup and plan["data"]["kind"] == "HISTORY", "第二轮须由 Worker 核对首轮安排"
            original = results[0]["source"]
            assert any(b["item"]["ref"] == original and b["item"]["text"] == questions[0]
                       for b in recall["data"]["bindings"]), "查到的来源版本与首轮回执不符"
            assert any(questions[0] in line for line in received["data"]["sentences"])
            assert "下午" in reply["data"]["text"] and "展" in reply["data"]["text"]
        results.append(dict(runId=reply["runId"], reply=reply["data"]["text"],
                            source=dict(record_id=source["data"]["recordId"], revision=source["data"]["revision"]),
                            events={e["type"]: e["eventId"] for e in (message, source, route, plan, *lookup, reply, written)}))
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("console", help="正式控制台根地址")
    parser.add_argument("chain", help="网页显示的会话标识")
    args = parser.parse_args()
    url = args.console.rstrip("/") + "/api/audit/chains/" + quote(args.chain, safe="")
    with urlopen(url, timeout=10) as response:
        results = verify(json.load(response)["events"])
    print(json.dumps(results, ensure_ascii=False, indent=2))
