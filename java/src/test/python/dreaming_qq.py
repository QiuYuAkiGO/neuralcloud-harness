"""正式 ConsoleMain、Memory v2 与回环 OneBot 验收 Dreaming 的 QQ 授权和前台让行。"""
import argparse
import json
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

from websockets.exceptions import ConnectionClosed
from websockets.sync.server import serve

from dreaming import DreamTrial, SCOPE, context, ref, tool_call
from qq_observe import A, ACCOUNT, B, C, call


PEER, OTHER, GHOST = 67890, 67891, 98765
PRIVATE = f"c2c:onebot:{ACCOUNT}:{PEER}"
PRIVATE_NOTE = "私聊采样员牧晴保管紫砂样盒。"
OBSERVED = "白槐组交接时先清点刻度盘。"
REPLY_GROUP = "海棠组每周校正一次蓝尺。"
IN_FLIGHT = "白槐组打算为新盒子印银杏封面。"
DISCONNECTED = "海棠组刚收到用于比对的琥珀样本。"
STATIC_NOTE = "红枫组的夜间底图一律采用暖灰色。"
NOTES = {PRIVATE_NOTE: "牧晴", OBSERVED: "白槐刻度盘", REPLY_GROUP: "海棠蓝尺",
         IN_FLIGHT: "银杏封面", DISCONNECTED: "琥珀样本", STATIC_NOTE: "红枫底图"}


def group(number):
    return f"group:onebot:{ACCOUNT}:{number}"


def draft(note):
    return "我把这条记录作为本会话对" + NOTES[note] + "的暂定认识。"


