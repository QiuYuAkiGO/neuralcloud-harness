"""用两个正式 JVM 和回环 OneBot 验收 QQ 私聊首次规划前的按作者查询。"""
import argparse
import json
import re
import unittest
from datetime import datetime
from pathlib import Path

from websockets.exceptions import ConnectionClosed

from qq_memory_auto_recall import PEER, PRIVATE, RecallTrial
from qq_observe import A, ACCOUNT, B, C, call, context


NAME = "MarShFiNch47"
QUESTION = f"你怎么看{NAME}呢"
AUTHOR, OTHER = 54321, 76543
FACT = "我把这次的星图按观测日期整理好了。"
FACT_UTC = "2026-01-17T02:37:48Z"
ANSWER = "从整理星图这件事看，这位朋友做事很有条理。"
CLARIFY = "你指的是哪位？可以告诉我对方的 QQ 号吗？"


class AuthorTrial(RecallTrial):
    """名册由私聊提问者所在的两个群返回。第三个群内也有同名账号，但提问者不在场。"""

    def __init__(self, args, ambiguous):
        super().__init__(args)
        self.ambiguous = ambiguous
        self.listed_groups = []
        self.answers = {FACT: ANSWER}
        policies = {str(group): {"revision": 1, "policy": "OBSERVE"} for group in (A, B, C)}
        (self.dir / "policies.json").write_text(
            json.dumps({"account": str(ACCOUNT), "policies": policies}), encoding="utf-8")

    def onebot(self, ws):
        """昵称和名片分别命中，另一群的相同账号仅算一人。各发送回执与实际帧一一对应。"""
        assert ws.request.headers["Authorization"] == "Bearer " + self.token
        try:
            for frame in ws:
                cmd = json.loads(frame)
                action, params = cmd["action"], cmd["params"]
                status, code = "ok", 0
                if action == "get_login_info":
                    data = {"user_id": ACCOUNT}
                elif action == "get_group_list":
                    data = [dict(group_id=g, group_name=str(g), member_count=3, max_member_count=20)
                            for g in (A, B, C)]
                    self.ws = ws
                elif action == "get_group_member_info":
                    assert params["user_id"] == PEER
                    data = dict(group_id=params["group_id"], user_id=PEER)
                    if params["group_id"] == C:
                        status, code, data = "failed", 1404, None
                elif action == "get_group_member_list":
                    group = params["group_id"]
                    self.listed_groups.append(group)
                    author = OTHER if group == C or (group == B and self.ambiguous) else AUTHOR
                    data = [dict(group_id=group, user_id=author, nickname=NAME if group == A else "群友",
                                 card="" if group == A else NAME)]
                else:
                    assert action == "send_private_msg", cmd
                    self.sent.append(params)
                    data = {"message_id": 92000 + len(self.sent)}
                ws.send(json.dumps(dict(status=status, retcode=code, echo=cmd["echo"], data=data)))
        except ConnectionClosed:
            pass

    def route(self, path, body):
        """歧义场景允许模型在被要求答复时提出澄清；是否查过名册、是否错查个人由服务请求判定。"""
        status, response = super().route(path, body)
        material = json.dumps(body["messages"], ensure_ascii=False) if path == "/v1/chat/completions" else ""
        if self.ambiguous and material and not body.get("tools") and re.search(
                r"同名|重名|不唯一|多个.{0,8}(账号|匹配|成员)|歧义|需.{0,4}澄清", material):
            response["choices"][0]["message"]["content"] = CLARIFY
        return status, response


