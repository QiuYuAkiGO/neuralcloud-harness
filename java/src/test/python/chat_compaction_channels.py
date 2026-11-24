"""回环验收 QQ 参与、网页引用受众、同版撤权和轮首更正。参数为 Harness JAR、Memory JAR 和新目录。"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from conversation_responsibilities import tool
from qq_observe import A, B, ACCOUNT, Trial, call, until


QUERY = "请查验票口令。"
CHECK = "继续前请复述核对结果。"
OLD = "青槐票根七号"
NEW = "银杏票根九号"
LOCAL = "小雾核验札记"
RAW_ONLY = "原始依据中的内部比对码柳枝"
OTHER = "另一位用户的青瓷徽记"
GROUP = "群活动原文"
GROUP_SUMMARY = "群里在讨论周末活动。"


def wire(value):
    return json.dumps(value, ensure_ascii=False)


def request_text(body):
    return "\n".join(m.get("content") or "" for m in body["messages"] if m["role"] != "system")


class ChannelTrial(Trial):
    """只控制模型和撤权时的受众读取响应；正式的 Router、Memory 账本、网页写出和 QQ 回执均照常运行。"""
    def __init__(self, args):
        super().__init__(args)
        with (self.dir / "chat.properties").open("a", encoding="utf-8") as config:
            config.write("contextCompression=ROLLING\nhistoryBudgetUnits=4096\n")
        self.calls = []
        self.denied = None
        self.denied_reads = []
        self.ambient = "SKIP"

    @staticmethod
    def summary(body):
        entries = json.loads(body["messages"][-1]["content"])["entries"]
        for row in entries:
            if LOCAL in row["text"]:
                statement = f"{LOCAL}提到过{OLD}。"
            elif GROUP in row["text"]:
                statement = GROUP_SUMMARY
            else:
                continue
            return {"items": [dict(kind="TOPIC", text=statement, sources=[row["index"]])], "closed": []}
        return {"items": [], "closed": []}

    def route(self, path, body):
        if path == "/v1/chat/completions":
            names = [t["function"]["name"] for t in body.get("tools", [])]
            name = names[0] if names else "reply"
            self.calls.append((name, body))
            current = body["messages"][-1].get("content") or ""
            if name == "select_reply":
                return tool(name, {"decision": self.ambient})
            if name == "submit_chat_summary":
                return tool(name, self.summary(body))
            if name == "select_route":
                if CHECK in current:
                    self.checkpoint("after-inbound")
                return tool(name, {"route": "CONVERSATION"})
            if name == "plan_turn":
                return tool(name, {"kind": "HISTORY", "query": "验票口令"} if QUERY in current
                            else {"kind": "REPLY", "query": ""})
            if name == "reply":
                received = request_text(body)
                text = "收到。"
                if QUERY in current and OLD in received:
                    text = f"{LOCAL}写着{OLD}。"
                elif CHECK in current:
                    if OLD in received or LOCAL in received:
                        text = f"{LOCAL}写着{OLD}。"
                    elif NEW in received:
                        text = f"现在用{NEW}。"
                return 200, {"model": "trial", "choices": [{"message": {"content": text}, "finish_reason": "stop"}]}
        status, data = super().route(path, body)
        if path == "/v2/read" and self.denied and status == 200:
            audience, record = self.denied
            if body["context"]["conversation_id"] == audience:
                for i, row in enumerate(data["items"]):
                    sources = [row.get("ref", {}), *row.get("source_refs", [])]
                    if any(ref.get("record_id") == record for ref in sources):
                        self.denied_reads.append(body)
                        data["items"][i] = dict(record_id=body["targets"][i]["record_id"], status="NOT_FOUND")
        return status, data

    def web(self, chain, text):
        status, response = call(self.hp, "/api/nodes/conversation/messages", {"chain": chain, "text": text})
        assert status == 200, (status, response)
        return response

    def web_events(self, chain):
        return call(self.hp, "/api/audit/chains/" + chain)[1]["events"]

    def events(self, group):
        # OneBot 发出第一条消息后，HTTP 控制台可能尚未登记该群的首个审计事件。
        status, data = call(self.hp, f"/api/audit/chains/qq-group-{ACCOUNT}-{group}")
        if status == 404:
            return []
        assert status == 200, (status, data)
        return data["events"]


def qq_participation(t):
    """两群都越过水位。SKIP 与仅观察保持静默，允许回复群先见近期原文，再压缩并确认一次渠道送达。"""
    t.policy(A, "OBSERVE_REPLY")
    t.policy(B, "OBSERVE")
    start = len(t.calls)
    for i in range(1, 4):
        t.emit(A, i, f"{GROUP}{i} " + "甲" * 1080, at=False)
        until(lambda: sum(e["type"] == "qq_participation" for e in t.events(A)) == i, "跳过群消息")
        t.emit(B, i + 20, f"仅观察历史{i} " + "乙" * 1080, at=False)
    until(lambda: t.status(B)["pending"] == 3, "仅观察群已记下历史")
    t.emit(B, 24, "即使点名也只观察", at=True)
    until(lambda: t.status(B)["pending"] == 4, "高水位观察群保持静默")
    t.emit(A, 4, "这一轮仍不需要插话。", at=False)
    until(lambda: sum(e["type"] == "qq_participation" for e in t.events(A)) == 4, "高水位普通消息判为跳过")
    assert [n for n, _ in t.calls[start:]] == ["select_reply"] * 4
    assert not t.sent

    t.ambient = "REPLY"
    begin = len(t.calls)
    t.emit(A, 5, "大家一起聊聊周末的活动吧。", at=False)
    until(lambda: any(e["type"] == "qq_delivery" for e in t.events(A)), "本群回复获 OneBot 回执")
    calls = t.calls[begin:]
    observed = [(n, req) for n, req in calls if n in ("select_reply", "submit_chat_summary", "plan_turn")]
    assert [n for n, _ in observed] == ["select_reply", "submit_chat_summary", "plan_turn"], [n for n, _ in calls]
    ambient, summary, planner = [req for _, req in observed]
    assert f"{GROUP}3" in request_text(ambient) and GROUP_SUMMARY not in request_text(ambient)
    assert GROUP in request_text(summary) and GROUP_SUMMARY in request_text(planner)
    assert len(t.sent) == 1 and t.sent[0]["action"] == "send_group_msg"
    assert all("仅观察历史" not in request_text(req) for _, req in calls)


def context(chain):
    return dict(character_id="trial", actor_id="ORCHESTRATOR", role="ORCHESTRATOR",
                session_id="console:" + chain, conversation_id="console:" + chain)


def cited_history(t, chain):
    """从同受众原始记录产生有依据的网页回复；可识别的本地措辞只出现在已送达答复中。"""
    source = t.mem("ingest", dict(context=context(chain), source_instance="neuralcloud-java",
                   event_id=chain + "-original", kind="observation", content=f"验票口令是{OLD}。{RAW_ONLY}。",
                   occurred_at=datetime.now(timezone.utc).isoformat()))
    assert source["status"] == "DURABLE"
    first = t.web(chain, QUERY)
    assert OLD in wire(first) and LOCAL in wire(first), first
    for i in range(3):
        t.web(chain, "当轮准备材料" + str(i) + "甲" * 1080)
    start = len(t.calls)
    t.web(chain, "往后接着聊。")
    step = t.calls[start:]
    summary, = [req for n, req in step if n == "submit_chat_summary"]
    assert LOCAL in request_text(summary) and OLD in request_text(summary)
    assert RAW_ONLY not in request_text(summary), "召回原文不能进入压缩"
    planner, = [req for n, req in step if n == "plan_turn"]
    assert LOCAL + "提到过" in request_text(planner), "应规划时实际使用可复核的提要"
    assert all(OTHER not in request_text(req) for _, req in step)
    assert any(e["type"] == "chat_context_compaction" and e["data"]["status"] == "COMMITTED"
               for e in t.web_events(chain))
    # 使下一轮再次有整理候选，撤权既不能泄漏给压缩模型，也不能回退到被覆盖的旧原文。
    for i in range(2):
        t.web(chain, "追加材料" + str(i) + "乙" * 1050)
    return source


def current_audience(t, correction):
    chain = "replace" if correction else "revoke"
    source = cited_history(t, chain)
    marker = len(t.calls)
    gate = t.arm("after-inbound")
    try:
        later = t.worker.submit(t.web, chain, CHECK)
        gate.wait()
        assert any(e["type"] == "operator_message" and e["data"].get("text") == CHECK for e in t.web_events(chain))
        if correction:
            revised = t.mem("corrections", dict(context=context(chain), source_instance="neuralcloud-java",
                            event_id=chain + "-revision", operation="REPLACE", record_id=source["record_id"],
                            expected_revision=source["revision"], content=f"验票口令改为{NEW}。", reason="登记更正"))
            assert revised["revision"] == source["revision"] + 1
        else:
            # 仅调整原受众的 CITE 读取结果；Memory 原文版本、有效状态和变更水位均未改变。
            t.denied = ("console:" + chain, source["record_id"])
    finally:
        gate.release.set()
    delivered = later.result(timeout=15)
    worker = [req for name, req in t.calls[marker:] if name in ("submit_chat_summary", "plan_turn", "reply")]
    assert worker
    assert all(OLD not in request_text(req) and LOCAL not in request_text(req) for req in worker)
    assert OLD not in wire(delivered) and LOCAL not in wire(delivered)
    if correction:
        assert NEW in request_text(worker[0]), "入站后发生的更正须抵达本轮首个 Worker 模型请求"
    else:
        assert t.denied_reads and all(req.get("use") == "CITE" for req in t.denied_reads)
        current, = t.mem("read", dict(context=context(chain), kind="HISTORY", use="CITE",
                         targets=[dict(record_id=source["record_id"])]))["items"]
        assert current["ref"]["revision"] == source["revision"]
        t.denied = None


def run(t, case):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.harness()
    if case in ("all", "qq"):
        qq_participation(t)
    if case != "qq":
        t.web("other-audience", OTHER)
        if case in ("all", "revocation"):
            current_audience(t, correction=False)
        if case in ("all", "correction"):
            current_audience(t, correction=True)
    print("PASS", case)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("harness", "memory", "output"):
        parser.add_argument(argument, type=Path)
    parser.add_argument("--case", choices=("all", "qq", "revocation", "correction"), default="all")
    args = parser.parse_args()
    trial = ChannelTrial(args)
    try:
        run(trial, args.case)
    finally:
        trial.close()