class QqDreamTrial(DreamTrial):
    """只在 OneBot、模型和 Memory HTTP 边界同步；策略变更都从控制台正式入口发出。"""
    def __init__(self, args):
        super().__init__(args)
        self.roster, self.sent, self.claims = [A, B, C], [], []
        self.static = False
        self.bot = serve(self.onebot, "127.0.0.1", 0)
        threading.Thread(target=self.bot.serve_forever, daemon=True).start()
        policies = self.dir / "policies.json"
        policies.write_text(json.dumps(dict(account=str(ACCOUNT), policies={
            str(A): {"revision": 1, "policy": "OBSERVE"}, str(C): {"revision": 1, "policy": "OBSERVE_REPLY"},
            str(GHOST): {"revision": 1, "policy": "OBSERVE"}})), encoding="utf-8")
        self.onebot_config = dict(endpoint=f"ws://127.0.0.1:{self.bot.socket.getsockname()[1]}/",
                                  accountId=ACCOUNT, allowedUserId=PEER, accessTokenEnv="TRIAL_KEY")
        self.console["openQqConfig"] = self.config("onebot", **self.onebot_config, groupPoliciesFile=policies)

    def onebot(self, ws):
        """私聊会核对成员身份；按群名单返回 OneBot 11 的成员资料，并以 echo 关联请求。"""
        assert ws.request.headers["Authorization"] == "Bearer " + self.token
        try:
            for frame in ws:
                cmd = json.loads(frame)
                action, params = cmd["action"], cmd.get("params", {})
                status, code = "ok", 0
                if action == "get_login_info":
                    data = dict(user_id=ACCOUNT, nickname="试验机器人")
                elif action == "get_group_list":
                    self.checkpoint("roster")
                    data = [dict(group_id=g, group_name=str(g), member_count=3, max_member_count=20) for g in self.roster]
                    self.ws = ws
                elif action == "get_group_member_info" and params["group_id"] in self.roster and params["user_id"] == PEER:
                    data = dict(group_id=params["group_id"], user_id=PEER, nickname="牧晴", card="", sex="unknown",
                                age=0, area="", join_time=1, last_sent_time=1, level="1", role="member",
                                unfriendly=False, title="", title_expire_time=0, card_changeable=True)
                elif action == "send_private_msg" and params["user_id"] == PEER:
                    self.sent.append(cmd)
                    data = {"message_id": 90000 + len(self.sent)}
                else:
                    status, code, data = "failed", 100, None
                ws.send(json.dumps(dict(status=status, retcode=code, echo=cmd["echo"], data=data)))
        except ConnectionClosed:
            pass  # 断线用例主动关闭这一条连接，恢复时会重新核验名单。

    def route(self, path, body, headers):
        if path == "/v2/dreaming/batch":
            assert headers.get("Authorization") == "Bearer " + self.token
            status, batch = call(self.mp, path, body, token=self.token)
            if status == 200:
                self.batches.append(batch)
                self.claims.append(set(body["scopes"]))
            return status, batch
        if path == "/chat/v1/chat/completions":
            names = [obj["function"]["name"] for obj in body.get("tools", [])]
            if names == ["select_reply"]:
                return 200, tool_call("select_reply", {"decision": "SKIP"})
            if names == ["plan_turn"]:
                return 200, tool_call("plan_turn", {"kind": "REPLY", "query": ""})
            assert not names, names
            return 200, {"model": "trial", "choices": [{"finish_reason": "stop", "message": {"content": "收到。"}}]}
        return super().route(path, body, headers)

    def model_reply(self, path, body, headers):
        user, = [m["content"] for m in body["messages"] if m["role"] == "user"]
        packet = json.loads(user)
        allowed = {SCOPE, PRIVATE, group(B)} if self.static else {SCOPE, PRIVATE, group(A), group(C)}
        assert all(e["conversation_id"] in allowed for e in packet["evidence"]), packet
        self.model_calls.append({"input": packet, "static": self.static})
        changes = []
        for e in packet["evidence"]:
            note = e["content"]
            if note not in NOTES:
                continue
            if note in (OBSERVED, IN_FLIGHT):
                self.checkpoint(note)
            subject = NOTES[note]
            prior = next((p for p in packet["profiles"] if p["subject"] == subject), {})
            if ref(e) not in prior.get("source_refs", []):
                changes.append(dict(kind="person" if note == PRIVATE_NOTE else "topic", subject=subject,
                                    expected_version=prior.get("version", 0), text=draft(note), scope=e["conversation_id"],
                                    reason="本会话交接记录提供了直接依据。", source_refs=[ref(e)]))
        return 200, tool_call("submit_dreaming", {"changes": changes})

    def scopes(self, expected, start=0):
        self.wait_for(lambda: expected in self.claims[start:], "账号、允许私聊和接收群组成的精确范围 " + str(expected))

    def material(self, note):
        return next((e for c in self.model_calls for e in c["input"]["evidence"] if e["content"] == note), None)

    def published(self, note, scope):
        e = self.wait_for(lambda: self.material(note), "模型收到 " + note)
        assert e["conversation_id"] == scope
        self.wait_committed(e)
        self.wait_for(lambda: any(draft(note) in s for s in self.files().values()), "发布 " + note)
        return e

    def close(self):
        super().close()
        self.bot.shutdown()

    def report(self, outcome):
        changes = [sorted(s) for idx, s in enumerate(self.claims) if idx == 0 or s != self.claims[idx - 1]]
        details = dict(outcome=outcome, scopeChanges=changes, modelRequests=self.model_calls, commits=self.commits,
                       stages=self.stages, masterRequests=len(self.master_calls), protocolErrors=self.failures)
        path = self.dir / "result.json"
        path.write_text(json.dumps(details, ensure_ascii=False, indent=2), encoding="utf-8")
        print(outcome, path)


