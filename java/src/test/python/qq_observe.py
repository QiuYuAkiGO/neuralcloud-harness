"""正式 Harness、Memory v2 与回环 OneBot 联调。命令行传入两个 JAR 和新的验收目录。"""
import argparse
import hashlib
import http.client
import json
import os
import platform
import secrets
import socket
import sqlite3
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode
from zipfile import ZipFile
from websockets.exceptions import ConnectionClosed
from websockets.sync.server import serve

A, B, C = 23456, 34567, 45678
ACCOUNT = 12345


def route_fields(request, route="CONVERSATION", *, memory_focus="DEFAULT", author_ref="", query=""):
    """旧版回放和其他渠道只声明 route。受控模型依据收到的工具协议提交本例指定的选择。"""
    fields = {"route": route}
    if "memory_focus" in request["tools"][0]["function"]["parameters"]["properties"]:
        fields.update(memory_focus=memory_focus, author_ref=author_ref, query=query)
    return fields


def correction_feedback(original, received):
    """只提取首次提交后调用方新给的说明，排除模型自己先前提交的工具参数。"""
    return "\n".join(m["content"] for m in received
                     if m not in original and m["role"] != "assistant" and m.get("content"))


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def call(port, path, body=None, method=None, token=None):
    """直接走回环，不依赖机器代理或外部账号。转发器需要保留 Memory 的原始状态码。"""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=45)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        conn.request(method or ("GET" if body is None else "POST"), path,
                     None if body is None else json.dumps(body).encode(), headers)
        res = conn.getresponse()
        return res.status, json.loads(res.read())
    finally:
        conn.close()


def until(check, label, seconds=28):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(.06)
    raise AssertionError("等待超时 " + label)


class Gate:
    """只在测试端截住一次协议交互，可分别在提交前或持久化之后断开 Harness。"""
    def __init__(self):
        self.hit = threading.Event()
        self.release = threading.Event()

    def wait(self):
        assert self.hit.wait(20), "未到达预期协议边界"


