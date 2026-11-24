"""用两个正式 JVM、回环 OneBot 和受控模型验收回复前的自动记忆查证。"""
import argparse
import json
import re
import unittest
from datetime import datetime
from pathlib import Path

from conversation_responsibilities import tool
from qq_observe import ACCOUNT, Trial, call, until
from websockets.exceptions import ConnectionClosed


PEER = 67890
PRIVATE = f"c2c:onebot:{ACCOUNT}:{PEER}"
CREATOR = "绀青六码"
SENDER_NAME = "琥珀九号"
FACT = f"经运营确认，{CREATOR}是 Misty 的唯一创造者。"
QUESTION = "你的创造者是谁？"
FOLLOWUP = "再查一下记忆。"
GREETING = "你好！"
WRONG_NAME = re.compile(rf"(?:Misty|小雾|我)(?:的(?:名字|称呼)是|叫|是)\s*[「“]?{SENDER_NAME}")


class RecallTrial(Trial):
    """只在服务边界记录 Memory 回包和模型请求，各次查询与渠道回复均由 Harness 自己发起。"""

    def __init__(self, args):
        self.packets_seen = []
        self.business_route = "CONVERSATION"
        super().__init__(args)

    def route(self, path, body):
        if path != "/v1/chat/completions":
            status, result = super().route(path, body)
            if path in ("/v2/recall", "/v2/ingest"):
                self.packets_seen.append((path, body, result))
            return status, result

        names = [item["function"]["name"] for item in body.get("tools", [])]
        if names == ["select_route"]:
            return tool("select_route", {"route": self.business_route})
        if names == ["plan_turn"]:
            self.packets_seen.append(("plan", body["messages"], None))
            kind = "ACTION" if self.business_route == "ACTION_REQUEST" else "REPLY"
            return tool("plan_turn", {"kind": kind, "query": ""})
        assert not names, names
        messages = json.dumps(body["messages"], ensure_ascii=False)
        self.packets_seen.append(("draft", body["messages"], None))
        if FACT in messages:
            text = f"{CREATOR}是我的唯一创造者。"
        elif WRONG_NAME.search(messages.replace(" ", "")):
            text = f"我叫{SENDER_NAME}。"
        else:
            text = "我目前没有查到能确认这件事的记录。"
        return 200, {"model": "trial", "choices": [{"finish_reason": "stop", "message": {"content": text}}]}

    def onebot(self, ws):
        """为每次真实渠道发送分配不同的回执号，随后用该次发送的收件人和正文验收。"""
        assert ws.request.headers["Authorization"] == "Bearer " + self.token
        try:
            for frame in ws:
                command = json.loads(frame)
                if command["action"] == "get_login_info":
                    data = {"user_id": ACCOUNT}
                elif command["action"] == "get_group_list":
                    data = []
                    self.ws = ws
                else:
                    assert command["action"] == "send_private_msg", command
                    self.sent.append(command["params"])
                    data = {"message_id": 92000 + len(self.sent)}
                ws.send(json.dumps(dict(status="ok", retcode=0, echo=command["echo"], data=data)))
        except ConnectionClosed:
            pass

    def ask_private(self, text, number):
        start, delivered = len(self.packets_seen), len(self.sent)
        self.ws.send(json.dumps(dict(post_type="message", message_type="private", sub_type="friend",
                                    self_id=ACCOUNT, user_id=PEER, message_id=number,
                                    message=[{"type": "text", "data": {"text": text}}])))
        reply = until(lambda: self.sent[delivered] if len(self.sent) > delivered else None, "QQ 私聊回复")
        assert reply["user_id"] == PEER, reply
        answer = "".join(seg["data"]["text"] for seg in reply["message"])
        # 渠道回执在发送命令之后返回。等该轮写入完成，再发送追问或关闭本次两个 JVM。
        until(lambda: any(kind == "/v2/ingest" and req["kind"] == "self_expression" and req["content"] == answer
                          and result["status"] == "DURABLE" for kind, req, result in self.packets_seen[start:]),
              "已送达回复持久化")
        return self.packets_seen[start:], answer

    def seed(self, key, scope, text, **fields):
        """运营验收资料走正式入账端点。隔离开关使另一个会话里的同一条关系对本受众不可引用。"""
        ctx = dict(character_id="trial", actor_id="ORCHESTRATOR", role="ORCHESTRATOR",
                   session_id="fixture", conversation_id=scope)
        if scope == PRIVATE:
            ctx["qq"] = dict(account_id=str(ACCOUNT), peer_id=str(PEER))
        return self.mem("ingest", dict(context=ctx, source_instance="neuralcloud-java", event_id=key,
                                      kind="observation", occurred_at="2026-01-01T00:00:00Z",
                                      speaker="运营", content=text, **fields))