def run(t):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.reject(dict(t.settings(), sourceScopes=group(B)), t.console, "sourceScopes")
    # 即使独立记忆写入方已经留存了原文，未接收群、已退群和其他私聊也不能成为模型材料。
    t.ingest("off", "红枫组早期未开放的旧样本。", "成员", group(B))
    t.ingest("left", "已退群保存的红色卷标。", "成员", group(GHOST))
    outsider = f"c2c:onebot:{ACCOUNT}:{OTHER}"
    outside = t.mem("ingest", dict(context=dict(context(outsider), qq=dict(account_id=str(ACCOUNT), peer_id=str(OTHER))),
                    source_instance="neuralcloud-java", event_id="other-private", kind="observation",
                    content="其他私聊保管的银扣。", occurred_at=datetime.now(timezone.utc).isoformat()))
    assert outside["status"] == "DURABLE"
    t.start_dreaming()
    dynamic = {PRIVATE, group(A), group(C), SCOPE}
    t.scopes(dynamic)
    t.wait_for(lambda: t.panel()["connected"], "已核验 OneBot 群名单")
    t.ws.send(json.dumps(dict(post_type="message", message_type="private", sub_type="friend", self_id=ACCOUNT,
                              user_id=PEER, message_id=801, message=[{"type": "text", "data": {"text": PRIVATE_NOTE}}])))
    t.published(PRIVATE_NOTE, PRIVATE)
    t.ingest("group-c", REPLY_GROUP, "成员", group(C))
    t.published(REPLY_GROUP, group(C))

    # 先让已接收群的证据进入后台模型，再启动前台。模型返回后，前台占用期间应保留原批次。
    model = t.arm(OBSERVED)
    foreground = t.arm("foreground")
    try:
        t.emit(A, 802, OBSERVED, at=False)
        model.wait()
        original = t.material(OBSERVED)
        current_batch = next(b for b in t.batches if ref(original) in [ref(e) for e in b["evidence"]])
        before = len(t.commits)
        front = t.worker.submit(call, t.hp, "/api/nodes/router/messages", dict(chain="foreground-qq", text="和我打个招呼。"))
        foreground.wait()
        model.release.set()
        time.sleep(2.2)
        assert not any(c["status"] == 200 and c["command"]["through"] >= current_batch["through"] for c in t.commits[before:])
        assert all(draft(OBSERVED) not in doc for doc in t.files().values())
    finally:
        model.release.set()
        foreground.release.set()
    assert front.result(timeout=8)[0] == 200
    t.published(OBSERVED, group(A))

    # 尚在模型中的新提案与此前已发布的同群稿须一起随 OFF 收回；其他群和允许私聊仍可使用。
    in_flight = t.arm(IN_FLIGHT)
    try:
        t.emit(A, 803, IN_FLIGHT, at=False)
        in_flight.wait()
        position = len(t.claims)
        t.policy(A, "OFF")
    finally:
        in_flight.release.set()
    t.scopes(dynamic - {group(A)}, start=position)
    t.wait_for(lambda: all(draft(OBSERVED) not in s for s in t.files().values()), "收回已关闭群的投影")
    profiles = t.snapshot("关闭群接收")
    assert {p["subject"] for p in profiles} == {NOTES[PRIVATE_NOTE], NOTES[REPLY_GROUP]}
    stale = ref(t.material(IN_FLIGHT))
    assert not any(c["status"] == 200 and any(stale in change["source_refs"] for change in c["command"]["changes"])
                   for c in t.commits), "关闭群后仍提交了旧授权的在途提案"

    # 实际断开 WebSocket 并暂扣下次群名单回包。在此期间由另一写入方持久确认一条群原文。
    roster = t.arm("roster")
    try:
        t.ws.close()
        roster.wait()
        assert not t.panel()["connected"]
        offline = t.ingest("during-outage", DISCONNECTED, "成员", group(C))
        time.sleep(2.2)
        assert t.material(DISCONNECTED) is None
    finally:
        roster.release.set()
    assert ref(t.published(DISCONNECTED, group(C))) == ref(offline)

    # 沿用隔离的 Memory，切换到静态群配置，检查前一个配置的动态群不再进入接收范围。
    t.stop_harness()
    t.static = True
    t.console["openQqConfig"] = t.config("onebot-static", **t.onebot_config, allowedGroupId=B)
    pos = len(t.claims)
    t.start_dreaming()
    t.scopes({PRIVATE, group(B), SCOPE}, start=pos)
    t.ingest("static", STATIC_NOTE, "成员", group(B))
    t.published(STATIC_NOTE, group(B))
    result = t.snapshot("切换到静态群")
    assert {p["subject"] for p in result} == {NOTES[PRIVATE_NOTE], NOTES[STATIC_NOTE]}
    assert not t.master_calls and not t.failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("harness", "memory", "output"):
        parser.add_argument(field, type=Path)
    parser.set_defaults(live=False)
    trial = QqDreamTrial(parser.parse_args())
    outcome = "FAIL"
    try:
        run(trial)
        outcome = "PASS"
    finally:
        trial.close()
        trial.report(outcome)
