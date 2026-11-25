"""从 ConsoleMain 验收 Dreaming。传入两个正式 JAR 和全新目录，--live 使用 JAR 内的 Hydrogen 配置。"""
import argparse
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zipfile import ZipFile

from qq_observe import Trial, call, port, until


SCOPE = "console:dreaming-north"
OUTSIDE = SCOPE + "-private"
PERSON = "我是林澄。过去六次在北岸站交班，我都会先要一份纸质清单，逐项打钩再接手。今后在这里交班我也想沿用这种方式。"
TOPIC = "青砾星图是北岸站持续维护的长期项目。最近六版的发布记录都显示，只有经过两台望远镜交叉校准的星点才能入图；这个发布标准会继续执行。"
UPDATE = "我是林澄。北岸站最近三次交班我都改用语音留言了，比先前的纸质清单更适合我。今后在这里交班，请以语音留言作为我的首选。"
CORRECTION = "我是林澄。更正我的交班说明：最近三次交班实际用的是磁贴板，我一直把它误记成了语音留言。今后在北岸站，我的首选明确是磁贴板。"
SECRET = "隔壁会话的暗号是绛云铜铃，仅限本会话。"
GEMINI_PATH = "/v1beta/models/dream-trial:generateContent"
OPENAI_PATH = "/dream/v1/chat/completions"
ROUTER_PATH = "/router/v1/chat/completions"
MASTER_PATH = "/master/v1/chat/completions"
UNRELATED_ALIAS = "栖桥"


def context(conversation, role="ORCHESTRATOR"):
    return dict(character_id="trial", actor_id="DREAMING" if conversation == "dreaming:trial" else role,
                role=role, session_id=conversation, conversation_id=conversation)


def ref(item):
    return {key: item[key] for key in ("record_id", "revision")}


def tool_call(name, arguments):
    function = dict(name=name, arguments=json.dumps(arguments, ensure_ascii=False))
    message = dict(content=None, tool_calls=[dict(id="submission", type="function", function=function)])
    return dict(model="trial", choices=[dict(message=message, finish_reason="tool_calls")])


def hydrogen(jar):
    """保留正式资源中的后端、模型、鉴权环境和代理；只把合成会话交给它。"""
    with ZipFile(jar) as bundle:
        content = bundle.read("config/dreaming-gemini.properties").decode("utf-8")
    props = dict(line.strip().split("=", 1) for line in content.splitlines()
                 if line.strip() and not line.lstrip().startswith(("#", "!")))
    assert props["protocol"] == "GEMINI" and props["model"] == "gemini-4-hydrogen", "需要项目的 Hydrogen 配置"
    assert props["endpoint"].startswith("https://") and props["model"] + ":generateContent" in props["endpoint"]
    if not os.environ.get(props["apiKeyEnv"]):
        raise ValueError("真实模型需要设置环境变量 " + props["apiKeyEnv"])
    return props