class AutoRecallCases(unittest.TestCase):
    def setUp(self):
        """每个案例使用独立空账本、审计目录与回环进程；外部凭据和 QQ 网络不参与。"""
        args = argparse.Namespace(**vars(self.inputs))
        args.output = args.output / self._testMethodName
        self.trial = RecallTrial(args)
        self.addCleanup(self.trial.close)
        with self.trial.mc.open("a", encoding="utf-8") as cfg:
            cfg.write("sessionIsolation=true\n")
        memory = self.trial.start("memory", ["-jar", self.trial.mjar, "--config", self.trial.mc])
        self.trial.ready(lambda: self.trial.mem("health")["status"] == "TEXT_READY", memory)

    def before_plan(self, events, question):
        """取正式入站持久回执与首个规划请求之间的本受众 HISTORY/CITE，并核对话题读取边界。"""
        kinds = [kind for kind, _, _ in events]
        self.assertIn("plan", kinds, "Chat Worker 应收到首次规划请求")
        plan_index = kinds.index("plan")
        ingests = [(i, body) for i, (kind, body, result) in enumerate(events[:plan_index])
                   if kind == "/v2/ingest" and body["content"] == question and result["status"] == "DURABLE"]
        self.assertEqual(len(ingests), 1, "查证应位于本轮发言的持久回执之后")
        index, inbound = ingests[0]
        reads = [(request, result) for kind, request, result in events[index + 1:plan_index]
                 if kind == "/v2/recall" and request["kind"] == "HISTORY" and request.get("use") == "CITE"]
        self.assertTrue(reads, f"首次规划前缺少本轮 HISTORY/CITE 查询：{question}")
        for request, _ in reads:
            ctx = request["context"]
            self.assertEqual((ctx["role"], ctx["conversation_id"]), ("CHAT_WORKER", PRIVATE))
            self.assertEqual(ctx["qq"], dict(account_id=str(ACCOUNT), peer_id=str(PEER)))
            self.assertLessEqual(datetime.fromisoformat(request["before"]), datetime.fromisoformat(inbound["occurred_at"]))
            self.assertIn(request["limit"], range(1, 5))
        return reads, json.dumps(events[plan_index][1], ensure_ascii=False)

    def test_creator_and_elliptical_followup(self):
        """正例。两个业务路由都让规划模型先看到有权引用的关系，同会话的省略追问须重新查到原主题。"""
        t = self.trial
        receipt = t.seed("confirmed-creator", PRIVATE, FACT)
        source = {key: receipt[key] for key in ("record_id", "revision")}
        t.harness()
        for i, (question, route) in enumerate(((QUESTION, "CONVERSATION"), (FOLLOWUP, "ACTION_REQUEST"))):
            with self.subTest(question=question):
                t.business_route = route
                observed, reply = t.ask_private(question, 81001 + i)
                reads, first_plan = self.before_plan(observed, question)
                hits = [(request["query"], item) for request, result in reads for item in result["items"]
                        if item.get("ref") == source and item.get("text") == FACT]
                self.assertTrue(hits, "本轮主题查询应命中事先入账的确认关系")
                if question == FOLLOWUP:
                    self.assertTrue(any(query.strip() and query != FOLLOWUP for query, _ in hits),
                                    "省略追问应使用恢复出的主题命中原关系")
                self.assertIn(FACT, first_plan)
                for kind, messages, _ in observed:
                    if kind == "draft":
                        self.assertIn(FACT, json.dumps(messages, ensure_ascii=False))
                self.assertRegex(reply.replace(" ", ""), rf"{CREATOR}是(?:我|Misty)的唯一创造者|"
                                 rf"(?:我|Misty)的唯一创造者是{CREATOR}")

    def test_greeting_and_private_relation_outside_the_audience(self):
        """反例。网页纯寒暄零主题检索，QQ 发言者有登记称呼，但无权引用其他会话的创造者记录。"""
        t = self.trial
        t.seed("unshared-creator", "console:other", FACT)
        t.seed("sender-name", PRIVATE, SENDER_NAME,
               identity={"account_id": str(PEER), "name": SENDER_NAME})
        t.harness()
        start = len(t.packets_seen)
        status, result = call(t.hp, "/api/nodes/conversation/messages", {"chain": "greeting", "text": GREETING})
        self.assertEqual(status, 200, result)
        greeting = t.packets_seen[start:]
        self.assertTrue(any(kind == "plan" for kind, _, _ in greeting))
        self.assertFalse(any(kind == "/v2/recall" and req["kind"] == "HISTORY" for kind, req, _ in greeting))

        observed, reply = t.ask_private(QUESTION, 82001)
        reads, first_plan = self.before_plan(observed, QUESTION)
        self.assertTrue(all(result["assessment"]["state"] == "NOT_FOUND" and not result["items"]
                            for _, result in reads))
        self.assertNotIn(CREATOR, first_plan)
        self.assertNotRegex(first_plan.replace(" ", ""), WRONG_NAME)
        for kind, messages, _ in observed:
            if kind == "draft":
                self.assertNotIn(CREATOR, json.dumps(messages, ensure_ascii=False))
        self.assertNotIn(CREATOR, reply)
        self.assertNotRegex(reply.replace(" ", ""), WRONG_NAME)
        self.assertTrue(any(term in reply for term in ("没有", "没查到", "未", "无法", "不能确认", "不确定")), reply)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    AutoRecallCases.inputs = parser.parse_args()
    unittest.main(argv=[__file__])
