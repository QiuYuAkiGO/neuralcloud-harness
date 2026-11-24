"""复核正式控制台已完成的“周六看展”两轮会话。用法见 java/README.md。"""

import argparse
import json
from urllib.parse import quote
from urllib.request import urlopen


def parent(by_id, event, kind=None):
    """父事件必须来自同一轮，只有契约允许不同时序的入口才由调用方分辨类型。"""
    previous = by_id[event["runId"], event["causedBy"]]
    if kind is not None:
        assert previous["type"] == kind, (event["eventId"], kind, previous["type"])
    return previous


def lookup_chain(by_id, received, caller):
    """一份结果须能逐跳追到提供证据的 Memory 读取以及实际消费者发起的调用。"""
    assert received["type"] == "memory_call_result" and received["node"] == "memory"
    recall = parent(by_id, received, "memory_recall")
    query = parent(by_id, recall, "memory_call")
    assert recall["node"] == "memory" and query["node"] == caller
    assert query["data"]["kind"] == received["data"]["kind"] == "HISTORY"
    return query, recall, received


def planning_route(by_id, plan, *, require_gate=False):
    """沿规划入边核对新版本的自动查询；旧审计从路由或当轮的上下文刷新直接进入规划。"""
    assert plan["type"] == "chat_worker_turn_plan" and plan["node"] == "chat-worker"
    previous = parent(by_id, plan)
    prepared = ()
    if require_gate or previous["type"] == "memory_call_result":
        query, recall, received = lookup_chain(by_id, previous, "chat-worker")
        gate = parent(by_id, query, "router_memory_gate")
        decision = gate["data"]
        assert gate["node"] == "router" and decision["consumer"] == "CHAT_WORKER"
        assert decision["operation"] == "RECALL" and decision["capability"] == "memory_call"
        assert decision["query"] == query["data"]["query"]
        prepared = gate, query, recall, received
        previous = parent(by_id, gate)
    if previous["type"] == "memory_context_reset":
        previous = parent(by_id, previous, "router_route")
    assert previous["type"] == "router_route"
    return previous, prepared


def verify(events, *, require_gate=False):
    """独立阅读新旧审计。实时验收可要求每轮都有门控，防止整批遗漏被当作历史版本。"""
    by_id = {(e["runId"], e["eventId"]): e for e in events}

    assert not any(e["type"] == "node_failed" for e in events), "会话包含失败事件"
    deliveries = [e for e in events if e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"]
    assert len(deliveries) == 2, "需要两次已送达且持久化的回复"
    assert not any(e["type"] in {"subject_conflict", "intent", "worker_dispatch"} for e in events), "日常回忆不应进入终审"
    questions = ["我周六下午要去看展", "我周六要干嘛来着？"]
    results = []
    verified_calls = set()
    for question, durable in zip(questions, deliveries):
        written = parent(by_id, parent(by_id, durable, "router_memory_write"), "console_response_written")
        reply = parent(by_id, written, "chat_worker_reply")
        previous = parent(by_id, reply)
        pull = ()
        if previous["type"] == "memory_call_result":
            pull = lookup_chain(by_id, previous, "chat-worker")
            plan = parent(by_id, pull[0], "chat_worker_turn_plan")
        else:
            plan = previous
        route, prepared = planning_route(by_id, plan, require_gate=require_gate)
        assert route["data"]["route"] == "CONVERSATION"
        verified_calls.update((e["runId"], e["eventId"]) for e in (*prepared, *pull)
                              if e["type"] in {"router_memory_gate", "memory_call"})
        if prepared and pull:
            assert prepared[1]["data"]["query"] != pull[0]["data"]["query"], "同轮相同主题应复用已查结果"
        source = parent(by_id, route, "memory_durable")
        message = parent(by_id, parent(by_id, source, "router_memory_write"), "operator_message")
        assert message["node"] == "conversation" and message["data"]["text"] == question
        assert source["data"]["kind"] == "OBSERVATION" and reply["node"] == "chat-worker"
        if results:
            assert (prepared or pull) and plan["data"]["kind"] == "HISTORY", "第二轮须由 Worker 核对首轮安排"
            original = results[0]["source"]
            for lookup in (prepared[1:], pull):
                if lookup:
                    _, recall, received = lookup
                    assert any(b["item"]["ref"] == original and b["item"]["text"] == questions[0]
                               for b in recall["data"]["bindings"]), "查到的来源版本与首轮回执不符"
                    assert any(questions[0] in line for line in received["data"]["sentences"])
            assert "下午" in reply["data"]["text"] and "展" in reply["data"]["text"]
        result = dict(runId=reply["runId"], reply=reply["data"]["text"],
                      source=dict(record_id=source["data"]["recordId"], revision=source["data"]["revision"]),
                      events={e["type"]: e["eventId"] for e in (message, source, route, plan, *pull, reply, written)})
        if prepared:
            result["beforePlanning"] = {e["type"]: e["eventId"] for e in prepared}
        results.append(result)
    # 同一进程的多轮可能共用 runId，按两条完整父链核对所有门控和查询，防止未挂回回复的重复读取。
    assert verified_calls == {(e["runId"], e["eventId"]) for e in events
                              if e["type"] in {"router_memory_gate", "memory_call"}}
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
