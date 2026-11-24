"""使用隔离网页、QQ 私聊和 Memory v2，验收滚动历史在同一轮的授权与真实发送回执。"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from chat_compaction_channels import CHECK, LOCAL, OLD, QUERY, ChannelTrial, cited_history, request_text, wire
from qq_observe import ACCOUNT, call, until


PEER = 67890
PRIVATE = f"c2c:onebot:{ACCOUNT}:{PEER}"
CHAIN = f"qq-private-{ACCOUNT}-{PEER}"
SUMMARY = LOCAL + "提到过"


class DeliveryTrial(ChannelTrial):
    def __init__(self, args):
        self.drop_next_receipt = False
        super().__init__(args)

    def route(self, path, body):
        response = super().route(path, body)
        if path == "/v1/chat/completions" and CHECK in (body["messages"][-1].get("content") or ""):
            names = [item["function"]["name"] for item in body.get("tools", [])]
            # 模型已经生成这一请求的完整结果。释放前可独立改变下一次 CITE 核验的结果。
            if names == ["plan_turn"]:
                self.checkpoint("planned")
            if not names:
                self.checkpoint("drafted")
        return response

    def onebot(self, ws):
        trial = self

        class Receiver:
            request = ws.request
            send = ws.send

            def __iter__(self):
                # 只在设备收到待测试的真实发送帧后断开，使 Harness 确实没有这次发送的回执。
                for frame in ws:
                    command = json.loads(frame)
                    if command["action"] == "get_group_member_info":
                        params = command["params"]
                        assert params["user_id"] == PEER and params["group_id"] in trial.roster
                        ws.send(wire(dict(status="ok", retcode=0, echo=command["echo"],
                                     data=dict(group_id=params["group_id"], user_id=PEER))))
                        continue
                    if trial.drop_next_receipt and command["action"] == "send_private_msg":
                        trial.drop_next_receipt = False
                        trial.sent.append(command)
                        ws.close()
                        return
                    yield frame

        super().onebot(Receiver())

    def send_private(self, number, text):
        self.ws.send(wire(dict(post_type="message", message_type="private", sub_type="friend", self_id=ACCOUNT,
                     user_id=PEER, message_id=number, message=[dict(type="text", data=dict(text=text))])))

    def private_result(self, number):
        """从指定 QQ 消息标识查看该轮的成功或失败，避免把上一条消息的回执当成本轮结果。"""
        def completed():
            status, data = call(self.hp, "/api/audit/chains/" + CHAIN)
            if status == 404:
                return None
            assert status == 200, (status, data)
            rows = data["events"]
            for i in range(len(rows) - 1, -1, -1):
                if rows[i]["type"] == "qq_message" and str(rows[i]["data"]["messageId"]) == str(number):
                    turn = rows[i:]
                    if any(e["type"] in ("qq_delivery", "qq_delivery_unknown")
                           or e["type"].endswith("_failed") for e in turn):
                        return turn
                    break
            return None
        return until(completed, "QQ 私聊结束 " + str(number))

    def private_turn(self, number, text):
        start = len(self.sent)
        self.send_private(number, text)
        rows = self.private_result(number)
        assert any(e["type"] == "qq_delivery" for e in rows), rows[-1]
        sent, = [c for c in self.sent[start:] if c["action"] == "send_private_msg"]
        assert int(sent["params"]["user_id"]) == PEER
        return sent["params"]["message"][0]["data"]["text"]


def model_boundary(t):
    chain = "after-planning"
    source = cited_history(t, chain)
    start = len(t.calls)
    assert OLD in wire(t.web(chain, CHECK)), "授权有效时，公开 HTTP 出口必须成功回复"
    for name in ("plan_turn", "reply"):
        req, = [req for operation, req in t.calls[start:] if operation == name]
        assert SUMMARY in request_text(req)

    start = len(t.calls)
    gate = t.arm("planned")
    try:
        future = t.worker.submit(call, t.hp, "/api/nodes/conversation/messages", dict(chain=chain, text=CHECK))
        gate.wait()
        last, = [req for name, req in t.calls[start:] if name == "plan_turn"]
        assert SUMMARY in request_text(last), "最后一次规划实际使用了已授权的提要"
        after_plan = len(t.calls)
        t.denied = ("console:" + chain, source["record_id"])
    finally:
        gate.release.set()
    status, delivered = future.result(timeout=12)
    assert status == 200 or status >= 400
    assert all(OLD not in request_text(req) and LOCAL not in request_text(req)
               for _, req in t.calls[after_plan:]), "规划后撤权的材料进入了后续模型"
    assert OLD not in wire(delivered) and LOCAL not in wire(delivered)
    assert t.denied_reads, "授权状态不产生账本变更，只能通过当前受众的读取发现"
    t.denied = None


def private_delivery(t):
    """同一私聊先仅用短历史，再越过水位读取提要。最终草稿写好后撤权，检验实际 OneBot 帧与审计。"""
    ctx = dict(character_id="trial", session_id=PRIVATE, conversation_id=PRIVATE, role="ORCHESTRATOR",
               actor_id="ORCHESTRATOR", qq=dict(account_id=str(ACCOUNT), peer_id=str(PEER)))
    source = t.mem("ingest", dict(context=ctx, source_instance="neuralcloud-java", event_id="private-original",
                   kind="observation", content=f"验票口令是{OLD}。", occurred_at=datetime.now(timezone.utc).isoformat()))
    start = len(t.calls)
    assert LOCAL in t.private_turn(101, QUERY)
    assert OLD in t.private_turn(102, CHECK)
    short = t.calls[start:]
    assert all(name not in ("submit_chat_summary", "select_reply") for name, _ in short)
    assert LOCAL + "写着" in request_text([req for name, req in short if name == "plan_turn"][-1])
    for i in range(3):
        t.private_turn(103 + i, "准备私聊材料" + str(i) + "甲" * 1080)
    start = len(t.calls)
    assert OLD in t.private_turn(106, CHECK)
    long = [(n, req) for n, req in t.calls[start:] if n != "select_route"]
    assert [n for n, _ in long] == ["submit_chat_summary", "plan_turn", "reply"]
    assert LOCAL + "写着" in request_text(long[0][1])
    assert all(SUMMARY in request_text(req) for _, req in long[1:])

    start = len(t.sent)
    called = len(t.calls)
    reads = len(t.denied_reads)
    gate = t.arm("drafted")
    try:
        t.send_private(107, CHECK)
        gate.wait()
        reply, = [req for name, req in t.calls[called:] if name == "reply"]
        assert SUMMARY in request_text(reply), "回复生成时该受众仍获准读取提要"
        assert not any(c["action"] == "send_private_msg" for c in t.sent[start:])
        t.denied = (PRIVATE, source["record_id"])
    finally:
        gate.release.set()
    rows = t.private_result(107)
    sent = [c for c in t.sent[start:] if c["action"] in ("send_private_msg", "send_group_msg")]
    assert all(OLD not in wire(c) and LOCAL not in wire(c) for c in sent), "旧标记被发给 QQ"
    if not sent:
        assert all(e["type"] not in ("qq_delivery", "qq_delivery_unknown") for e in rows), rows[-1]
    assert len(t.denied_reads) > reads
    t.denied = None


def lost_receipt(t):
    """已经在 OneBot 收到正文、随后丢失回执的对照，须保留发送状态不明。"""
    start = len(t.sent)
    t.drop_next_receipt = True
    t.send_private(108, "用一句话打个招呼。")
    rows = t.private_result(108)
    sent, = [c for c in t.sent[start:] if c["action"] == "send_private_msg"]
    assert int(sent["params"]["user_id"]) == PEER and sent["params"]["message"][0]["data"]["text"]
    assert sum(e["type"] == "qq_delivery_unknown" for e in rows) == 1
    assert all(e["type"] != "qq_delivery" for e in rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    parser.add_argument("--case", choices=("all", "model", "private", "unknown"), default="all")
    args = parser.parse_args()
    trial = DeliveryTrial(args)
    try:
        memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
        trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
        trial.harness()
        for name, case in (("model", model_boundary), ("private", private_delivery), ("unknown", lost_receipt)):
            if args.case in ("all", name):
                case(trial)
        print("PASS", args.case)
    finally:
        trial.close()
