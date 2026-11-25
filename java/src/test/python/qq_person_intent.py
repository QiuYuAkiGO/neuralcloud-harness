"""通过 QQ 入站、两套正式 JVM 和首次规划请求验收模型给出的人物查询意图。"""
import argparse
import re
import unittest
from pathlib import Path

import qq_author_recall as roster
from conversation_responsibilities import tool
from qq_memory_auto_recall import PRIVATE, RecallTrial
from qq_observe import A, ACCOUNT, B


DIRECTORY_NAME, DIRECTORY_ID = "纸舟样例七", "6543219"
MISSING, TOPIC = "薄荷样例塔", "翻谱"
QUOTE = "翻谱前，我先逐页核对了页码。"
NO_QUOTES = (r"(?:未|没有|没|无|零|0).{0,28}(?:发言|原话|言论|本人记录|作者记录)"
             r"|(?:发言|原话|言论|作者历史).{0,24}(?:未找到|没有|为空|零条|0\s*条)")
ASK_WHICH = r"哪(?:一)?[位个]|(?:提供|告诉|给出|确认|给我).{0,16}(?:QQ\s*号|账号|称呼)|具体.{0,10}(?:人|称呼)"
NEEDS_CLARIFICATION = r"澄清|歧义|重名|同名|未能唯一|没有.{0,8}匹配|未(?:匹配|找到|命中)|无法.{0,8}(?:确定|确认|识别|定位)"


def current_material(messages, question):
    """查本轮规划或起草实际收到的材料，排除上一轮助理答复、自带的问题和通用提示。"""
    for i in range(len(messages) - 1, -1, -1):
        row = messages[i]
        if row["role"] == "user" and question in row["content"]:
            return "\n".join(m["content"] for m in messages[i:] if m.get("content")).replace(question, "")
    raise AssertionError(f"模型请求缺少本轮输入 {question}")


class IntentTrial(roster.AuthorTrial):
    """共享已有回环名册。分类结果由模型对端返回，名称解析、范围选择和读数都经过正式 Harness。"""

    def route(self, path, body):
        if path == "/v1/chat/completions":
            names = [entry["function"]["name"] for entry in body.get("tools", [])]
            if names == ["select_route"]:
                self.packets_seen.append(("route", body, None))
                return tool("select_route", self.decision)
            if names == ["plan_turn"]:
                self.packets_seen.append(("plan", body["messages"], None))
                return tool("plan_turn", self.plan)
            if self.ambiguous and not names and hasattr(self, "question"):
                current = current_material(body["messages"], self.question)
                if re.search(NEEDS_CLARIFICATION, current):
                    self.packets_seen.append(("draft", body["messages"], None))
                    return 200, {"choices": [{"finish_reason": "stop", "message": {"content": roster.CLARIFY}}]}
        return RecallTrial.route(self, path, body)

    def ask(self, text, focus, author="", query="", *, plan=None):
        """每次只投递一个私聊事件，复用夹具对 OneBot 送达后的持久回执检查。"""
        self.question = text
        self.decision = dict(route="CONVERSATION", memory_focus=focus, author_ref=author, query=query)
        self.plan = plan or dict(kind="REPLY", query="")
        self.listed_groups.clear()
        sent = len(self.sent)
        events, reply = self.ask_private(text, 731000 + sent)
        assert len(self.sent) == sent + 1, "单轮应只产生一条 QQ 回复"
        return events, reply


