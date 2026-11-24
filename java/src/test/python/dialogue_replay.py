"""用正式网页、QQ 和 Memory v2 核对重启后的模型输入。--previous-harness 可指定产生旧审计的版本。"""
import argparse
import json
from pathlib import Path

from conversation_responsibilities import tool
from qq_observe import A, ACCOUNT, Trial, call, context, until
from websockets.exceptions import ConnectionClosed


QQ = f"qq-private-{ACCOUNT}-67890"
WEB = "dialogue-replay"
SOURCE_INSTANCE = "neuralcloud-java"
GREET = "你好。"


class ReplayTrial(Trial):
    """固定模型的选择与答复，记录实际请求；Memory 请求仍由独立的正式服务处理。"""

    def __init__(self, args):
        self.inputs = []
        self.origins = {}
        self.query = ""
        self.answer = ""
        self.number = 100
        super().__init__(args)

    def onebot(self, ws):
        """隔离私聊账号没有群；只对实收的私聊发送发放唯一消息号。"""
        assert ws.request.headers["Authorization"] == "Bearer " + self.token
        try:
            for frame in ws:
                command = json.loads(frame)
                if command["action"] == "get_login_info":
                    data = {"user_id": ACCOUNT}
                elif command["action"] == "get_group_list":
                    self.ws = ws
                    data = []
                else:
                    assert command["action"] == "send_private_msg", command
                    self.sent.append(command)
                    data = {"message_id": 90000 + len(self.sent)}
                ws.send(json.dumps(dict(status="ok", retcode=0, echo=command["echo"], data=data)))
        except ConnectionClosed:
            pass  # 重启 Harness 会关闭本次 OneBot 连接。

    def route(self, path, body):
        if path == "/v2/ingest":
            status, receipt = super().route(path, body)
            if status == 200:
                self.origins[receipt["record_id"]] = body["context"]
            return status, receipt
        if path != "/v1/chat/completions":
            return super().route(path, body)
        names = [item["function"]["name"] for item in body.get("tools", [])]
        self.inputs.append((names, body["messages"]))
        if names == ["select_route"]:
            return tool("select_route", {"route": "CONVERSATION"})
        if names == ["plan_turn"]:
            self.checkpoint("replay-plan")
            return tool("plan_turn", {"kind": "HISTORY" if self.query else "REPLY", "query": self.query})
        assert not names, names
        answer = self.answer(body["messages"]) if callable(self.answer) else self.answer
        return 200, {"model": "trial", "choices": [{"finish_reason": "stop", "message": {"content": answer}}]}

    def start_memory(self, name):
        self.memory_process = self.start(name, ["-jar", self.mjar, "--config", self.mc])
        self.ready(lambda: self.mem("health")["status"] == "TEXT_READY", self.memory_process)

    def restart_for_replay(self, harness, isolate=False):
        self.stop_harness()
        if isolate:
            self.memory_process.kill()
            self.memory_process.wait(timeout=10)
            with self.mc.open("a", encoding="utf-8") as config:
                config.write("sessionIsolation=true\n")
            self.start_memory("memory-isolated")
        self.hjar = harness
        self.harness()

    def audit(self, chain):
        status, data = call(self.hp, "/api/audit/chains/" + chain)
        if status == 404:  # OneBot 首条入站抵达前会话尚未创建。
            return []
        assert status == 200, (status, data)
        return data["events"]

    def turn(self, chain, text, answer, query=""):
        """由回环 OneBot 或网页发言，取同轮入站、实际送达和入库的公开审计。"""
        self.inputs.clear()
        self.query, self.answer = query, answer
        if chain == QQ:
            self.number += 1
            before = len(self.sent)
            self.ws.send(json.dumps(dict(post_type="message", message_type="private", sub_type="friend",
                         self_id=ACCOUNT, user_id=67890, message_id=self.number,
                         message=[{"type": "text", "data": {"text": text}}])))
        else:
            status, data = call(self.hp, "/api/nodes/conversation/messages", dict(chain=chain, text=text))
            assert status == 200, (status, data)

        def finished():
            rows = self.audit(chain)
            for i in range(len(rows) - 1, -1, -1):
                event = rows[i]
                if event["type"] in {"qq_message", "operator_message"} and event["data"]["text"] == text:
                    if chain == QQ and event["data"]["messageId"] != self.number:
                        continue
                    batch = rows[i:]
                    for row in batch:
                        assert row["type"] not in {"node_failed", "qq_delivery_failed", "qq_delivery_unknown"}, row
                    if any(row["type"] == "memory_durable" and row["data"]["kind"] == "SELF_EXPRESSION"
                           for row in batch):
                        return batch, rows
                    break

        batch, history = until(finished, "已送达回复的持久回执")
        if chain == QQ:
            sent, = self.sent[before:]
            assert (sent["action"], sent["params"]["user_id"], sent["params"]["message"][0]["data"]["text"]) == (
                "send_private_msg", 67890, answer)
        delivery = "qq_delivery" if chain == QQ else "console_response_written"
        assert any(row["type"] == delivery for row in batch)
        assert any(row["type"] == "chat_worker_reply" and row["data"]["text"] == answer for row in batch)
        return batch, history


