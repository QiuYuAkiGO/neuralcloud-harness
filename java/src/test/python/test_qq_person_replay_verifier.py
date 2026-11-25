"""以合成的私聊回放和截点数据库，从命令行核对人物验收器的一正一反两个出口。"""
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class PersonReplayVerifierCases(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        account, peer, author, group = "71001", "71002", "71003", "72001"
        name, question, quote = "石榴雀", "这位石榴雀平时聊些什么？", "昨晚记录了三颗流星。"
        chain, scope = f"qq-private-{account}-{peer}", f"c2c:onebot:{account}:{peer}"
        self.ref = {"record_id": "e" * 64, "revision": 1}
        self.reply = "从现有记录看，这位群友会聊夜间观测。"
        occurred = "2026-02-01T19:10:00Z"
        info = dict(account_id=account, peer_id=peer, member_group_ids=[group])
        expected = dict(peer=peer, message_id=71, name=name, author=author, model="trial-chat", cut_seq=4,
                        source_quotes=[quote], reply_require=[], reply_forbid=["我亲眼见到"])
        item = dict(ref=self.ref, status="ONLINE", occurred_at=occurred, citation_scope=scope, text=quote)
        self.identity = f"按「{name}」查询 QQ 账号 {author} 的原话。"
        self.line = f"QQ 账号 {author}的原话（北京时间 2026-02-02 03:10:00）：“{quote}”。"
        trace = []

        def event(kind, node, data):
            index = len(trace) + 1
            trace.append(dict(type=kind, node=node, data=data, runId="isolated", chain=chain, eventId=index,
                              causedBy=index - 1 if trace else None, recordedAt=f"2026-02-04T01:00:{index:02}Z"))

        event("qq_message", "openqq", dict(messageId=71, text=question))
        event("router_memory_gate", "router", dict(operation="RECALL", consumer="CHAT_WORKER"))
        event("qq_author_lookup", "router", dict(status="RESOLVED", author_ref=name, qq_sender_id=author))
        event("memory_call", "chat-worker", dict(kind="HISTORY", query="", author_ref=name, qq_sender_id=author))
        event("memory_recall", "memory", dict(bindings=[dict(item=item, id=self.ref["record_id"], scope=scope,
                                                             worker="CHAT_WORKER", kind="HISTORY", input="input-1")]))
        event("memory_call_result", "memory", dict(count=1, sentences=[self.identity, self.line]))
        event("chat_worker_turn_plan", "chat-worker", dict(automaticInput=dict(inputId="input-1", kind="HISTORY", count=1)))
        event("chat_worker_reply", "chat-worker", dict(text=self.reply))
        event("qq_send_requested", "openqq", {})
        event("qq_delivery", "openqq", dict(messageId=72, source_refs=[self.ref]))
        packet = dict(model="trial-chat", tools=[{"function": {"name": "plan_turn"}}],
                      messages=[dict(role="user", content="\n".join((question, self.identity, self.line)))])
        self.trial = dict(trace=trace, modelInputs=[packet], onebotActions=[dict(action="send_private_msg")],
                          sent=[dict(at=trace[-2]["recordedAt"], params=dict(user_id=int(peer), message=self.reply), receipt=72)])
        self.save("expect.json", expected)
        self.save("original.json", dict(trace[0], runId="earlier", recordedAt="2026-02-03T01:00:00Z"))
        self.save("rosters.json", dict(qq=info, completed_at=occurred, by_group={group: dict(verified_membership=True,
                  rows=[dict(user_id=int(author), nickname=name, card="")])}))
        self.save("memory-http.json", [dict(path="/v2/recall", status=200, at=trace[3]["recordedAt"],
                  request=dict(kind="HISTORY", query="", qq_sender_id=author, use="CITE", before=trace[0]["recordedAt"],
                               context=dict(role="CHAT_WORKER", conversation_id=scope, qq=info)),
                  result=dict(items=[item], assessment={"state": "DIRECT"}))])
        source = dict(content=quote, channel=dict(platform="qq", type="qq_group", sender_id=author, account_id=account,
                                                 conversation_id=f"group:onebot:{account}:{group}"))
        db = sqlite3.connect(self.root / "cut.sqlite")
        try:
            db.executescript("CREATE TABLE changes(seq INTEGER); INSERT INTO changes VALUES(4);"
                             "CREATE TABLE revisions(record_id TEXT, revision INTEGER, seq INTEGER, occurred TEXT, body TEXT);")
            db.execute("INSERT INTO revisions VALUES (?,1,2,?,?)", (self.ref["record_id"], occurred, json.dumps(source)))
            db.commit()
        finally:
            db.close()

    def save(self, name, value):
        (self.root / name).write_text(json.dumps(value), encoding="utf-8")

    def run_trial(self):
        """只交一轮输入给公开 CLI，检查返回码与它生成的回执。"""
        self.save("result.json", self.trial)
        script = Path(__file__).with_name("verify_qq_person_replay.py")
        return subprocess.run([sys.executable, "-X", "utf8", str(script.resolve()), ".", "--original", "original.json",
                               "--rosters", "rosters.json", "--expect", "expect.json", "--snapshot", "cut.sqlite",
                               "--output", "receipt.json"], cwd=self.root, capture_output=True, encoding="utf-8")

    def test_author_evidence_in_first_plan_and_one_private_receipt(self):
        observed = self.run_trial()
        self.assertEqual(observed.returncode, 0, observed.stderr)
        receipt = json.loads((self.root / "receipt.json").read_bytes())
        self.assertEqual((receipt["evidence"][0]["ref"], receipt["reply"], receipt["receipt"]),
                         (self.ref, self.reply, 72))

    def test_old_assistant_quote_and_later_correct_plan_cannot_supply_first_plan(self):
        # 装入过去回复和稍后的正确请求，保留真实召回与送达，仅首次规划缺少本人原话。
        correct = self.trial["modelInputs"][0]
        latest = correct["messages"][-1]["content"].splitlines()[0]
        self.trial["modelInputs"].insert(0, dict(correct, messages=[
            dict(role="assistant", content=self.line), dict(role="user", content=latest + "\n" + self.identity)]))
        observed = self.run_trial()
        self.assertNotEqual(observed.returncode, 0)
        self.assertIn("首次规划的本轮材料缺少", observed.stderr)
        self.assertFalse((self.root / "receipt.json").exists())


if __name__ == "__main__":
    unittest.main()
