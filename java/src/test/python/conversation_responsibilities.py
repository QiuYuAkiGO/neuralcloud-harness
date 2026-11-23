"""用正式 Harness 和 Memory v2 核对日常回忆、P3 并行与重大承诺终审。模型和 OneBot 仅监听回环。"""
import argparse
import json
from pathlib import Path

from qq_observe import A, Trial, call, until
from verify_conversation_plan import verify


def tool(name, fields):
    function = {"name": name, "arguments": json.dumps(fields, ensure_ascii=False)}
    message = {"content": None, "tool_calls": [{"id": "call", "type": "function", "function": function}]}
    return 200, {"model": "trial", "choices": [{"message": message, "finish_reason": "tool_calls"}]}


class ConversationTrial(Trial):
    """模型按协议输出固定选择；实际调用方、入库、版本、Epoch 和派发由两个 JVM 决定。"""
    allow_master = False
    note = "根据先约好的看展安排，我目前倾向于优先履行先前的承诺"

    def route(self, path, body):
        if path != "/v1/chat/completions":
            return super().route(path, body)
        names = [t["function"]["name"] for t in body.get("tools", [])]
        if names == ["submit_impression"]:
            return super().route(path, body)
        current = body["messages"][-1].get("content") or ""
        if names == ["select_route"]:
            return tool("select_route", {"route": "ACTION_REQUEST" if "替我安排" in current else "CONVERSATION"})
        if names == ["plan_turn"]:
            if "只能兑现一个" in current:
                return tool("plan_turn", {"kind": "SUBJECT_CONFLICT", "query": "", "conflict": "小雾的两份重要承诺同时到期"})
            if "来着" in current:
                return tool("plan_turn", {"kind": "HISTORY", "query": "周六 看展"})
            return tool("plan_turn", {"kind": "REPLY", "query": ""})
        if "submit_intent" in names:
            if not self.allow_master:
                return 503, {"error": "测试中的 Master 不可用"}
            if "memory_call" in names:
                return tool("memory_call", {"kind": "HISTORY", "query": "周六 看展"})
            return tool("submit_intent", {"assessment": self.note, "impression": self.note,
                        "replyIntent": "说明两个承诺的取舍，优先兑现先前的约定", "evidence": [1], "needsReply": True})
        if names == ["submit_plan"]:
            return tool("submit_plan", {"steps": [{"id": "reply", "worker": "CHAT_WORKER", "dependsOn": [],
                                                  "text": "表达主体对两个承诺的取舍"}]})
        assert not names, names
        self.checkpoint("reply")
        text = "周六下午去看展。" if "来着" in current else "嗯，我记下了。"
        return 200, {"model": "trial", "choices": [{"finish_reason": "stop", "message": {"content": text}}]}


def run(t):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.harness()
    chain = "responsibilities"

    def ask(text):
        return call(t.hp, "/api/nodes/conversation/messages", {"chain": chain, "text": text})

    def events():
        return call(t.hp, "/api/audit/chains/" + chain)[1]["events"]

    # P3 在模型调用中阻塞期间，前台仍能写入、查证并确认回复。日常流程没有 Master 作为可用依赖。
    hold = t.arm("evaluate")
    try:
        for n in range(1, 21):
            t.emit(A, n, "本批普通原文" + str(n))
        hold.wait()
        assert ask("我周六下午要去看展")[0] == 200
        followup = t.worker.submit(ask, "我周六要干嘛来着？")
        assert followup.result(timeout=8)[0] == 200
        def delivered():
            rows = events()
            count = sum(e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION" for e in rows)
            return rows if count == 2 else None
        history = until(delivered, "两轮回复落盘")
        verified = verify(history)
    finally:
        hold.release.set()
    until(lambda: t.status(A)["lastResult"] == "NO_IMPRESSION", "P3 空结论")
    assert not t.posts and not t.sent

    # 同会话的重要承诺才打开终审；意图与主观印象都引用实际取回的版本。
    t.allow_master = True
    assert ask("看展和另一项重要承诺只能兑现一个，小雾决定后请替我安排。")[0] == 200
    rows = events()[len(history):]
    types = [e["type"] for e in rows]
    required = ["subject_conflict", "memory_call", "intent", "router_plan", "worker_dispatch", "chat_worker_reply"]
    assert [k for k in types if k in required] == required and "task_worker_dispatch" not in types
    assert next(e["data"]["route"] for e in rows if e["type"] == "router_route") == "ACTION_REQUEST"
    intent = next(e["data"] for e in rows if e["type"] == "intent")
    candidate, = t.posts
    receipt = next(e["data"] for e in rows if e["type"] == "memory_durable" and e["data"]["kind"] == "impression")
    assert candidate["content"] == intent["impression"] and candidate["source_refs"] == intent["evidence"]
    assert candidate["author"] == "MASTER" and candidate["context"]["role"] == "ROUTER"
    ctx = candidate["context"]
    item, = t.mem("read", dict(context=ctx, kind="HISTORY", use="CITE", targets=[{"record_id": receipt["recordId"]}]))["items"]
    assert item["basis"] == "inferred" and item["source_refs"] == intent["evidence"]

    # 生成期间被修订的普通来源也须拦截，不能写出旧回复。
    gate = t.arm("reply")
    try:
        later = t.worker.submit(ask, "周六的安排是什么来着？")
        gate.wait()
        source = verified[0]["source"]
        t.mem("corrections", dict(context={**ctx, "actor_id": "ORCHESTRATOR", "role": "ORCHESTRATOR"},
              source_instance="neuralcloud-java", event_id="correct-source", operation="REPLACE",
              record_id=source["record_id"], expected_revision=source["revision"], content="周日看展", reason="日程修订"))
    finally:
        gate.release.set()
    assert later.result()[0] >= 400 and events()[-1]["type"] == "node_failed"
    print("PASS 日常查证、后台反思并行、两项承诺终审、主观印象回执、来源变更阻断")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    trial = ConversationTrial(parser.parse_args())
    try:
        run(trial)
    finally:
        trial.close()