class DreamTrial(Trial):
    """复用现有 JVM 和回环 HTTP 助手；本例不启动任何渠道或生产数据服务。"""
    def __init__(self, args):
        self.hjar, self.mjar = args.harness.resolve(), args.memory.resolve()
        self.live = args.live
        self.model_config = hydrogen(self.hjar) if self.live else None
        self.dir = args.output.resolve()
        self.dir.mkdir(parents=True, exist_ok=False)
        self.out = self.dir / "published" if self.live else self.dir / "audit" / "impressions"
        self.token = secrets.token_hex(24)
        self.mp, self.hp = port(), port()
        self.children, self.gates = [], {}
        self.batches, self.commits, self.lookups, self.model_calls, self.master_calls = [], [], [], [], []
        self.failures, self.stages, self.evidence, self.epochs = [], {}, {}, {}
        self.mode = "valid"
        self.runs = 0
        self.worker = ThreadPoolExecutor(1)
        trial = self

        class Handler(BaseHTTPRequestHandler):
            def dispatch(self):
                n = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(n)) if n else None
                try:
                    status, value = trial.route(self.path, body, self.headers)
                except (AssertionError, KeyError, ValueError) as error:
                    trial.failures.append(f"{self.path} {type(error).__name__} {error}")
                    status, value = 500, {"error": "验收协议不匹配，详见测试进程"}
                data = json.dumps(value, ensure_ascii=False).encode("utf-8")
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                    pass  # 用例会主动中止尚未收到提交回执的宿主。

            do_GET = do_POST = dispatch

            def log_message(self, *args):
                pass

        self.proxy = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.proxy.server_port}"
        model = dict(model="trial", httpVersion="HTTP_1_1", connectTimeoutSeconds=3,
                     requestTimeoutSeconds=30, enableThinking="false")
        probe = dict(probePrompt="就绪", probeMaxTokens=8, probeTemperature=0)
        master = self.config("master", endpoint=self.base + MASTER_PATH, **model, **probe)
        router = self.config("router", endpoint=self.base + ROUTER_PATH, **model, **probe)
        chat = self.config("chat", endpoint=self.base + "/chat/v1/chat/completions", **model,
                           apiFormat="OPENAI_CHAT", maxOutputTokens=512,
                           replyPreferences="classpath:/config/chat-worker-misty-reply-preferences.txt")
        client = self.config("client", endpoint=self.base + "/", characterId="trial", accessTokenEnv="TRIAL_KEY",
                             apiVersion=2, recallMode="scoped")
        self.console = dict(listenHost="127.0.0.1", listenPort=self.hp, auditDirectory=self.dir / "audit",
                            chatWorkerConfig=chat, chatWorkerRolePrompt="classpath:/config/chat-worker-misty-prompt.txt",
                            routerConfig=router, routerRolePrompt="classpath:/config/router-role-prompt.txt",
                            masterConfig=master, masterMaxTokens=512, masterTemperature=0, memoryConfig=client,
                            gpuMetrics="NONE", metricsIntervalSeconds=10, metricsTimeoutSeconds=5)
        self.mc = self.config("memory", port=self.mp, dataDirectory=self.dir / "db", characterId="trial",
                              sourceInstance="neuralcloud-java", accessTokenEnv="TRIAL_KEY")

    def settings(self, gemini=False):
        if self.live:
            return dict(self.model_config, intervalSeconds=1, sourceScopes=SCOPE, outputDirectory=self.out)
        settings = dict(protocol="OPENAI_CHAT", endpoint=self.base + OPENAI_PATH, model="trial",
                        connectTimeoutSeconds=3, requestTimeoutSeconds=15, maxOutputTokens=1024,
                        batchSize=16, intervalSeconds=1, sourceScopes=SCOPE)
        if gemini:
            settings.update(protocol="GEMINI", model="dream-trial", endpoint=self.base + GEMINI_PATH, apiKeyEnv="TRIAL_KEY")
        return settings

    def launch(self, settings, console):
        self.runs += 1
        model = self.config("dream", **settings)
        config = self.config("console", **console, dreamingConfig=model)
        self.hproc = self.start("harness-" + str(self.runs), ["-cp", self.hjar,
                                "neuralcloud.controlplane.app.ConsoleMain", "--config", config])

    def wait_for(self, check, label):
        def running():
            assert not self.failures, self.failures
            assert self.hproc.poll() is None, f"Harness 提前退出，见 {self.dir}/harness-{self.runs}.log"
            return check()
        return until(running, label, seconds=150 if self.live else 32)

    def start_dreaming(self, gemini=False):
        before = len(self.batches)
        self.launch(self.settings(gemini), self.console)
        self.wait_for(lambda: len(self.batches) > before, "Dreaming 开始向 Memory 取证")

    def reject(self, settings, console, field):
        self.launch(settings, console)
        assert self.hproc.wait(timeout=12) == 2, f"应拒绝配置 {field}"
        log = (self.dir / f"harness-{self.runs}.log").read_text(encoding="utf-8")
        assert field.lower() in log.lower(), f"配置错误应指明 {field}"

    def model_reply(self, path, body, headers):
        """两种真实传输协议共享同一组模型输入断言；挡板只提供确定性推理返回。"""
        if path == OPENAI_PATH:
            function, = [t["function"] for t in body["tools"]]
            assert function["name"] == "submit_dreaming"
            assert body["tool_choice"] in ("required", {"type": "function", "function": {"name": "submit_dreaming"}})
            schema = function["parameters"]
            user, = [m["content"] for m in body["messages"] if m["role"] == "user"]
        else:
            assert headers.get("x-goog-api-key") == self.token, "Gemini 需要用配置密钥签入"
            config = body["generationConfig"]
            assert config["responseMimeType"] == "application/json"
            schema = config.get("responseJsonSchema") or config["responseSchema"]
            user = "".join(part["text"] for msg in body["contents"] if msg.get("role", "user") == "user"
                           for part in msg["parts"])
        assert "changes" in schema["required"] and "changes" in schema["properties"]
        packet = json.loads(user)
        evidence, profiles = packet["evidence"], packet["profiles"]
        assert evidence and SECRET not in user
        assert all(row["conversation_id"] == SCOPE for row in evidence)
        mode = self.mode
        self.model_calls.append(dict(protocol="GEMINI" if path == GEMINI_PATH else "OPENAI_CHAT", mode=mode, input=packet))
        if mode == "unavailable":
            return 503, {"error": "模型暂时不可用"}
        by_text = {e["content"]: e for e in evidence}
        changes = []

        def change(kind, subject, material, text, reason):
            version = next((p["version"] for p in profiles if (p["kind"], p["subject"]) == (kind, subject)), 0)
            source = ref(by_text[material])
            if mode == "stale":
                source["revision"] -= 1
            changes.append(dict(kind=kind, subject=subject, expected_version=version, text=text,
                                scope="仅限北岸站交班与观测记录", reason=reason, source_refs=[source]))

        if CORRECTION in by_text:
            change("person", "林澄", CORRECTION, "我现在倾向于用磁贴板和林澄交班。", "更正说明核实了交班工具。")
        elif UPDATE in by_text:
            change("person", "林澄", UPDATE, "我会先给林澄留一段语音，再确认交班。", "近三次交接和新的明确偏好改变了我的判断。")
        elif PERSON in by_text:
            subject = UNRELATED_ALIAS if mode == "guess-alias-once" else "林澄"
            change("person", subject, PERSON, "我觉得和林澄交班时先备好纸质清单会更顺利。", "连续六次交班和本人表述一致。")
            if mode == "guess-alias-once":
                self.mode = "valid"  # 只有重新调用模型才会得到正确称呼。
        if TOPIC in by_text and not any(p["kind"] == "topic" and p["subject"] == "青砾星图" for p in profiles):
            change("topic", "青砾星图", TOPIC, "我把交叉校准看作青砾星图发布前的必要步骤。", "六版发布记录采用同一标准。")
        result = {"changes": changes}
        if path == OPENAI_PATH:
            return 200, tool_call("submit_dreaming", result)
        content = dict(role="model", parts=[{"text": json.dumps(result, ensure_ascii=False)}])
        return 200, dict(candidates=[dict(content=content, finishReason="STOP")], modelVersion="dream-trial")

    def route(self, path, body, headers):
        if path.startswith("/master/"):
            self.master_calls.append(path)
            return 503, {"error": "验收期间 Master 离线"}
        if path == ROUTER_PATH:
            self.checkpoint("foreground")
            return 200, tool_call("select_route", {"route": "CONVERSATION"})
        if path in (GEMINI_PATH, OPENAI_PATH):
            assert not self.live, "真实模型模式调用了回环模型"
            return self.model_reply(path, body, headers)
        assert path.startswith("/v2/"), "未预期的模型或服务调用"
        assert headers.get("Authorization") == "Bearer " + self.token, "Memory 请求应携带本轮独立凭据"
        if path == "/v2/dreaming/commit" and body["changes"] and self.mode == "valid":
            self.checkpoint("before-commit")
        status, data = call(self.mp, path, body, token=self.token)
        if path == "/v2/dreaming/batch" and status == 200:
            assert set(body["scopes"]) == {SCOPE} and SECRET not in json.dumps(data, ensure_ascii=False)
            self.batches.append(data)
        elif path == "/v2/dreaming/commit":
            if status == 422:
                # 在明确拒绝送回宿主之前查回执，此时重新推理尚无机会提交。
                query = dict(context=context("dreaming:trial"), source_instance=body["source_instance"],
                             event_id=body["event_id"])
                assert self.mem("dreaming/receipt", query)["status"] == "NOT_FOUND"
            self.commits.append(dict(command=body, status=status, response=data))
            if status == 200 and body["changes"]:
                self.checkpoint("after-commit")
        elif path == "/v2/dreaming/receipt":
            self.lookups.append(dict(event_id=body["event_id"], result=data))
        return status, data

    def ingest(self, event, content, speaker, conversation=SCOPE):
        body = dict(context=context(conversation), source_instance="neuralcloud-java", event_id=event,
                    kind="observation", content=content, occurred_at=datetime.now(timezone.utc).isoformat(),
                    speaker=speaker, source_venue="北岸观测站")
        receipt = self.mem("ingest", body)
        assert receipt["status"] == "DURABLE"
        self.evidence[event] = receipt
        return receipt

    def correct(self, prior, content=None):
        body = dict(context=context(SCOPE), source_instance="neuralcloud-java",
                    event_id="replace" if content else "retract", operation="REPLACE" if content else "RETRACT",
                    record_id=prior["record_id"], expected_revision=prior["revision"], reason="发言者核对原话")
        if content:
            body["content"] = content
        receipt = self.mem("corrections", body)
        assert receipt["status"] == "DURABLE" and receipt["revision"] == prior["revision"] + 1
        self.evidence[body["event_id"]] = receipt
        return receipt

    def files(self):
        return {p.relative_to(self.out).as_posix(): p.read_text(encoding="utf-8") for p in self.out.rglob("*.md")}

    def profiles(self):
        return self.mem("dreaming/profiles", {"context": context("dreaming:trial")})["profiles"]

    def epoch_counts(self):
        # README 明确列出 Epoch 和已纳入主干的段表，使用公开审计数据库的只读连接。
        path = self.dir / "audit" / "master-epochs.sqlite"
        with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as con:
            return dict(epochs=con.execute("SELECT count(*) FROM epoch").fetchone()[0],
                        members=con.execute("SELECT count(*) FROM member").fetchone()[0])

    def snapshot(self, stage):
        """按档案正文定位文件，核对适用范围、理由、精确引用与唯一发布路径，不约定文件名。"""
        profiles = self.profiles()
        def synchronized():
            files = self.files()
            if len(files) != len(profiles):
                return False
            paths = set()
            for p in profiles:
                assert p["author"] == "DREAMING" and "我" in p["text"], p
                prefix = "people/" if p["kind"] == "person" else "topics/"
                matches = [name for name, doc in files.items() if name.startswith(prefix) and
                           all(p[k] in doc for k in ("subject", "text", "scope", "reason")) and
                           all(re.search(re.escape(r["record_id"]) + r".{0,100}?(?<!\d)" + str(r["revision"]) + r"(?!\d)",
                                         doc, re.S) for r in p["source_refs"])]
                if len(matches) != 1 or matches[0] in paths:
                    return False
                paths.add(matches[0])
            return {"profiles": profiles, "markdown": files}
        result = self.wait_for(synchronized, stage + " 的公开档案和 Markdown 一致")
        self.stages[stage] = result
        print(f"{stage}，共 {len(profiles)} 份当前档案。", flush=True)
        return profiles

    def wait_committed(self, source):
        def matching():
            return next((row for row in reversed(self.commits) if row["status"] == 200 and
                         any(ref(source) in c["source_refs"] for c in row["command"]["changes"])), None)
        row = self.wait_for(matching, "使用证据 " + source["record_id"] + "@" + str(source["revision"]) + " 提交")
        assert row["response"]["status"] == "DURABLE" and row["response"]["through"] == row["command"]["through"]
        assert row["command"]["context"]["role"] == "ROUTER" and row["command"]["context"]["actor_id"] == "DREAMING"
        return row

    def report(self, outcome):
        # 服务返回的材料、命令与回执可在失败后复盘。真实端点的合成材料保留在 Memory 出口，
        # 模型输入只有在回环服务实际收到时才记录。配置只写入模型身份和地址。
        batches = {(b["stream_id"], b["policy_version"], b["after"], b["through"]): b for b in self.batches
                   if b["through"] > b["after"]}
        backend = ({k: self.model_config[k] for k in ("protocol", "model", "endpoint")} if self.live
                   else {"protocols": ["OPENAI_CHAT", "GEMINI"], "model": "回环挡板"})
        report = dict(outcome=outcome, backend=backend, evidence=self.evidence, stages=self.stages,
                      batches=list(batches.values()), commits=self.commits, receiptLookups=self.lookups,
                      masterRequests=len(self.master_calls), epoch=self.epochs, protocolErrors=self.failures)
        if not self.live:
            report["modelRequests"] = self.model_calls
        output = self.dir / "result.json"
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(outcome, output)

    def close(self):
        for gate in self.gates.values():
            gate.release.set()
        for child in reversed(self.children):
            if child.poll() is None:
                child.kill()
                child.wait(10)
        self.worker.shutdown(wait=True)
        self.proxy.shutdown()
        self.proxy.server_close()