def ref(rows, kind):
    data, = [row["data"] for row in rows if row["type"] == "memory_durable" and row["data"]["kind"] == kind]
    return {"record_id": data["recordId"], "revision": data["revision"]}


def audience(t, target, role):
    return dict(t.origins[target["record_id"]], actor_id=role, role=role)


def correct(t, ctx, target, replacement=None):
    """不更改 Harness 审计。用 Memory 的持久更正回执确认这次操作已生效。"""
    body = dict(context=ctx, source_instance=SOURCE_INSTANCE, event_id="change-" + target["record_id"],
                operation="RETRACT" if replacement is None else "REPLACE", reason="回放验收",
                record_id=target["record_id"], expected_revision=target["revision"])
    if replacement is not None:
        body["content"] = replacement
    receipt = t.mem("corrections", body)
    assert receipt["status"] == "DURABLE" and receipt["revision"] == target["revision"] + 1


def check_replay(t, chain, history, visible, forbidden):
    """逐个检查 Chat Worker 规划和生成的角色消息；所有模型收到的完整消息均不得含失效正文。"""
    _, replayed = t.turn(chain, GREET, "下午好。")
    # 逐事件核对旧的真实送达、写入回执与原始正文仍可从正式审计接口读出。
    assert replayed[:len(history)] == history
    plans = [messages for names, messages in t.inputs if names == ["plan_turn"]]
    replies = [messages for names, messages in t.inputs if not names]
    assert plans and replies, "须实际抵达 Chat Worker 的规划与生成出口"
    for names, messages in t.inputs:
        raw = json.dumps(messages, ensure_ascii=False)
        for text in forbidden:
            assert text not in raw, (chain, names, "模型收到失效正文", text)
    for messages in [*plans, *replies]:
        for role, text in visible:
            assert any(row["role"] == role and text in row["content"] for row in messages), (
                chain, role, "未按原说话者完整回放", len(text), text[:16], text[-16:])


def current_versions(t, harness):
    """正例：QQ 已送达的自述与网页入站各更正一次，各自同轮的另一方仍应原样可见。"""
    qq_input, qq_old = "信封上贴着银杏。", "我把蓝印章放在北架。"
    # 精确到 Memory 原文窗口边界；开头和末尾的非 BMP 字符都各算一个码点。
    prefix, suffix = "我把蓝印章放在南架。🦉", "读到流星尾标🌠"
    qq_new = prefix + "页" * (2048 - len(prefix) - len(suffix)) + suffix
    assert len(qq_new) == 2048
    web_old, web_new = "我在读苍鹭册。", "我在读翠鸟册。"
    # 两渠道的自述原本同文，仅 QQ 的自述被更正，网页自述须保留自己的授权和版本。
    qq, qq_history = t.turn(QQ, qq_input, qq_old)
    web, web_history = t.turn(WEB, web_old, qq_old)
    for target, text in ((ref(qq, "SELF_EXPRESSION"), qq_new), (ref(web, "OBSERVATION"), web_new)):
        correct(t, audience(t, target, "ORCHESTRATOR"), target, text)
    t.restart_for_replay(harness)
    check_replay(t, QQ, qq_history, [("user", qq_input), ("assistant", qq_new)], [qq_old])
    check_replay(t, WEB, web_history, [("user", web_new), ("assistant", qq_old)], [web_old])


