"""复核正式控制台已完成的“周六看展”两轮会话。用法见 java/README.md。"""

import argparse
import json
from urllib.parse import quote
from urllib.request import urlopen


def verify(events):
    """从每轮的送达回执逆查派单；跨轮使用 Memory 的记录标识和版本与首轮原文相连。"""
    by_id = {(e["runId"], e["eventId"]): e for e in events}

    def parent(event, kind):
        previous = by_id[event["runId"], event["causedBy"]]
        assert previous["type"] == kind, (event["eventId"], kind, previous["type"])
        return previous

    assert not any(e["type"] == "node_failed" for e in events), "会话包含失败事件"
    deliveries = [e for e in events if e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"]
    assert len(deliveries) == 2, "需要两次已送达且持久化的回复"
    assert sum(e["type"] == "worker_dispatch" for e in events) == 2, "每轮只能派发一次"
    questions = ["我周六下午要去看展", "我周六要干嘛来着？"]
    results = []
    for question, durable in zip(questions, deliveries):
        written = parent(parent(durable, "router_memory_write"), "console_response_written")
        reply = parent(written, "chat_worker_reply")
        dispatch = parent(reply, "worker_dispatch")
        plan = parent(dispatch, "router_plan")
        intent = parent(plan, "intent")
        received = parent(intent, "memory_call_result")
        recall = parent(received, "memory_recall")
        query = parent(recall, "memory_call")
        # 已配置的热目录或更正通知可以在分流与查询之间插入记忆事件。
        route = by_id[query["runId"], query["causedBy"]]
        while route["type"] != "router_route":
            route = by_id[route["runId"], route["causedBy"]]
        source = parent(route, "memory_durable")
        message = parent(parent(source, "router_memory_write"), "operator_message")

        assert message["node"] == "conversation" and message["data"]["text"] == question
        assert source["data"]["kind"] == "OBSERVATION" and route["data"]["route"] == "CONVERSATION"
        assert intent["node"] == "master" and intent["data"]["needsReply"] is True
        assert plan["node"] == "router" and plan["data"]["tool"] == "submit_plan" and plan["data"]["toolCallId"]
        step, = plan["data"]["steps"]
        assert step["worker"] == "CHAT_WORKER" and step["dependsOn"] == [] and step["text"].strip()
        assert dispatch["node"] == "orchestrator" and dispatch["data"]["worker"] == step["worker"]
        assert dispatch["data"]["text"] == step["text"] and reply["node"] == "chat-worker"
        assert reply["data"]["stepId"] == dispatch["data"]["action"] == step["id"]
        workflow = plan["data"]["workflowId"]
        assert reply["data"]["workflowId"] == dispatch["data"]["workflowId"] == workflow
        if results:
            original = results[0]["source"]
            assert workflow != results[0]["workflowId"], "两轮工作流必须独立"
            assert original in intent["data"]["evidence"], "Master 的意图没有引用首轮原文的版本"
            assert any(b["item"]["ref"] == original and b["item"]["text"] == questions[0]
                       for b in recall["data"]["bindings"]), "取证记录与首轮原文不一致"
            assert any(questions[0] in line for line in received["data"]["sentences"]), "原文未交付给 Master"
            assert "下午" in reply["data"]["text"] and "展" in reply["data"]["text"], reply["data"]["text"]
        results.append(dict(runId=reply["runId"], reply=reply["data"]["text"], workflowId=workflow,
                            source=dict(record_id=source["data"]["recordId"], revision=source["data"]["revision"]),
                            evidence=intent["data"]["evidence"], toolCallId=plan["data"]["toolCallId"],
                            events={e["type"]: e["eventId"] for e in (message, source, recall, received,
                                                                       intent, plan, dispatch, reply, written)}))
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
