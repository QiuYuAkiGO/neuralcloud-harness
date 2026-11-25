"""通过真实 QQ、网页、模型和 Memory 协议验收作者补查与能力协商。"""
import argparse
import unittest
from pathlib import Path

from conversation_responsibilities import tool
from dialogue_replay import QQ, SOURCE_INSTANCE, ReplayTrial
from qq_observe import A, ACCOUNT, context


FIRST, SECOND = "543261", "543262"
NAME, TOPIC = "竹帆七号", "排练"
FIRST_QUOTE, SECOND_QUOTE = "排练时我负责合唱。", "排练时我负责伴奏。"
QUESTION = f"你怎么看{FIRST}呢？"
CONFLICT = "两项承诺只能兑现一个时，该怎么取舍？"
ANSWER = "我看过相关记录了。"
CAPABILITY = "qq_author_history"


class PlanningTrial(ReplayTrial):
    """模型对端给出计划，记录正式请求。旧协议场景只从健康响应中去掉新增能力。"""

    def __init__(self, args):
        self.transcript = []
        self.hide_capability = False
        self.plan = self.native = None
        super().__init__(args)

    def route(self, path, body):
        if path == "/v2/health" and self.hide_capability:
            status, health = super().route(path, body)
            health["capabilities"] = [c for c in health["capabilities"] if c != CAPABILITY]
            return status, health
        if path == "/v2/recall" and body["kind"] == "HISTORY":
            self.transcript.append((body["context"]["role"], (body.get("qq_sender_id"), body["query"])))
        if path == "/v1/chat/completions":
            functions = {t["function"]["name"]: t["function"] for t in body.get("tools", [])}
            if "select_route" in functions and self.question == QUESTION:
                return tool("select_route", dict(route="CONVERSATION", memory_focus="PERSON", author_ref=FIRST, query=""))
            for name, choice in (("plan_turn", self.plan), ("memory_call", self.native)):
                if name in functions:
                    self.transcript.append((name, functions[name]["parameters"]))
                    return tool(name, choice)
            if "submit_intent" in functions:
                return tool("submit_intent", dict(assessment="根据已知的排练安排，我目前倾向于按原定承诺行动", impression="",
                            replyIntent="说明我的安排", evidence=[1], needsReply=True))
            if "submit_plan" in functions:
                return tool("submit_plan", {"steps": [dict(id="reply", worker="CHAT_WORKER", dependsOn=[],
                                                       text="说明我的安排")]})
        return super().route(path, body)

    def ask(self, chain, text, plan):
        self.transcript.clear()
        self.question = text
        self.plan = plan
        self.turn(chain, text, ANSWER)
        return self.transcript.copy()


class AuthorPlanCases(unittest.TestCase):
    def setUp(self):
        trial = self.t = PlanningTrial(argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory,
                                           output=ARGS.output / self._testMethodName))
        self.addCleanup(trial.close)
        with trial.mc.open("a", encoding="utf-8") as config:
            config.write("sessionIsolation=false\n")
        trial.start_memory("memory")
        self.quote(FIRST, FIRST_QUOTE)

    def quote(self, author, text):
        """以可见群中的发言入账，两个作者可使用同一主题词。"""
        group = context(A)
        self.t.mem("ingest", dict(context=group, source_instance=SOURCE_INSTANCE, kind="observation",
                   occurred_at="2026-01-01T12:00:00Z", event_id="quote-" + author, content=text,
                   channel=dict(platform="qq", type="qq_group", account_id=str(ACCOUNT), sender_id=author,
                                conversation_id=group["conversation_id"], message_id=author)))

    def assert_tools(self, chain, enabled):
        """从一次承诺冲突进入正式 Master 工具回路，核对能力目录与实际工具请求的协议一致。"""
        self.t.native = dict(kind="HISTORY", query=TOPIC)
        if enabled:
            self.t.native["author_ref"] = FIRST
        events = self.t.ask(chain, CONFLICT, dict(kind="SUBJECT_CONFLICT", query="", conflict="两个承诺同日到期"))
        for name in ("plan_turn", "memory_call"):
            schema, = [value for kind, value in events if kind == name]
            self.assertEqual(enabled, "author_ref" in schema["properties"], name)
            if enabled:
                self.assertEqual("string", schema["properties"]["author_ref"]["type"])
                self.assertNotIn("author_ref", schema["required"])
        self.assertEqual([(FIRST if enabled else None, TOPIC)], [value for kind, value in events if kind == "MASTER"])

    def assert_history(self, author, topic, expected, quote):
        events = self.t.ask(QQ, QUESTION, dict(kind="HISTORY", query=topic, author_ref=author))
        stages = ["plan" if kind == "plan_turn" else value for kind, value in events
                  if kind in ("CHAT_WORKER", "plan_turn")]
        self.assertEqual(expected, stages)
        self.assertTrue(any(quote in message["content"] for names, messages in self.t.inputs if not names
                            for message in messages), "起草模型应收到所查作者的原话")

    def test_supported_private_scope_reuses_the_same_resolved_author(self):
        """正例。确认称呼与原问题中的 QQ 号指向同一人，规划复用本轮材料，原生工具也能按作者查。"""
        self.t.mem("ingest", dict(context=context(A), source_instance=SOURCE_INSTANCE, kind="observation",
                   event_id="confirmed-name", content="运营确认了该账号的称呼。", identity=dict(account_id=FIRST, name=NAME),
                   occurred_at="2026-01-01T12:00:00Z"))
        self.t.harness()
        self.assert_history(NAME, "", [(FIRST, ""), "plan"], FIRST_QUOTE)
        self.assert_tools(QQ, True)

    def test_changed_key_or_unavailable_capability(self):
        """反例。只改变账号或主题都须再次读取，网页与旧协议的私聊只使用原有工具参数。"""
        self.quote(SECOND, SECOND_QUOTE)
        self.t.harness()
        self.assert_history(SECOND, "", [(FIRST, ""), "plan", (SECOND, "")], SECOND_QUOTE)
        self.assert_history(FIRST, TOPIC, [(FIRST, ""), "plan", (FIRST, TOPIC)], FIRST_QUOTE)
        self.assert_tools("author-console", False)
        self.t.stop_harness()
        self.t.hide_capability = True
        self.t.harness()
        self.assert_tools(QQ, False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
