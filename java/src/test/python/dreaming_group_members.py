"""从真实 Console、Memory 和 OneBot 边界验收群友互聊。传入两个 JAR 和新的验收目录。"""
import argparse
import json
from pathlib import Path

from dreaming import DreamTrial, MASTER_PATH, ROUTER_PATH, SCOPE, ref, tool_call
from dreaming_qq import PRIVATE, QqDreamTrial, group
from qq_observe import A, ACCOUNT, B, C, call


FIRST, SECOND = (54321, "北芦"), (54322, "月衡")
QUESTION = "北芦，我每天都拿方格本记录曝光。你平时怎么记？"
ANSWER = "我习惯把每卷胶卷的参数写在牛皮纸袋上。"
UPDATE = "月衡，我现在改用铅笔记在胶卷盒盖上了。"
CLOSED = "我只在封存照片时戴紫色手套。"
PROBE = "今晚先聊到这里。"
FACTS = {
    QUESTION: (SECOND, "我记得月衡每天在方格本上记录曝光。"),
    ANSWER: (FIRST, "我记得北芦习惯在牛皮纸袋上写胶卷参数。"),
    UPDATE: (FIRST, "我记得北芦现在用铅笔把参数写在胶卷盒盖上。"),
}


class MemberTrial(QqDreamTrial):
    def __init__(self, args):
        super().__init__(args)
        self.received, self.history_flags, self.side_effects = {}, [], []
        self.other_profile = None

    def onebot(self, ws):
        # 静态白名单没有动态群名单请求，直接保留此验收连接以发送真实的 OneBot 事件。
        self.ws = ws
        super().onebot(ws)

    def say(self, room, number, member, text, mention=None, picture=False):
        uid, name = member
        segments = [{"type": "text", "data": {"text": text}}]
        if mention:
            segments.insert(0, {"type": "at", "data": {"qq": str(mention[0])}})
        if picture:
            segments.append({"type": "image", "data": {"file": "sample.jpg", "url": self.base + "/group-photo"}})
        self.ws.send(json.dumps(dict(post_type="message", message_type="group", sub_type="normal", self_id=ACCOUNT,
                         group_id=room, user_id=uid, sender=dict(user_id=uid, nickname=name, card=""),
                         message_id=number, message=segments)))

    def route(self, path, body, headers):
        if path == "/v2/dreaming/batch":
            self.history_flags.append(body.get("include_group_history"))
        if path in (MASTER_PATH, ROUTER_PATH, "/chat/v1/chat/completions", "/group-photo"):
            self.side_effects.append(path)
            if path == "/group-photo":
                return 404, {}
        if path == "/v2/dreaming/commit" and self.other_profile:
            for change in body["changes"]:
                if change["text"] != FACTS[ANSWER][1]:
                    continue
                # 同一批的正式提交入口上只换成被 @ 的已知人物，并给出其真实现版。
                # 来源只有甲自述的习惯，即使乙也获签发，乙的档案也不能用这条原话修改。
                peer = self.other_profile
                wrong = dict(change, subject=peer["subject"], person_id=peer["person_id"],
                             expected_version=peer["version"], text="我记得月衡习惯在牛皮纸袋上写胶卷参数。")
                status, error = call(self.mp, path, dict(body, changes=[wrong], event_id="misassigned-habit"),
                                     token=self.token)
                assert status == 422, ("不应将甲的第一人称习惯归给被提及的乙", status, error)
                self.other_profile = None
                break
        status, result = super().route(path, body, headers)
        if path == "/v2/ingest" and status == 200 and result["status"] == "DURABLE":
            self.received[body["channel"]["message_id"]] = ref(result)
        return status, result

    def model_reply(self, path, body, headers):
        """挡板只用本批人物、原话与原版本写认识，未见的消息不会生成档案。"""
        user, = [m["content"] for m in body["messages"] if m["role"] == "user"]
        packet = json.loads(user)
        self.model_calls.append({"input": packet})
        changes = []
        for row in packet["evidence"]:
            for note, ((uid, name), fact) in FACTS.items():
                if note not in row["content"]:
                    continue
                pid = row["speaker_person_id"]
                person, = [p for p in packet["persons"] if p["person_id"] == pid]
                assert row["sender_id"] == str(uid) and person["subject"] == name, (row, person)
                if note == ANSWER and not self.static:
                    assert str(SECOND[0]) in row["mention_ids"] and str(ACCOUNT) not in row["mention_ids"]
                    assert any(p["subject"] == SECOND[1] for p in packet["persons"])
                old = next((p for p in packet["profiles"] if p.get("person_id") == pid), {})
                if ref(row) in old.get("source_refs", []):
                    continue
                changes.append(dict(kind="person", person_id=pid, subject=name, expected_version=old.get("version", 0),
                                    text=fact, scope=row["conversation_id"], reason="群成员说明自己的摄影习惯。",
                                    source_refs=[ref(row)]))
        return 200, tool_call("submit_dreaming", {"changes": changes, "name_claims": []})

    def committed_message(self, number):
        source = self.wait_for(lambda: self.received.get(str(number)), "群成员原话取得 Memory 持久回执")
        self.wait_committed(source)
        return source

    def silent(self):
        assert not self.side_effects, self.side_effects
        assert not any(a.startswith("send_") or a == "get_image" for a in self.bot_actions), self.bot_actions
        assert self.history_flags and all(flag is True for flag in self.history_flags), self.history_flags