def retract_after_planning(t, target, text):
    """首个规划已看见这条历史。更正回执先于模型返回，只允许后续调用使用重新获准的正文。"""
    question = "早上好。"
    t.inputs.clear()
    t.query = ""
    # 仅在生成请求还带着旧自述时复述，渠道回包即可揭示这次引用是否实际送达。
    t.answer = lambda messages: text if text in json.dumps(messages, ensure_ascii=False) else "早安。"
    gate = t.arm("replay-plan")
    later = t.worker.submit(call, t.hp, "/api/nodes/conversation/messages", dict(chain=WEB, text=question))
    try:
        gate.wait()
        names, messages = t.inputs[-1]
        assert names == ["plan_turn"] and any(row["role"] == "assistant" and text in row["content"]
                                               for row in messages), "规划必须先收到仍有效的自述"
        first_unseen = len(t.inputs)
        correct(t, audience(t, target, "ORCHESTRATOR"), target)
    finally:
        gate.release.set()
    status, result = later.result(timeout=15)
    following = t.inputs[first_unseen:]
    for names, messages in following:
        assert text not in json.dumps(messages, ensure_ascii=False), (WEB, names, "规划后已撤回的正文又进入了模型", text)
    if status == 200:
        assert any(not names for names, _ in following), "正常回复应有本轮生成请求"
        assert result["type"] == "chat_worker_reply" and text not in result["data"]["text"], result
    else:
        assert status >= 400 and result.get("error"), (status, result)
        events = t.audit(WEB)
        start = next(i for i, row in enumerate(events)
                     if row["type"] == "operator_message" and row["data"]["text"] == question)
        assert any(row["type"] == "node_failed" for row in events[start:])
        assert not any(row["type"] == "console_response_written" for row in events[start:])


def unavailable_versions(t, harness):
    """反例：QQ 已撤回自述、来源失效自述、越权自述及网页已撤回入站均从模型输入剔除。"""
    kept_input, withdrawn_reply = "盒子上印着松树。", "我曾经把这称作白桦密语。"
    withdrawn_input, kept_reply = "我临时把它叫作海星证章。", "我收藏过一张梧桐书票。"
    qq, qq_history = t.turn(QQ, kept_input, withdrawn_reply)
    web, web_history = t.turn(WEB, withdrawn_input, kept_reply)
    # 两次已送达的回答各用了另一个会话的可引用来源。一个来源将被修改，另一个仅会收回共享。
    questions = ["青瓷门票放在哪里？", "蓝杉钥匙放在哪里？"]
    answers = ["我记得那个抽屉挂着竹牌。", "我记得那只木箱系着橘色带子。"]
    seeds, deliveries = [], []
    for topic, question, answer in zip(("青瓷门票", "蓝杉钥匙"), questions, answers):
        seed = t.mem("ingest", dict(context=context(A), source_instance=SOURCE_INSTANCE, event_id=topic,
                                    kind="observation", content=topic + "放在写字桌旁。", occurred_at="2025-10-20T00:00:00Z"))
        rows, qq_history = t.turn(QQ, question, answer, topic)
        delivered, = [row for row in rows if row["type"] == "qq_delivery"]
        assert any(source["record_id"] == seed["record_id"] for source in delivered["data"]["source_refs"])
        seeds.append(seed)
        deliveries.append(ref(rows, "SELF_EXPRESSION"))
    for target in (ref(qq, "SELF_EXPRESSION"), ref(web, "OBSERVATION")):
        correct(t, audience(t, target, "ORCHESTRATOR"), target)
    correct(t, context(A), seeds[0], "青瓷门票已经移交。")
    t.restart_for_replay(harness, isolate=True)
    # 两次自述在 QQ 当前受众下均不可引用；直接从 Memory 核对造出的边界，保证不会因缺少测试数据而误过。
    states = t.mem("read", dict(context=audience(t, deliveries[0], "CHAT_WORKER"), kind="HISTORY", use="CITE",
                        targets=[{"record_id": item["record_id"]} for item in deliveries]))["items"]
    assert states[0]["status"] == "SOURCE_CHANGED" and states[1]["status"] == "NOT_FOUND", states
    forbidden = [withdrawn_reply, withdrawn_input, *answers]
    check_replay(t, QQ, qq_history, [("user", text) for text in [kept_input, *questions]], forbidden)
    check_replay(t, WEB, web_history, [("assistant", kept_reply)], forbidden)
    retract_after_planning(t, ref(web, "SELF_EXPRESSION"), kept_reply)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--previous-harness", type=Path, help="使用指定旧版生成真实渠道审计与回执")
    parser.add_argument("--case", choices=["current", "unavailable"])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for case, check in (("current", current_versions), ("unavailable", unavailable_versions)):
        if args.case and case != args.case:
            continue
        trial = ReplayTrial(argparse.Namespace(harness=args.previous_harness or args.harness, memory=args.memory,
                                               output=args.output / case))
        try:
            trial.start_memory("memory")
            trial.harness()
            check(trial, args.harness.resolve())
            print("PASS", case)
        finally:
            trial.close()
