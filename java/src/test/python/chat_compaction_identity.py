"""回环双 JVM 验收 ROLLING 下的本地历史延续、共享账号目录用途及已送达称呼的回放边界。"""
import argparse
from datetime import datetime, timezone
from pathlib import Path

from chat_compaction_channels import CHECK, LOCAL, OLD, QUERY, ChannelTrial, context, request_text, wire
from conversation_responsibilities import tool
from qq_observe import call, until


DESK = "identity-purpose"
LOOKUP = "请核对共享目录里登记的称呼。"
NAME = "合成琉璃灯七号"
ACCOUNT = "46573"
DIRECTORY_REPLY = f"目录核对结果，账号 {ACCOUNT} 的称呼是{NAME}。"
ORDINARY = "合成核验凭条，不含目录称呼。"


class IdentityTrial(ChannelTrial):
    def __init__(self, args):
        super().__init__(args)
        self.memory_requests = []
        self.revoke_after_lookup = None
        self.revoked = None
        self.denied_directory_reads = []

    def route(self, path, body):
        response = super().route(path, body)
        if path in ("/v2/read", "/v2/recall", "/v2/ingest"):
            self.memory_requests.append((path, body))
        if path == "/v2/recall" and body.get("kind") == "IDENTITY" and self.revoke_after_lookup:
            # 此次目录召回已经取得真实服务的结果，从此刻起只收回该用途的再次交付权。
            self.revoked, self.revoke_after_lookup = self.revoke_after_lookup, None
        if path == "/v2/read" and body.get("kind") == "IDENTITY" and self.revoked:
            for i, target in enumerate(body["targets"]):
                if target["record_id"] == self.revoked:
                    self.denied_directory_reads.append(body)
                    response[1]["items"][i] = dict(record_id=self.revoked, status="NOT_FOUND")
        if path != "/v1/chat/completions":
            return response
        names = [t["function"]["name"] for t in body.get("tools", [])]
        current = body["messages"][-1].get("content") or ""
        if names == ["plan_turn"] and LOOKUP in current:
            return tool("plan_turn", {"kind": "IDENTITY", "query": ""})
        if not names and NAME in request_text(body) and (LOOKUP in current or CHECK in current):
            # 真实目录查询应当能送达。普通重放一旦混入旧称呼，也会在最终网页回复中显现。
            return 200, {"model": "trial", "choices": [
                {"message": {"content": DIRECTORY_REPLY}, "finish_reason": "stop"}]}
        return response


def run(t):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    health = t.mem("health")
    assert "identity_lookup" in health["capabilities"] and not health["session_isolation"]
    t.harness()
    now = datetime.now(timezone.utc).isoformat()
    # 先交付纯本会话材料。写入其他渠道的记录后，通用 HISTORY 查证可能同时命中跨会话来源。
    t.mem("ingest", dict(context=context(DESK), source_instance="neuralcloud-java", event_id="desk-history",
                         kind="observation", content=f"验票口令是{OLD}。", occurred_at=now))
    assert LOCAL in wire(t.web(DESK, QUERY))
    group = "group:onebot:12345:23456"
    source = t.mem("ingest", dict(context=dict(character_id="trial", session_id=group, conversation_id=group,
                          role="ORCHESTRATOR", actor_id="ORCHESTRATOR"), source_instance="neuralcloud-java",
                          event_id="directory-source", kind="observation", content=ORDINARY, privacy="private",
                          identity=dict(account_id=ACCOUNT, name=NAME), occurred_at=now))
    ref = dict(record_id=source["record_id"], revision=source["revision"])
    reader = dict(context(DESK), actor_id="CHAT_WORKER", role="CHAT_WORKER")
    before = len(t.memory_requests)
    assert DIRECTORY_REPLY in wire(t.web(DESK, LOOKUP)), "实际目录操作在获准后应正确送达"
    tagged = dict(ref, purpose="IDENTITY")

    def saved_identity_reply():
        return any(path == "/v2/ingest" and req.get("content") == DIRECTORY_REPLY
                   and tagged in req.get("source_refs", []) for path, req in t.memory_requests[before:])

    until(saved_identity_reply, "已送达称呼按 IDENTITY 原用途入账")
    queries = [(path, req) for path, req in t.memory_requests[before:] if req.get("kind") == "IDENTITY"]
    assert any(path == "/v2/recall" and req["context"]["conversation_id"] == "console:" + DESK for path, req in queries)
    assert any(path == "/v2/read" and "use" not in req and ref["record_id"] in
               [target["record_id"] for target in req["targets"]] for path, req in queries)
    assert any(e["type"] == "console_response_written" and tagged in e["data"].get("source_refs", [])
               for e in t.web_events(DESK)), "渠道回执须保留原用途"

    # 正反例共用相同受众、短尾部和长前缀。普通模型请求均可见已送达的本地历史，目录称呼只在上面的获准操作出现。
    first = len(t.calls)
    short_output = t.web(DESK, CHECK)
    short = [(name, req) for name, req in t.calls[first:] if name != "select_route"]
    assert [name for name, _ in short] == ["plan_turn", "reply"]
    assert all(LOCAL + "写着" in request_text(req) for _, req in short)
    assert OLD in wire(short_output) and NAME not in wire(short_output)

    for i in range(3):
        t.web(DESK, "同一会话的其他资料" + str(i) + "甲" * 1080)
    started = len(t.calls)
    long_output = t.web(DESK, CHECK)
    long = [(name, req) for name, req in t.calls[started:] if name != "select_route"]
    assert [name for name, _ in long] == ["submit_chat_summary", "plan_turn", "reply"]
    assert LOCAL + "写着" in request_text(long[0][1])
    assert all(LOCAL + "提到过" in request_text(req) for _, req in long[1:])
    assert OLD in wire(long_output) and NAME not in wire(long_output)
    assert all(NAME not in request_text(req) and ACCOUNT not in request_text(req)
               for _, req in t.calls[first:]), "目录称呼进入了普通原文尾部、压缩输入或后续模型"

    # 目录用途的受众复核失败时，同版本的普通原文仍可读，网页不能借其交付目录称呼。
    t.revoke_after_lookup = ref["record_id"]
    _, delivery = call(t.hp, "/api/nodes/conversation/messages", dict(chain=DESK, text=LOOKUP))
    assert t.denied_directory_reads and all("use" not in req for req in t.denied_directory_reads)
    assert NAME not in wire(delivery)
    status, check = call(t.proxy.server_port, "/v2/read", dict(context=reader, kind="HISTORY", use="CITE", targets=[ref]),
                         token=t.token)
    assert status == 200
    ordinary, = check["items"]
    assert ordinary["ref"] == ref and ordinary["text"] == ORDINARY


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    trial = IdentityTrial(parser.parse_args())
    try:
        run(trial)
        print("PASS 本地 HISTORY 延续、共享目录按 IDENTITY 送达、短尾与长前缀排除目录称呼")
    finally:
        trial.close()