def dynamic_members(t):
    """OBSERVE 中两人互相说话、引用各自原句，改口沿用原档。关闭后旧档收回，新话不入账。"""
    t.start_dreaming()
    t.scopes({SCOPE, PRIVATE, group(A), group(C)})
    t.say(A, 901, SECOND, QUESTION)
    second_ref = t.committed_message(901)
    peer, = t.profiles()
    assert (peer["subject"], peer["text"], peer["source_refs"]) == (SECOND[1], FACTS[QUESTION][1], [second_ref])
    t.other_profile = peer
    t.say(A, 902, FIRST, ANSWER, mention=SECOND)
    first_ref = t.committed_message(902)
    current = {p["subject"]: p for p in t.snapshot("两位群友各自的初稿")}
    first = current[FIRST[1]]
    assert current == {SECOND[1]: peer, FIRST[1]: first} and first["person_id"] != peer["person_id"]
    assert (first["text"], first["source_refs"]) == (FACTS[ANSWER][1], [first_ref])
    assert t.other_profile is None

    t.say(A, 903, FIRST, UPDATE)
    new_ref = t.committed_message(903)
    updated = {p["subject"]: p for p in t.snapshot("群友更新自己的记录习惯")}
    revised = updated[FIRST[1]]
    assert set(updated) == set(current) and updated[SECOND[1]] == peer
    assert (revised["person_id"], revised["version"], revised["source_refs"], revised["text"]) == (
        first["person_id"], first["version"] + 1, [new_ref], FACTS[UPDATE][1])
    assert set(t.files()) == {"people/北芦.md", "people/月衡.md"}

    seen = len(t.claims)
    t.policy(A, "OFF")
    t.scopes({SCOPE, PRIVATE, group(C)}, start=seen)
    t.say(A, 904, FIRST, CLOSED, mention=SECOND)
    # 用同一 OneBot 连接的另一个 OBSERVE 群作有序屏障，观察已关闭群的事件是否进入材料。
    t.policy(C, "OBSERVE")
    t.say(C, 905, SECOND, PROBE)
    t.wait_for(lambda: t.material(PROBE), "后续开放群原话进入模型")
    assert "904" not in t.received and t.material(CLOSED) is None
    assert t.snapshot("关闭来源群") == []
    t.silent()


def static_members(t):
    """静态白名单启用 Dreaming 后，未点名机器人的群友图文只整理文字，其他群继续隔离。"""
    t.static = True
    t.console["openQqConfig"] = t.config("onebot-static", **t.onebot_config, allowedGroupId=B)
    t.start_dreaming()
    t.scopes({SCOPE, PRIVATE, group(B)})
    t.wait_for(lambda: hasattr(t, "ws"), "静态白名单的 OneBot 连接就绪")
    t.say(A, 911, FIRST, CLOSED, mention=SECOND, picture=True)
    t.say(B, 912, FIRST, ANSWER, mention=SECOND, picture=True)
    source = t.committed_message(912)
    person, = t.snapshot("静态群的成员自述")
    assert (person["subject"], person["text"], person["source_refs"]) == (FIRST[1], FACTS[ANSWER][1], [source])
    assert "911" not in t.received and t.material(CLOSED) is None
    t.silent()