class PersonIntentCases(unittest.TestCase):
    def open_trial(self, ambiguous=False):
        args = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory, output=ARGS.output / self._testMethodName)
        t = IntentTrial(args, ambiguous)
        self.addCleanup(t.proxy.server_close)
        self.addCleanup(t.close)
        memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
        t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
        return t

    def before_plan(self, t, events):
        """只在真实分类回包和首次规划之间取 Memory 请求，随后从首次规划核对交付。"""
        order = [stage for stage, _, _ in events]
        start, first = order.index("route"), order.index("plan")
        self.assertLess(start, first)
        reads = [(r, result) for kind, r, result in events[start + 1:first] if kind == "/v2/recall"]
        return reads, current_material(events[first][1], t.question)

    def author_evidence(self, t, events, author, query):
        """授权作者检索须在首次规划前完成，后续阶段复用本轮结果。"""
        reads, material = self.before_plan(t, events)
        requests = [r for kind, r, _ in events if kind == "/v2/recall" and "qq_sender_id" in r]
        matches = [(r, result) for r, result in reads if "qq_sender_id" in r]
        self.assertEqual(len(matches), 1, matches)
        (req, result), = matches
        self.assertEqual(requests, [req])
        self.assertEqual((req["kind"], req["use"], req["query"], req["qq_sender_id"]),
                         ("HISTORY", "CITE", query, str(author)))
        self.assertEqual((req["context"]["role"], req["context"]["conversation_id"]),
                         ("CHAT_WORKER", PRIVATE))
        self.assertEqual(req["context"]["qq"], dict(account_id=str(ACCOUNT), peer_id=str(roster.PEER)))
        return result["items"], material

    def test_explicit_speaker_and_topic_delivered_before_first_plan(self):
        """正例。口语转述的对象与主题来自分类，规划再查同一对象时仍只读取一遍。"""
        t = self.open_trial()
        receipt = roster.AuthorRecallCases.seed_quote(t, roster.AUTHOR, 731099, "2026-01-17T02:37:48Z", QUOTE)
        t.answers = {QUOTE: "这位之前提到要逐页核对页码。"}
        t.harness()
        question = f"说起{TOPIC}，把{roster.NAME}那回的说法捋一遍呗。"
        events, reply = t.ask(question, "PERSON", roster.NAME, TOPIC,
                              plan=dict(kind="HISTORY", author_ref=roster.NAME, query=TOPIC))
        items, material = self.author_evidence(t, events, roster.AUTHOR, TOPIC)
        self.assertTrue(any(item["ref"] == {k: receipt[k] for k in ("record_id", "revision")}
                            and item["text"] == QUOTE for item in items))
        self.assertIn(QUOTE, material)
        self.assertEqual(t.answers[QUOTE], reply)

    def test_unique_account_with_no_utterances_still_delivers_identity_and_source(self):
        """边界。目录称呼与共同群昵称各自唯一；两轮都没有本人原话，规划仍应看见身份依据。"""
        t = self.open_trial()
        t.seed("directory-only", PRIVATE, "样例称呼由运营确认。", identity=dict(account_id=DIRECTORY_ID, name=DIRECTORY_NAME))
        t.harness()
        for name, account, focus, source in (
                (DIRECTORY_NAME, DIRECTORY_ID, "PERSON", r"目录|确认称呼|已登记|IDENTITY"),
                (roster.NAME, str(roster.AUTHOR), "PERSON_OR_TOPIC", r"共同群|同群|群(?:成员|名片|昵称|名册)|OneBot")):
            with self.subTest(name=name):
                t.answers = {account: f"{name}对应账号{account}，目前没有可引用的本人原话。"}
                events, reply = t.ask(f"我们来聊聊{name}吧。", focus, name)
                items, material = self.author_evidence(t, events, account, "")
                self.assertEqual([], items)
                for token in (name, account):
                    self.assertIn(token, material)
                self.assertRegex(material, source)
                self.assertRegex(material, NO_QUOTES)
                self.assertIn(account, reply)
                self.assertRegex(reply, NO_QUOTES)

    def test_explicit_missing_or_tied_accounts_only_clarify(self):
        """反例。有同名正文也不能回答对方说过什么；明确问人未命中及两种意图重名都要澄清。"""
        t = self.open_trial(ambiguous=True)
        decoy = f"样例公告并列写有{MISSING}和{roster.NAME}，交接箱涂成青色。"
        t.seed("names-in-other-text", PRIVATE, decoy)
        t.answers = {decoy: "交接箱是青色。"}
        t.harness()
        for focus, name in (("PERSON", MISSING), ("PERSON", roster.NAME), ("PERSON_OR_TOPIC", roster.NAME)):
            with self.subTest(focus=focus, name=name):
                events, reply = t.ask(f"跟我讲一讲{name}吧。", focus, name)
                reads = [r for kind, r, _ in events if kind == "/v2/recall"]
                self.assertTrue(any(r["kind"] == "IDENTITY" and r["query"] == name for r in reads))
                self.assertEqual({A, B}, set(t.listed_groups))
                self.assertFalse(any(r["kind"] == "HISTORY" for r in reads), reads)
                self.assertRegex(reply, ASK_WHICH)
                self.assertNotIn("青色", reply)

    def test_unresolved_object_and_text_mentions_use_keyword_history(self):
        """正例。待辨对象没有账号时可回到主题；点名查谁提过某词则直接查正文。"""
        t = self.open_trial()
        facts = {MISSING: f"{MISSING}的模型使用浅绿封套。", roster.NAME: f"样例甲曾提到{roster.NAME}负责装订。"}
        for key, fact in facts.items():
            t.seed("mention-" + key, PRIVATE, fact)
        t.harness()
        for name, question, focus, author, query in (
                (MISSING, f"咱们聊聊{MISSING}吧。", "PERSON_OR_TOPIC", MISSING, ""),
                (roster.NAME, f"哪些聊天提到过{roster.NAME}这个词？", "MENTION", "", roster.NAME)):
            with self.subTest(focus=focus):
                fact = facts[name]
                t.answers = {fact: fact}
                events, reply = t.ask(question, focus, author, query)
                reads, material = self.before_plan(t, events)
                histories = [r for r, _ in reads if r["kind"] == "HISTORY"]
                self.assertTrue(any(name in r["query"] and r["use"] == "CITE" for r in histories), histories)
                self.assertFalse(any("qq_sender_id" in r for kind, r, _ in events if kind == "/v2/recall"))
                self.assertIn(fact, material)
                self.assertEqual(fact, reply)
                looked_up = [r for r, _ in reads if r["kind"] == "IDENTITY" and r["query"] == name]
                self.assertEqual(focus == "PERSON_OR_TOPIC", bool(looked_up))
                self.assertEqual({A, B} if focus == "PERSON_OR_TOPIC" else set(), set(t.listed_groups))

    def test_adjective_is_regular_conversation(self):
        """反例。即使正文带有“你怎么看”，形容配色也不应触发人员目录、共同群名册或作者读取。"""
        t = self.open_trial()
        t.harness()
        events, _ = t.ask("你怎么看清淡柔和的配色呢？", "DEFAULT")
        reads, _ = self.before_plan(t, events)
        self.assertFalse(any(r["kind"] == "IDENTITY" and r["query"] not in ("", str(roster.PEER)) for r, _ in reads))
        self.assertFalse(any("qq_sender_id" in r for kind, r, _ in events if kind == "/v2/recall"))
        self.assertEqual([], t.listed_groups)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("harness", "memory", "output"):
        parser.add_argument(field, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
