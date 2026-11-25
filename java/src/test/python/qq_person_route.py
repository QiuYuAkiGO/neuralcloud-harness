"""从实际 QQ 私聊与网页入口验收 select_route 的条件协议和原生工具校验。"""
import argparse
import unittest
from pathlib import Path

from conversation_responsibilities import tool
from qq_action_lookup import PEER, ActionTrial
from qq_observe import call, until


AUTHOR, TOPIC = "572816", "纸样布展"
FOCI = {"PERSON", "PERSON_OR_TOPIC", "MENTION", "DEFAULT"}
CAPABILITY = "qq_author_history"
ROUTE = {"route": "CONVERSATION"}


def decision(focus, author="", topic=""):
    return dict(ROUTE, memory_focus=focus, author_ref=author, query=topic)


class RouteTrial(ActionTrial):
    """按案例原样提交模型选择，即使它与 Harness 发出的工具声明不符。"""
    def __init__(self, args):
        super().__init__(args)
        self.requests = []
        self.selection = ROUTE
        self.hide_capability = False
        self.roster = []
        self.plan = {"kind": "REPLY", "query": ""}

    def route(self, path, body):
        if path == "/v2/health" and self.hide_capability:
            status, health = super().route(path, body)
            health["capabilities"].remove(CAPABILITY)
            return status, health
        if path == "/v1/chat/completions" and any(
                t["function"]["name"] == "select_route" for t in body.get("tools", [])):
            self.requests.append(body)
            return tool("select_route", self.selection)
        return super().route(path, body)


class PersonRouteCases(unittest.TestCase):
    def setUp(self):
        t = self.t = RouteTrial(argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory,
                                                    output=ARGS.output / self._testMethodName))
        self.addCleanup(t.proxy.server_close)
        self.addCleanup(t.close)
        memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])

        def author_ready():
            health = t.mem("health")
            return health["status"] == "TEXT_READY" and CAPABILITY in health["capabilities"]

        t.ready(author_ready, memory)
        t.harness()
        self.number = 481500

    def private(self, text, selection):
        """只投递一次入站。用本轮审计的失败或回复持久回执作为终点。"""
        t = self.t
        t.requests.clear()
        t.plans.clear()
        t.selection = selection
        self.number += 1
        t.private(self.number, text)

        def terminal():
            events = t.audit_events("private", PEER)
            start = next((i for i, e in enumerate(events) if e["type"] == "qq_message"
                          and e["data"]["messageId"] == self.number), len(events))
            return next((e for e in events[start:] if e["type"] == "node_failed"
                         or e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"), None)

        return until(terminal, "本次私聊分流的终点")

    def check_schema(self, fields):
        """唯一一次分流应强制同名工具，逐字段核实类型、必选性与分类可选项。"""
        self.assertEqual(1, len(self.t.requests), "每轮应只有一次 select_route 请求")
        request, = self.t.requests
        self.assertEqual(["select_route"], [entry["function"]["name"] for entry in request["tools"]])
        self.assertEqual({"type": "function", "function": {"name": "select_route"}}, request["tool_choice"])
        schema = request["tools"][0]["function"]["parameters"]
        self.assertEqual(fields, set(schema["properties"]))
        self.assertEqual(fields, set(schema["required"]))
        for name in fields:
            self.assertEqual("string", schema["properties"][name]["type"], name)
        if "memory_focus" in fields:
            self.assertEqual(FOCI, set(schema["properties"]["memory_focus"]["enum"]))
        return "\n".join(m.get("content") or "" for m in request["messages"])

    def test_private_decisions_share_one_route_and_can_refer_to_the_last_user_topic(self):
        """正例。四种合法选择各占一回；第二回的作者仅在同私聊上一条实质问题中出现。"""
        for text, choice in [
            (f"说到{TOPIC}，你对{AUTHOR}的印象怎样？", decision("PERSON_OR_TOPIC", AUTHOR, TOPIC)),
            ("再查一下记忆。", decision("PERSON", AUTHOR)),
            ("是哪位提过深灰色的画夹？", decision("MENTION", topic="深灰色的画夹")),
            ("早上好！", decision("DEFAULT")),
        ]:
            with self.subTest(text=text):
                end = self.private(text, choice)
                material = self.check_schema(set(choice))
                self.assertIn(text, material)
                if choice["author_ref"]:
                    self.assertIn(choice["author_ref"], material)
                self.assertEqual("memory_durable", end["type"], end)

    def test_invalid_private_choices_stop_at_router_and_legacy_scopes_keep_one_field(self):
        """反例。错误字段或作者来源不可进入规划；网页和未协商能力的 QQ 私聊只提交业务路由。"""
        question = f"{AUTHOR}本人讲过哪些有关{TOPIC}的事？"
        person = decision("PERSON", AUTHOR, TOPIC)
        for selection in [
            ROUTE, dict(person, memory_focus="IDENTITY"), dict(person, query=12),
            dict(person, qq_sender_id=AUTHOR), decision("PERSON"), decision("PERSON", "林间新客"),
            decision("PERSON_OR_TOPIC", "林间新客"), decision("MENTION", AUTHOR, TOPIC),
            decision("MENTION"), decision("DEFAULT", topic=TOPIC),
        ]:
            with self.subTest(selection=selection):
                end = self.private(question, selection)
                self.assertEqual("node_failed", end["type"], end)
                self.assertRegex(end["data"]["detail"], r"select_route|memory_focus|author_ref|query|分流|作者|提及")
                self.assertFalse(self.t.plans, "不合格的分流不能开始 Chat Worker 规划")

        self.t.selection = ROUTE
        self.t.requests.clear()
        status, result = call(self.t.hp, "/api/nodes/conversation/messages", {"chain": "route-web", "text": question})
        self.assertEqual(200, status, result)
        self.check_schema(set(ROUTE))
        self.t.stop_harness()
        self.t.hide_capability = True
        self.t.harness()
        end = self.private(question, ROUTE)
        self.check_schema(set(ROUTE))
        self.assertEqual("memory_durable", end["type"], end)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
