"""在网页和 QQ 正式入口上，独立切换交付前的来源回读与群成员复查。"""
import argparse
import json
import unittest
from pathlib import Path

from websockets.exceptions import ConnectionClosed

from dialogue_replay import QQ, SOURCE_INSTANCE, WEB, ReplayTrial
from qq_observe import A, ACCOUNT, context
from qq_sourced_reply import PEER


TOPIC = "苍鹭门票"
FACT = TOPIC + "放在蓝色票夹里。"
QUESTION = TOPIC + "放在哪里？"
ANSWER = "我记得在蓝色票夹里。"


class DeliveryTrial(ReplayTrial):
    """回环服务记录模型、来源查询、成员查询和实际外发的先后顺序。"""

    def __init__(self, args):
        self.events = []
        super().__init__(args)

    def route(self, path, body):
        if path == "/v1/chat/completions" and not body.get("tools"):
            self.events.append(("draft", body["messages"]))
        elif path == "/v2/read" and any(r["record_id"] == self.source["record_id"] for r in body["targets"]):
            self.events.append(("read", (body["kind"], body["use"])))
        elif path == "/v2/ingest" and body["kind"] == "self_expression":
            self.events.append(("persist", body.get("source_refs", [])))
        return super().route(path, body)

    def onebot(self, ws):
        """只模拟一个双方在场的群，发送回执与实际收到的消息逐一对应。"""
        assert ws.request.headers["Authorization"] == "Bearer " + self.token
        try:
            for frame in ws:
                cmd = json.loads(frame)
                action, params = cmd["action"], cmd["params"]
                if action == "get_login_info":
                    data = {"user_id": ACCOUNT}
                elif action == "get_group_list":
                    data = [dict(group_id=A, group_name="苍鹭", member_count=3, max_member_count=20)]
                    self.ws = ws
                elif action == "get_group_member_info":
                    assert (params["group_id"], params["user_id"]) == (A, PEER)
                    self.events.append(("member", None))
                    data = dict(group_id=A, user_id=PEER, nickname="受邀者", card="", sex="unknown", age=0,
                                area="", join_time=1, last_sent_time=1, level="1", role="member", unfriendly=False,
                                title="", title_expire_time=0, card_changeable=True)
                else:
                    assert action == "send_private_msg", cmd
                    self.sent.append(cmd)
                    self.events.append(("send", None))
                    data = {"message_id": 90000 + len(self.sent)}
                ws.send(json.dumps(dict(status="ok", retcode=0, echo=cmd["echo"], data=data)))
        except ConnectionClosed:
            pass


class DeliveryReviewTest(unittest.TestCase):
    def exercise(self, name, switches, channels):
        args = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory, output=ARGS.output / name)
        t = DeliveryTrial(args)
        self.addCleanup(t.close)
        with t.hc.open("a", encoding="utf-8") as out:
            for key, value in switches.items():
                out.write(f"{key}={str(value).lower()}\n")
        t.start_memory("memory")
        receipt = t.mem("ingest", dict(context=context(A), source_instance=SOURCE_INSTANCE, event_id="ticket",
                        kind="observation", content=FACT, occurred_at="2026-01-01T11:00:00Z"))
        t.source = {key: receipt[key] for key in ("record_id", "revision")}
        t.harness()
        for chain in channels:
            with self.subTest(config=name, channel=chain):
                t.events.clear()
                rows, _ = t.turn(chain, QUESTION, ANSWER, TOPIC)
                kinds = [kind for kind, _ in t.events]
                draft = kinds.index("draft")
                handed_off = kinds.index("send" if chain == QQ else "persist")
                self.assertIn(FACT, json.dumps(t.events[draft][1], ensure_ascii=False))
                self.assertGreater(handed_off, draft)
                final = t.events[draft + 1:handed_off]
                self.assertEqual([data for kind, data in final if kind == "read"],
                                 [("HISTORY", "CITE")] if switches.get("chatReviewDeliverySources") else [])
                self.assertEqual(kinds[:draft].count("member"), int(chain == QQ))
                self.assertEqual(sum(kind == "member" for kind, _ in final),
                                 int(chain == QQ and switches.get("chatReviewQqMembership", False)))
                persisted, = [refs for kind, refs in t.events if kind == "persist"]
                self.assertIn(t.source, persisted)
                if chain == QQ:
                    delivered, = [r["data"] for r in rows if r["type"] == "qq_delivery"]
                    self.assertEqual(delivered["messageId"], 90000 + len(t.sent))
                    self.assertIn(t.source, delivered["source_refs"])

    def test_manually_enabling_each_review(self):
        """正例。分别只打开一项，使用有效来源，交付前只出现所选复核的协议请求。"""
        self.exercise("sources", {"chatReviewDeliverySources": True, "chatReviewQqMembership": False}, (QQ, WEB))
        self.exercise("membership", {"chatReviewDeliverySources": False, "chatReviewQqMembership": True}, (QQ,))

    def test_trials_without_manual_reviews(self):
        """反例。未选择任何复核时仍取到获准原文并持久化实收回复，两种交付前回查均为零。"""
        self.exercise("default", {}, (QQ, WEB))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__])