class Trial:
    def __init__(self, args):
        self.dir = args.output.resolve()
        self.dir.mkdir(parents=True, exist_ok=False)
        self.hjar, self.mjar = args.harness.resolve(), args.memory.resolve()
        self.token = secrets.token_hex(24)
        self.mp, self.hp = port(), port()
        self.gates, self.sent, self.posts, self.lookups, self.packets = {}, [], [], [], []
        self.roster = [A, B, C]
        self.bad_index = True
        self.rejected_messages = None
        self.corrected_submission = False
        self.children = []
        self.worker = ThreadPoolExecutor(1)
        self.bot = serve(self.onebot, "127.0.0.1", 0)
        threading.Thread(target=self.bot.serve_forever, daemon=True).start()
        trial = self

        class Handler(BaseHTTPRequestHandler):
            def dispatch(self):
                size = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(size)) if size else None
                status, data = trial.route(self.path, body)
                raw = json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                try:
                    self.end_headers()
                    self.wfile.write(raw)
                except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):
                    pass  # 此用例会在真实提交和本地确认之间终止 JVM。

            do_GET = do_POST = dispatch

            def log_message(self, *args):
                pass

        self.proxy = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        endpoint = f"http://127.0.0.1:{self.proxy.server_port}"
        shared = dict(endpoint=endpoint+"/v1/chat/completions", model="trial", httpVersion="HTTP_1_1",
                      connectTimeoutSeconds=3, requestTimeoutSeconds=45, enableThinking="false")
        model = self.config("model", **shared, probePrompt="foreground", probeMaxTokens=8, probeTemperature=0)
        chat = self.config("chat", **shared, apiFormat="OPENAI_CHAT", maxOutputTokens=512,
                           replyPreferences="classpath:/config/chat-worker-misty-reply-preferences.txt")
        client = self.config("client", endpoint=endpoint+"/", characterId="trial", accessTokenEnv="TRIAL_KEY",
                             apiVersion=2, recallMode="scoped")
        (self.dir/"policies.json").write_text(json.dumps({"account": str(ACCOUNT),
                     "policies": {str(A): {"revision": 1, "policy": "OBSERVE"}}}))
        qq = self.config("onebot", endpoint=f"ws://127.0.0.1:{self.bot.socket.getsockname()[1]}/",
                         accountId=ACCOUNT, allowedUserId=67890, accessTokenEnv="TRIAL_KEY",
                         groupPoliciesFile=self.dir/"policies.json")
        self.mc = self.config("memory", port=self.mp, dataDirectory=self.dir/"db", characterId="trial",
                              sourceInstance="neuralcloud-java", accessTokenEnv="TRIAL_KEY")
        self.hc = self.config("console", listenHost="127.0.0.1", listenPort=self.hp, auditDirectory=self.dir/"audit",
                              chatWorkerConfig=chat, chatWorkerRolePrompt="classpath:/config/chat-worker-misty-prompt.txt",
                              routerConfig=model, routerRolePrompt="classpath:/config/router-role-prompt.txt",
                              masterConfig=model, masterMaxTokens=512, masterTemperature=0, openQqConfig=qq,
                              memoryConfig=client, gpuMetrics="NONE", metricsIntervalSeconds=10, metricsTimeoutSeconds=5)
        self.runs = 0

    def config(self, name, **props):
        file = self.dir/(name+".properties")
        file.write_text("".join(f"{k}={v.as_posix() if isinstance(v, Path) else v}\n" for k, v in props.items()),
                        encoding="utf-8")
        return file

    def arm(self, key):
        gate = self.gates[key] = Gate()
        return gate

    def checkpoint(self, key):
        gate = self.gates.pop(key, None)
        if gate:
            gate.hit.set()
            assert gate.release.wait(45), key

    def route(self, path, body):
        if path == "/v1/chat/completions":
            if not body.get("tools"):
                self.checkpoint("foreground")
                return 200, {"model": "trial", "choices": [{"message": {"content": "ready"}, "finish_reason": "stop"}]}
            tools = [t["function"]["name"] for t in body["tools"]]
            if tools == ["select_reply"]:
                self.checkpoint("qq-foreground")
                return 200, {"model": "trial", "choices": [{"finish_reason": "tool_calls", "message": {"content": None,
                       "tool_calls": [{"id": "select", "type": "function", "function": {
                       "name": "select_reply", "arguments": json.dumps({"decision": "SKIP"})}}]}}]}
            assert tools == ["submit_impression"], body
            packet = json.loads(body["messages"][1]["content"])
            self.packets.append(packet)
            self.checkpoint("evaluate")
            texts = [s["原文"] for s in packet["本批原文"]]
            imp = {"impression": "", "evidence": []}
            if texts[0].startswith("A"):
                imp = {"impression": "根据群里两条记录，我目前倾向于继续核对进展", "evidence": [1, 2]}
            if self.bad_index and imp["evidence"]:
                self.bad_index = False
                self.rejected_messages = body["messages"]
                imp["evidence"] = [len(texts) + 1]
            elif self.rejected_messages is not None and imp["evidence"]:
                feedback = correction_feedback(self.rejected_messages, body["messages"])
                self.corrected_submission = "evidence" in feedback and (
                    str(len(texts) + 1) in feedback or str(len(texts)) in feedback)
                self.rejected_messages = None
            function = {"name": "submit_impression", "arguments": json.dumps(imp)}
            return 200, {"model": "trial", "choices": [{"finish_reason": "tool_calls", "message": {"content": None,
                         "tool_calls": [{"id": "observation", "type": "function", "function": function}]}}]}
        if path == "/v2/candidates":
            self.posts.append(body)
            offline = "candidate-offline" in self.gates
            self.checkpoint("candidate-before")
            if offline:
                self.checkpoint("candidate-offline")
                return 503, {"code": "TEST_UNAVAILABLE"}
        if path.startswith("/v2/receipts?"):
            self.lookups.append(path)
        status, data = call(self.mp, path, body, token=self.token)
        if status == 200 and path == "/v2/candidates":
            self.checkpoint("candidate-after")
        if status == 200 and path == "/v2/ingest" and body["content"] == "A原文已提交未确认":
            self.checkpoint("raw-after")
        return status, data

    def onebot(self, ws):
        assert ws.request.headers["Authorization"] == "Bearer "+self.token
        try:
            for frame in ws:
                cmd = json.loads(frame)
                if cmd["action"] == "get_login_info":
                    data = {"user_id": ACCOUNT}
                elif cmd["action"] == "get_group_list":
                    self.checkpoint("roster")
                    data = [dict(group_id=g, group_name=str(g), member_count=3, max_member_count=20) for g in self.roster]
                    self.ws = ws
                else:
                    self.sent.append(cmd)
                    data = {"message_id": 90000}
                ws.send(json.dumps(dict(status="ok", retcode=0, echo=cmd["echo"], data=data)))
        except ConnectionClosed:
            pass

    def start(self, name, args):
        # 故障演练会强制停止并重启 JVM。各进程独立解压 SQLite 原生库，避免系统临时目录中的 DLL 锁冲突。
        native = self.dir / (name + "-native")
        native.mkdir()
        library_args = ["-Dorg.sqlite.tmpdir=" + str(native)]
        if os.name == "nt" and platform.machine().lower() in ("amd64", "x86_64"):
            with ZipFile(self.mjar if name == "memory" else self.hjar) as jar:
                (native / "sqlitejdbc.dll").write_bytes(jar.read("org/sqlite/native/Windows/x86_64/sqlitejdbc.dll"))
            library_args.append("-Dorg.sqlite.lib.path=" + str(native))
        with (self.dir/(name+".log")).open("wb") as log:
            proc = subprocess.Popen(["java", *library_args, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", *map(str, args)],
                                    env=dict(os.environ, TRIAL_KEY=self.token), stdout=log, stderr=log)
        self.children.append(proc)
        return proc

    def ready(self, check, process):
        def check_running():
            assert process.poll() is None, "JVM 已退出，见 " + str(self.dir)
            try:
                return check()
            except (ConnectionError, http.client.HTTPException):
                return False
        until(check_running, "服务就绪")

    def harness(self):
        self.runs += 1
        self.hproc = self.start(f"harness-{self.runs}", ["-cp", self.hjar, "neuralcloud.controlplane.app.ConsoleMain",
                                                         "--config", self.hc])
        self.ready(lambda: self.panel()["connected"], self.hproc)

    def stop_harness(self):
        self.hproc.kill()
        self.hproc.wait(10)

    def mem(self, path, body=None):
        status, data = call(self.mp, "/v2/"+path, body, token=self.token)
        assert status == 200, (status, data)
        return data

    def panel(self):
        status, data = call(self.hp, "/api/qq/groups")
        assert status == 200, (status, data)
        return data

    def room(self, group):
        return next(r for r in self.panel()["groups"] if r["id"] == str(group))

    def status(self, group):
        return self.room(group)["observation"]

    def policy(self, group, mode):
        rev = self.room(group)["revision"]
        assert call(self.hp, f"/api/qq/groups/{group}/policy", {"policy": mode, "revision": rev}, "PUT")[0] == 200

    def emit(self, group, number, text=None, at=True):
        msg = [{"type": "text", "data": {"text": text or f"{group}-{number}"}}]
        if at:
            msg.insert(0, {"type": "at", "data": {"qq": str(ACCOUNT)}})
        self.ws.send(json.dumps(dict(post_type="message", message_type="group", sub_type="normal", self_id=ACCOUNT,
                          user_id=54321, message_id=number, group_id=group, message=msg)))

    def events(self, group):
        return call(self.hp, f"/api/audit/chains/qq-group-{ACCOUNT}-{group}")[1]["events"]

    def block_foreground(self):
        gate = self.arm("foreground")
        future = self.worker.submit(call, self.hp, "/api/nodes/master/probe", {}, "POST")
        gate.wait()
        return gate, future

    def close(self):
        for p in reversed(self.children):
            if p.poll() is None:
                p.kill()
                p.wait(10)
        self.proxy.shutdown()
        self.bot.shutdown()
        self.worker.shutdown(wait=True)


def context(group, role="ORCHESTRATOR"):
    return dict(character_id="trial", actor_id=role, role=role, session_id="test",
                conversation_id=f"group:onebot:{ACCOUNT}:{group}")


def run(t):
    mp = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", mp)
    for i in range(25):
        t.mem("ingest", dict(context=context(A), source_instance="neuralcloud-java", event_id=f"old-{i}",
                             kind="observation", content="启用前原文", occurred_at=datetime.now(timezone.utc).isoformat()))
    t.harness()
    assert t.status(A)["pending"] == 0
    t.policy(B, "OBSERVE")
    t.policy(C, "OBSERVE")
    for i in range(1, 10):
        t.emit(A, i, "A"+str(i) if i != 3 else "A" + "整"*3000 + "完整尾标")
    for i in range(1, 11):
        t.emit(B, i)
    until(lambda: t.status(B)["pending"] == 10, "群间隔离")
    raw = t.arm("raw-after")
    t.emit(A, 10, "A原文已提交未确认")
    raw.wait()
    t.stop_harness()
    raw.release.set()
    t.harness()
    t.emit(A, 10, "A原文已提交未确认")
    assert [t.status(g)["pending"] for g in [A, B]] == [10, 10]
    assert any("event_id=" in path for path in t.lookups)

    t.policy(C, "OBSERVE_REPLY")
    foreground = t.arm("qq-foreground")
    t.emit(C, 50, "无需参与的前台群消息", at=False)
    foreground.wait()
    for g in [A, B]:
        for i in range(11, 21):
            t.emit(g, i, "A"+str(i) if g == A else None)
    until(lambda: all(t.status(g)["state"] == "QUEUED" for g in [A, B]), "前台优先")
    assert not t.packets
    conflict, lost = t.arm("candidate-before"), t.arm("candidate-after")
    foreground.release.set()
    until(lambda: any(e["type"]=="qq_participation" for e in t.events(C)), "QQ 前台完成")
    t.policy(C, "OBSERVE")
    conflict.wait()
    old = t.posts[0]
    assert len(old["source_refs"]) == 2
    first, second = old["source_refs"]
    for ref, op in [(first, "REPLACE"), (second, "RETRACT")]:
        t.mem("corrections", dict(context=context(A), source_instance="neuralcloud-java",
              event_id=op, operation=op, record_id=ref["record_id"], expected_revision=1, reason="联调修订",
              **({"content": "A更正原文"} if op == "REPLACE" else {})))
    offline = t.arm("candidate-offline")
    conflict.release.set()
    offline.wait()
    offline.release.set()
    lost.wait()
    durable_cmd = t.posts[-1]
    assert durable_cmd["event_id"] != old["event_id"]
    assert durable_cmd["source_refs"][0] == dict(first, revision=2)
    assert second not in durable_cmd["source_refs"]
    t.policy(A, "OFF")  # 已发出且远端已提交，旧策略只按原键对账。
    t.stop_harness()
    lost.release.set()
    t.harness()
    t.policy(A, "OBSERVE")
    until(lambda: all(t.status(g)["lastBatchId"] for g in [A, B]), "两批完成")
    assert [t.status(g)["lastResult"] for g in [A, B]] == ["IMPRESSION", "NO_IMPRESSION"]
    assert [t.status(g)["pending"] for g in [A, B]] == [0, 0] and t.corrected_submission
    first_batches = {g: t.status(g) for g in [A, B]}
    def receipt(cmd):
        return t.mem("receipts?"+urlencode(dict(source_instance="neuralcloud-java", event_id=cmd["event_id"])))
    assert receipt(old)["status"] == "NOT_FOUND"
    actual = receipt(durable_cmd)
    assert actual["status"] == "DURABLE" and len(t.posts) == 3 and t.posts[1] == t.posts[2]
    assert any("qq-impression" in path for path in t.lookups)
    assert any("完整尾标" in row["原文"] for packet in t.packets for row in packet["本批原文"])
    targets = [dict(record_id=r["record_id"]) for r in [first, actual]]
    outsider = {**context(A, "MASTER"), "conversation_id": "c2c:onebot:12345:67890",
                "qq": {"account_id": "12345", "peer_id": "67890"}}
    for ctx in [context(B, "MASTER"), outsider]:
        rows = t.mem("read", dict(context=ctx, kind="HISTORY", use="CITE", targets=targets))["items"]
        assert [r["status"] for r in rows] == ["NOT_FOUND", "NOT_FOUND"]
    completed = until(lambda: next((e for e in t.events(A) if e["type"]=="observe_completed"), None), "批次审计")
    events = t.events(A)
    claimed = next(e for e in events if e["type"]=="observe_claimed")
    assert len({(r["runId"], r["eventId"]) for r in claimed["data"]["sources"]}) == 20
    assert {r["messageId"] for r in claimed["data"]["sources"]} == {str(i) for i in range(1, 21)}
    assert completed["data"]["receipt"] == actual and completed["data"]["batchId"] == claimed["data"]["batchId"]
    submission = [e["data"] for e in events if e["type"] == "observe_submission"][-1]
    evidence = next(e["data"] for e in events if e["type"] == "observe_evidence" and
                    e["data"]["inputId"] == submission["inputId"])
    assert submission["toolCallId"] and submission["submission"]["evidence"] == durable_cmd["source_refs"]
    assert evidence["originals"][0]["text"] == "A更正原文" and evidence["originals"][0]["ref"] == dict(first, revision=2)

    # 运行中的旧版本取消；重新启用后的单条原文在无新消息时独立触发时间条件。
    hold = t.arm("evaluate")
    for i in range(20):
        t.emit(C, 100+i, "A旧策略会产生印象"+str(i))
    hold.wait()
    t.policy(C, "OFF")
    hold.release.set()
    until(lambda: any(e["type"]=="observe_cancelled" for e in t.events(C)), "旧策略取消")
    assert t.status(C)["state"] == "DISABLED" and len(t.posts) == 3
    t.policy(C, "OBSERVE")
    assert t.status(C)["pending"] == 0
    t.emit(C, 999)
    until(lambda: t.status(C)["pending"] == 1, "重新启用")
    t.stop_harness()
    # 仅在停止的测试副本设置还差 4 秒到期的首条确认时间；正式启动入口与调度时钟照常工作。
    with sqlite3.connect(t.dir/"audit"/"memory-v2-trial.sqlite") as db:
        db.execute("UPDATE observe_sources SET confirmed=? WHERE room=? AND message='999'",
                   (int(time.time()*1000)-1800000+4000, C))
    hold = t.arm("evaluate")
    t.harness()
    assert t.status(C)["state"] == "WAITING"
    hold.wait()
    batch = t.status(C)["currentBatchId"]
    roster = t.arm("roster")
    t.ws.close()
    until(lambda: not t.panel()["connected"], "断线")
    hold.release.set()
    roster.wait()
    assert t.status(C)["pending"] == 1 and t.status(C)["currentBatchId"] == batch
    roster.release.set()
    until(lambda: t.status(C)["lastBatchId"] == batch, "重连续办")
    chain_c = t.events(C)
    assert any(e["type"]=="observe_queued" and e["data"]["reason"]=="AGE" for e in chain_c)

    # 等待中的新批次切换到观察与回复后撤销；已完成的旧批次仍可从群审计回查。
    fg, front = t.block_foreground()
    for i in range(50, 70):
        t.emit(B, i)
    until(lambda: t.status(B)["state"] == "QUEUED", "策略切换前入队")
    t.policy(B, "OBSERVE_REPLY")
    fg.release.set()
    assert front.result()[0] == 200
    t.policy(B, "OBSERVE")
    assert t.status(B)["pending"] == 0 and t.status(B)["lastResult"] == "NO_IMPRESSION"
    # 超预算的批次保留完整前缀和余量；同群上一条持久印象可作对照，不能被当作证据编号。
    for i in range(20):
        t.emit(A, 2000+i, "续"+"文"*3598+"末")
    until(lambda: t.status(A)["lastResult"] == "NO_IMPRESSION", "长原文分批")
    assert t.status(A)["pending"] == 11
    material = t.packets[-1]
    assert len(material["本批原文"]) == 9 and all(len(s["原文"]) == 3600 for s in material["本批原文"])
    assert material["已有印象"] and len(t.posts) == 3
    t.policy(A, "OFF")
    t.policy(A, "OBSERVE")
    assert t.status(A)["pending"] == 0

    t.emit(C, 1000)
    until(lambda: t.status(C)["pending"] == 1, "退群前进度")
    t.roster.remove(C)
    t.ws.close()
    until(lambda: t.panel()["connected"] and all(int(r["id"]) != C for r in t.panel()["groups"]), "确认退群")
    t.roster.append(C)
    t.ws.close()
    until(lambda: t.panel()["connected"] and any(int(r["id"]) == C for r in t.panel()["groups"]), "重新入群")
    assert t.room(C)["policy"] == "OFF" and t.status(C)["state"] == "DISABLED"
    assert not t.sent, t.sent
    assert not any(e["type"]=="qq_send_requested" for g in [A, B, C] for e in t.events(g))
    def digest(path):
        digest = hashlib.sha256()
        with path.open("rb") as file:
            for chunk in iter(lambda: file.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    report = dict(harnessSha256=digest(t.hjar), memorySha256=digest(t.mjar), firstBatches=first_batches,
                  rooms=[t.room(g) for g in [A,B,C]], receipt=actual, rejected=old["event_id"],
                  confirmed=durable_cmd["event_id"], source_refs=durable_cmd["source_refs"],
                  runs=t.runs, sends=len(t.sent), audit=str(t.dir/"audit"))
    (t.dir/"result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS 启用水位、分群去重、P3 排队、时间触发、来源修订与撤回、两个崩溃窗口、策略与受众隔离；QQ 发送 0 次。")
    print(t.dir/"result.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["harness", "memory", "output"]:
        parser.add_argument(name, type=Path)
    trial = Trial(parser.parse_args())
    try:
        # 本次验收要求其他群与私聊均无法引用观察原文和印象，显式启用 Memory 会话隔离。
        with trial.mc.open("a", encoding="utf-8") as config:
            config.write("sessionIsolation=true\n")
        run(trial)
    finally:
        trial.close()
