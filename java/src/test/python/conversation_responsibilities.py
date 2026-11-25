"""用正式 Harness 和 Memory v2 核对日常回忆、P3 并行与重大承诺终审。模型和 OneBot 仅监听回环。"""
import argparse
import json
from pathlib import Path

from qq_observe import A, Trial, call, until
from verify_conversation_plan import lookup_chain, parent, planning_route, verify


FOLLOWUP = "我周六要干嘛来着？"
SCHEDULE = "周六 看展"


def tool(name, fields):
    function = {"name": name, "arguments": json.dumps(fields, ensure_ascii=False)}
    message = {"content": None, "tool_calls": [{"id": "call", "type": "function", "function": function}]}
    return 200, {"model": "trial", "choices": [{"message": message, "finish_reason": "tool_calls"}]}


class ConversationTrial(Trial):
    """模型按协议输出固定选择；实际调用方、入库、版本、Epoch 和派发由两个 JVM 决定。"""
    allow_master = False
    note = "根据先约好的看展安排，我目前倾向于优先履行先前的承诺"

    def __init__(self, args):
        self.lookups_by_chat = []
        super().__init__(args)

    def route(self, path, body):
        if path != "/v1/chat/completions":
            if (path == "/v2/recall" and body["kind"] == "HISTORY" and body["context"]["role"] == "CHAT_WORKER"
                    and body["context"]["conversation_id"] == "console:responsibilities"):
                self.lookups_by_chat.append(body["query"])
            return super().route(path, body)
        names = [t["function"]["name"] for t in body.get("tools", [])]
        if names == ["submit_impression"]:
            return super().route(path, body)
        current = body["messages"][-1].get("content") or ""
        if names == ["select_route"]:
            return tool("select_route", {"route": "ACTION_REQUEST" if "替我安排" in current else "CONVERSATION"})
        if names == ["plan_turn"]:
            # 用例的提问均为单行。后附证据可以引用上轮同样的文字，不能替代本轮输入选择规划。
            question = current.partition("\n")[0]
            if "只能兑现一个" in question:
                return tool("plan_turn", {"kind": "SUBJECT_CONFLICT", "query": "", "conflict": "小雾的两份重要承诺同时到期"})
            if "来着" in question:
                return tool("plan_turn", {"kind": "HISTORY", "query": FOLLOWUP if question == FOLLOWUP else SCHEDULE})
            return tool("plan_turn", {"kind": "REPLY", "query": ""})
        if "submit_intent" in names:
            if not self.allow_master:
                return 503, {"error": "测试中的 Master 不可用"}
            if "memory_call" in names:
                return tool("memory_call", {"kind": "HISTORY", "query": SCHEDULE})
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
    # 本用例在模型起草期间修订来源，显式启用草稿完成后的复核。
    with t.hc.open("a", encoding="utf-8") as config:
        config.write("chatReviewAfterDraft=true\n")
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
        start = len(t.lookups_by_chat)
        followup = t.worker.submit(ask, FOLLOWUP)
        status, response = followup.result(timeout=8)
        assert status == 200, (status, response)
        def delivered():
            rows = events()
            count = sum(e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION" for e in rows)
            return rows if count == 2 else None
        history = until(delivered, "两轮回复落盘")
        verified = verify(history, require_gate=True)
        # 模型主动索取与本轮 Push 同一主题。既检查父链，也核对 Memory 服务只接到一次实际读取。
        assert t.lookups_by_chat[start:] == [FOLLOWUP]
        assert "memory_call" not in verified[1]["events"]
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
    final_review = [e for e in rows if e["type"] in required
                    and (e["type"] != "memory_call" or e["node"] == "master")]
    assert [e["type"] for e in final_review] == required and "task_worker_dispatch" not in types
    conflict, master_call, intent_event = final_review[:3]
    by_id = {(e["runId"], e["eventId"]): e for e in rows}
    plan = parent(by_id, conflict, "chat_worker_turn_plan")
    route, prepared = planning_route(by_id, plan, require_gate=True)
    assert route["data"]["route"] == "ACTION_REQUEST" and plan["data"]["kind"] == "SUBJECT_CONFLICT"
    # Chat Worker 的宿主查询先于终审，Master 的主动查询以冲突为父事件、以意图为结果的消费者。
    assert [e for e in rows if e["type"] == "memory_call"] == [prepared[1], master_call]
    reviewed, _, _ = lookup_chain(by_id, parent(by_id, intent_event), "master")
    assert reviewed == master_call and parent(by_id, master_call) == conflict
    intent = intent_event["data"]
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
        start = len(t.lookups_by_chat)
        later = t.worker.submit(ask, "周六的安排是什么来着？")
        gate.wait()
        # 这一轮模型索取不同的检索词，原有补查必须保留，并沿用发送前的来源版本检查。
        assert t.lookups_by_chat[start:] == ["周六的安排是什么来着？", SCHEDULE]
        source = verified[0]["source"]
        t.mem("corrections", dict(context={**ctx, "actor_id": "ORCHESTRATOR", "role": "ORCHESTRATOR"},
              source_instance="neuralcloud-java", event_id="correct-source", operation="REPLACE",
              record_id=source["record_id"], expected_revision=source["revision"], content="周日看展", reason="日程修订"))
    finally:
        gate.release.set()
    assert later.result()[0] >= 400 and events()[-1]["type"] == "node_failed"
    print("PASS 首次规划前查证、同题复用与异题补查、后台反思并行、两项承诺终审、主观印象回执、来源变更阻断")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    trial = ConversationTrial(parser.parse_args())
    try:
        run(trial)
    finally:
        trial.close()