def profile_for(profiles, kind, source, subject_part):
    found = [p for p in profiles if p["kind"] == kind and ref(source) in p["source_refs"] and subject_part in p["subject"]]
    assert len(found) == 1, (kind, subject_part, ref(source), profiles)
    return found[0]


def run(t):
    if not t.live:
        # 真实 Memory 登记另一个人的别称。本批材料只提到林澄，模型无权获知那条绑定。
        people = {"character_id": "trial", "people": [{"subject": "南音", "aliases": [UNRELATED_ALIAS]}]}
        (t.dir / "people.json").write_text(json.dumps(people, ensure_ascii=False), encoding="utf-8")
        with t.mc.open("a", encoding="utf-8") as config:
            config.write("dreamingPeopleFile=people.json\n")
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    if not t.live:
        t.reject(dict(t.settings(), batchSize=17), t.console, "batchSize")
        t.reject(t.settings(), {k: v for k, v in t.console.items() if k != "memoryConfig"}, "memory")
        t.reject({k: v for k, v in t.settings(gemini=True).items() if k != "apiKeyEnv"}, t.console, "apiKeyEnv")
    t.start_dreaming()
    t.wait_for(lambda: sum(b["through"] == b["after"] for b in t.batches) >= 2, "连续两次空批次")
    assert not t.model_calls and not t.commits and not t.files()
    # 正式 Master 独占 Epoch 审计库。两次核对均先等 JVM 退出，再由 SQLite 读取已提交的主库与 WAL。
    t.stop_harness()
    t.epochs["before"] = t.epoch_counts()
    t.start_dreaming()
    if not t.live:
        t.mode = "guess-alias-once"

    # 挡住一个正式 Router 前台请求，期间写入两条可见原文和一条名称近似的隔离会话原文。
    busy = t.arm("foreground")
    future = t.worker.submit(call, t.hp, "/api/nodes/router/messages", dict(chain="hold-router", text="和我打个招呼。"))
    try:
        busy.wait()
        private = t.ingest("outside", SECRET, "隔壁成员", OUTSIDE)
        person = t.ingest("initial-person", PERSON, "林澄")
        topic = t.ingest("initial-topic", TOPIC, "北岸站记录员")
        time.sleep(2.2)  # 观察范围超过两个配置的调度周期。
        assert not t.model_calls and not t.commits and not t.files()
    finally:
        busy.release.set()
    assert future.result(timeout=8)[0] == 200
    if not t.live:
        rejected = t.wait_for(lambda: next((r for r in t.commits if r["status"] == 422), None),
                              "Memory 明确拒绝本批未提示的人物别称")
        command = rejected["command"]
        assert rejected["response"]["code"] == "INVALID_FIELD" and "subject" in rejected["response"]["field"]
        assert any(c["kind"] == "person" and c["subject"] == UNRELATED_ALIAS for c in command["changes"])
        guessed, = [c["input"] for c in t.model_calls if c["mode"] == "guess-alias-once"]
        assert not any(UNRELATED_ALIAS in p["aliases"] for p in guessed.get("person_names", []))
    written = t.wait_committed(person)
    if not t.live:
        assert any(c["mode"] == "valid" and c["input"]["evidence"] == guessed["evidence"] for c in t.model_calls)
        assert all(command[key] == written["command"][key] for key in ("stream_id", "policy_version", "after", "through"))
        assert sum(r["command"] == command for r in t.commits) == 1
    t.wait_committed(topic)
    initial = t.snapshot("新增")
    old_person = profile_for(initial, "person", person, "林澄")
    old_topic = profile_for(initial, "topic", topic, "青砾星图")
    assert old_person["version"] == old_topic["version"] == 1

    # 先让 Memory 实际提交，再在原回执抵达宿主前杀进程。重启须按原键对账并恢复文件。
    lost = t.arm("after-commit")
    try:
        delta = t.ingest("update-person", UPDATE, "林澄")
        t.wait_for(lost.hit.is_set, "Memory 已提交增量，宿主仍未收到回执")
        written = t.wait_committed(delta)
        changed = next(c for c in written["command"]["changes"] if c["kind"] == "person" and ref(delta) in c["source_refs"])
        assert changed["subject"] == old_person["subject"] and changed["expected_version"] == old_person["version"]
        assert not any(changed["text"] in doc for doc in t.files().values()), "在收到回执前发布了新正文"
        t.stop_harness()
    finally:
        lost.release.set()
    t.start_dreaming(gemini=True)
    key = written["command"]["event_id"]
    t.wait_for(lambda: any(r["event_id"] == key and r["result"] == written["response"] for r in t.lookups), "重启按原键找回持久回执")
    current = t.snapshot("重启恢复增量修订")
    new_person = profile_for(current, "person", delta, "林澄")
    assert new_person["version"] == old_person["version"] + 1
    assert any(word in new_person["text"] for word in ("语音", "录音", "音频", "口述", "口头", "声音"))
    assert sum(row["command"]["event_id"] == key for row in t.commits) == 1

    # 更正使旧档案立即失效。真实模式在模型已经产出、Memory 尚未提交的边界读取投影。
    # 确定性模式另外让模型离线、再提交过期引用，两个失败都必须留在同一批次。
    blocked = t.arm("before-commit")
    try:
        t.mode = "unavailable" if not t.live else "valid"
        corrected = t.correct(delta, CORRECTION)
        if not t.live:
            t.wait_for(lambda: any(c["mode"] == "unavailable" for c in t.model_calls), "模型离线时保留批次")
            t.mode = "stale"
            t.wait_for(lambda: sum(c["mode"] == "stale" for c in t.model_calls) >= 2, "过期引用被拒绝后用同批重试")
            bad = [c for c in t.model_calls if c["mode"] in ("unavailable", "stale")]
            assert all(c["protocol"] == "GEMINI" and ref(corrected) in [ref(e) for e in c["input"]["evidence"]] for c in bad)
            assert all(new_person["text"] not in json.dumps(c["input"], ensure_ascii=False) for c in bad)
            t.mode = "valid"
        t.wait_for(blocked.hit.is_set, "更正后的模型提案到达 Memory 提交边界")
        assert new_person["text"] not in "".join(t.files().values())
        assert old_topic["text"] in "".join(t.files().values())
        assert new_person not in t.profiles()
        pending = [b["after"] for b in t.batches if ref(corrected) in [ref(e) for e in b["evidence"]]]
        assert pending and len(set(pending)) == 1, "模型失败或提交未确认时越过了本批次"
    finally:
        blocked.release.set()
    t.wait_committed(corrected)
    corrected_profiles = t.snapshot("原文更正")
    fixed_person = profile_for(corrected_profiles, "person", corrected, "林澄")
    assert fixed_person["subject"] == old_person["subject"] and fixed_person["version"] == new_person["version"] + 1
    assert "磁" in fixed_person["text"] and ref(delta) not in fixed_person["source_refs"]

    # 宿主停机时撤回，下一次启动只需收回失效版本并持久确认空批；独立主题继续可读。
    t.stop_harness()
    withdrawn = t.correct(corrected)
    before_models = len(t.model_calls)
    t.start_dreaming(gemini=True)
    t.wait_for(lambda: any(b["after"] == b["through"] and b["after"] >= withdrawn["seq"] for b in t.batches), "撤回批次已持久确认")
    final = t.snapshot("撤回后重启")
    assert all(ref(corrected) not in p["source_refs"] for p in final) and old_topic in final
    assert fixed_person["text"] not in "".join(t.files().values()) and len(t.model_calls) == before_models
    assert all(ref(private) not in p["source_refs"] for stage in t.stages.values() for p in stage["profiles"])
    t.stop_harness()
    t.epochs["after"] = t.epoch_counts()
    assert not t.master_calls and t.epochs["after"] == t.epochs["before"] and t.epochs["before"]["members"] == 0
    assert not t.failures, t.failures
    if not t.live:
        assert {c["protocol"] for c in t.model_calls} == {"OPENAI_CHAT", "GEMINI"}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    parser.add_argument("--live", action="store_true", help="使用正式 JAR 的 Hydrogen 模型，仅发送本轮合成材料")
    trial = DreamTrial(parser.parse_args())
    outcome = "FAIL"
    try:
        run(trial)
        outcome = "PASS"
    finally:
        trial.close()
        trial.report(outcome)