def signed_messages(t):
    """OneBot 群消息号是有符号 int32。按真实消息号保存与引用，数字字符串、越界值和负账号无效。"""
    t.start_dreaming()
    t.scopes({SCOPE, PRIVATE, group(A), group(C)})
    t.say(A, "-17", SECOND, "伪装成数字的字符串。")
    t.say(A, 2147483648, SECOND, "溢出的消息号。")
    t.say(A, -18, (-54322, "月衡"), "负数用户号。")
    t.say(A, -2147483648, SECOND, QUESTION)
    source = t.committed_message(-2147483648)
    t.say(A, 0, FIRST, PROBE)
    t.wait_for(lambda: t.material(PROBE), "有效的零值消息号也从同一群连接入账")
    assert set(t.received) == {"-2147483648", "0"}, t.received
    assert t.material(QUESTION)["message_id"] == "-2147483648" and t.material(PROBE)["message_id"] == "0"
    archive, = t.profiles()
    assert (archive["subject"], archive["text"], archive["source_refs"]) == (SECOND[1], FACTS[QUESTION][1], [source])
    messages = [e["data"]["messageId"] for e in t.events(A) if e["type"] == "qq_message"]
    assert messages == [-2147483648, 0], messages
    t.silent()


class HistoryRelay(DreamTrial):
    """在正式 HTTP 边界交付数字位置相同的历史页和实时页，模拟历史提交后丢失即时回包。"""
    def __init__(self, args):
        super().__init__(args)
        self.saved = {}
        self.history_lookups = []

    def route(self, path, body, headers):
        if path == "/v2/dreaming/batch":
            assert body.get("include_group_history") is True, body
            status, page = super().route(path, body, headers)
            assert status == 200 and not page["evidence"], page
            # 外部 Relay 模拟回扫页已筛完但无证据，再交付位置相同的实时页。
            page = dict(page, after=1 if len(self.saved) == 2 else 0, through=1)
            if not self.saved:
                page["lane"] = "group_history"
            return status, page
        if path == "/v2/dreaming/commit":
            first = not self.saved
            assert (body["after"], body["through"], body.get("lane")) == (0, 1, "group_history" if first else None)
            assert first or (self.history_lookups and self.history_lookups[-1].get("lane") == "group_history")
            key = body["event_id"]
            assert key not in self.saved, "历史页与实时页必须有不同命令键"
            fields = ("event_id", "stream_id", "policy_version", "after", "through", "lane")
            receipt = dict(status="DURABLE", **{k: body[k] for k in fields if k in body})
            self.saved[key] = receipt
            self.commits.append(dict(command=body, status=503 if first else 200, response=receipt))
            return (503, {"error": "即时回包中断"}) if first else (200, receipt)
        if path == "/v2/dreaming/receipt" and self.saved.get(body["event_id"], {}).get("lane") == "group_history":
            receipt = self.saved[body["event_id"]]
            # 首次查回一个属于别的进度的回执。只有查到原命令的 group_history 回执才算成功。
            result = receipt if self.history_lookups else dict(receipt, lane="identity_backfill")
            self.history_lookups.append(result)
            self.lookups.append(dict(event_id=body["event_id"], result=result))
            return 200, result
        return super().route(path, body, headers)


def history_receipt(t):
    t.start_dreaming()
    t.wait_for(lambda: len(t.saved) == 2, "核实历史页原键回执以后继续处理实时页")
    historical, live = t.saved.values()
    assert [r["lane"] for r in t.history_lookups[:2]] == ["identity_backfill", "group_history"]
    assert all(r["event_id"] == historical["event_id"] for r in t.history_lookups[:2])
    assert historical["lane"] == "group_history" and "lane" not in live and not t.model_calls


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("harness", "memory", "output"):
        parser.add_argument(key, type=Path)
    parser.add_argument("--case", choices=("dynamic", "static", "signed", "history"))
    parser.set_defaults(live=False)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for label, test, fixture in (("dynamic", dynamic_members, MemberTrial), ("static", static_members, MemberTrial),
                                 ("signed", signed_messages, MemberTrial), ("history", history_receipt, HistoryRelay)):
        if args.case and args.case != label:
            continue
        trial = fixture(argparse.Namespace(**{**vars(args), "output": args.output / label}))
        outcome = "FAIL"
        try:
            memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
            trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
            test(trial)
            outcome = "PASS"
        finally:
            trial.close()
            trial.report(outcome)