class AuthorRecallCases(unittest.TestCase):
    def open_trial(self, ambiguous):
        args = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory, output=ARGS.output / self._testMethodName)
        trial = AuthorTrial(args, ambiguous)
        self.addCleanup(trial.proxy.server_close)
        self.addCleanup(trial.close)
        memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
        trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
        return trial

    @staticmethod
    def seed_quote(trial, sender, number, at, text):
        """通过公开入账接口固定原话、作者和原始 UTC，随后只观察正式渠道及模型边界。"""
        group = context(A)
        return trial.mem("ingest", dict(context=group, source_instance="neuralcloud-java", event_id=str(number),
                         kind="observation", occurred_at=at, content=text, speaker=str(sender),
                         channel=dict(platform="qq", type="qq_group", account_id=str(ACCOUNT), sender_id=str(sender),
                                      conversation_id=group["conversation_id"], message_id=str(number))))

    def test_shared_nickname_and_card_resolve_to_one_author_before_planning(self):
        """正例。正文没有账号或昵称；仅按两群名册共同指向的作者，才能拿到预存原话。"""
        t = self.open_trial(ambiguous=False)
        receipt = self.seed_quote(t, AUTHOR, 481190, FACT_UTC, FACT)
        source = {key: receipt[key] for key in ("record_id", "revision")}
        t.harness()
        observed, reply = t.ask_private(QUESTION, 481201, memory_focus="PERSON_OR_TOPIC", author_ref=NAME)
        first = next(i for i, (kind, _, _) in enumerate(observed) if kind == "plan")
        before = observed[:first]
        self.assertEqual(set(t.listed_groups), {A, B})
        self.assertTrue(any(kind == "/v2/recall" and req["kind"] == "IDENTITY" and req["query"] == NAME
                            for kind, req, _ in before))
        authorized = [(req, result) for kind, req, result in before if kind == "/v2/recall"
                      and req.get("qq_sender_id") == str(AUTHOR)]
        self.assertEqual(len(authorized), 1)
        request, result = authorized[0]
        self.assertEqual((request["kind"], request["use"], request["query"], request["limit"]),
                         ("HISTORY", "CITE", "", 4))
        self.assertEqual(request["context"]["conversation_id"], PRIVATE)
        self.assertEqual(request["context"]["qq"]["peer_id"], str(PEER))
        inbound = next(req for kind, req, _ in before if kind == "/v2/ingest" and req["content"] == QUESTION)
        self.assertLessEqual(datetime.fromisoformat(request["before"]), datetime.fromisoformat(inbound["occurred_at"]))
        item, = [row for row in result["items"] if row["ref"] == source and row["text"] == FACT]
        self.assertTrue(item["occurred_at"].endswith("Z"))
        self.assertEqual(datetime.fromisoformat(item["occurred_at"]), datetime.fromisoformat(FACT_UTC))
        drafts = [messages for kind, messages, _ in observed if kind == "draft"]
        self.assertTrue(drafts, "需要核对实际起草请求里的作者材料")
        for messages in (observed[first][1], *drafts):
            material = "\n".join(row["content"] for row in messages if FACT in (row.get("content") or ""))
            self.assertRegex(material, r"10(?:[:：]37|时37分)", "目标原话应按北京时间提交给模型")
            self.assertRegex("\n".join(row.get("content") or "" for row in messages),
                             r"北京时间|东八区|Asia/Shanghai|\+08:00|UTC\+8\b", "模型需要知道所用时区")
        self.assertEqual(reply, ANSWER)
        self.assertEqual(len(t.sent), 1)
        status, audit = call(t.hp, f"/api/audit/chains/qq-private-{ACCOUNT}-{PEER}")
        self.assertEqual(status, 200)
        self.assertTrue(any(e["type"] == "memory_call" and e["data"].get("author_ref") == NAME
                            for e in audit["events"]))
        stored = [b["item"] for e in audit["events"] if e["type"] == "memory_recall"
                  for b in e["data"]["bindings"] if b["item"]["ref"] == source]
        self.assertTrue(stored)
        self.assertTrue(all(row["occurred_at"] == item["occurred_at"] for row in stored))

    def test_same_name_on_two_accounts_and_mention_only_question(self):
        """反例。同名不同号时请求辨认；随后问谁提到这个名字时应按正文查，两个入口均不指定作者。"""
        t = self.open_trial(ambiguous=True)
        other_quote = "朱砂样片分装到提匣里了。"
        self.seed_quote(t, OTHER, 481191, "2026-01-23T02:53:11Z", other_quote)
        t.harness()
        ambiguous, reply = t.ask_private(QUESTION, 481202, memory_focus="PERSON_OR_TOPIC", author_ref=NAME)
        self.assertEqual(set(t.listed_groups), {A, B})
        self.assertFalse(any("qq_sender_id" in req for kind, req, _ in ambiguous if kind == "/v2/recall"))
        self.assertRegex(reply, r"哪[一位个]|QQ\s*号|具体.*称呼|如何区分")
        t.listed_groups.clear()
        mention, _ = t.ask_private(f"谁提到过{NAME}？", 481203, memory_focus="MENTION", query=NAME)
        first = next(i for i, (kind, _, _) in enumerate(mention) if kind == "plan")
        histories = [req for kind, req, _ in mention[:first] if kind == "/v2/recall" and req["kind"] == "HISTORY"]
        self.assertTrue(any(NAME in req["query"] for req in histories))
        self.assertTrue(all("qq_sender_id" not in req for req in histories))
        self.assertEqual(t.listed_groups, [])

        # 直接指定另一账号，名册歧义不影响此轮；只有同群其他人的原话，目标作者应为零命中。
        empty, _ = t.ask_private(f"你怎么看{AUTHOR}呢", 481204, memory_focus="PERSON", author_ref=str(AUTHOR))
        reads = [result for kind, req, result in empty
                 if kind == "/v2/recall" and req.get("qq_sender_id") == str(AUTHOR)]
        self.assertTrue(reads and all(not result["items"] for result in reads))
        for kind, messages, _ in empty:
            if kind in ("plan", "draft"):
                material = json.dumps(messages, ensure_ascii=False)
                self.assertNotIn(other_quote, material)
                self.assertNotRegex(material, r"(?:2026[-/.]0?1[-/.]23|0?1月23日)\D{0,16}10(?:[:：]53|时53分)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
