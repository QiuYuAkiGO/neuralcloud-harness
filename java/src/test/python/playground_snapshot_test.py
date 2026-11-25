"""通过 Playground 公开入口验收 QQ 快照。可用 NC_PLAYGROUND_MEMORY_JAR 指定生成格式 4 至 7 的真实 JAR。"""

import hashlib
import http.client
import json
import os
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT))
from playground.snapshot import Snapshot


ACCOUNT, AUTHOR, PEER = "12345", "34567", "45678"
GROUP = f"group:onebot:{ACCOUNT}:23456"
AUTHOR_DM = f"c2c:onebot:{ACCOUNT}:{AUTHOR}"
PEER_DM = f"c2c:onebot:{ACCOUNT}:{PEER}"


def context(conversation, role="ROUTER"):
    scope = dict(character_id="cut-test", session_id=conversation, conversation_id=conversation,
                 actor_id=role, role=role)
    if conversation.startswith("c2c:onebot:"):
        scope["qq"] = dict(account_id=ACCOUNT, peer_id=conversation.rsplit(":", 1)[1])
    return scope


def write_fixture(directory):
    """只通过公开写入、授权和更正 API 构造版本历史，取可独立打开的一致性 SQLite 备份。"""
    default_jar = ROOT.parent / "neuralcloud-memory/target/neuralcloud-memory-2.0.0-SNAPSHOT.jar"
    jar = Path(os.environ.get("NC_PLAYGROUND_MEMORY_JAR", default_jar)).resolve(strict=True)
    auth, bridge = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    config = directory / "memory.properties"
    config.write_text(f"port={port}\ndataDirectory=db\ncharacterId=cut-test\nsourceInstance=fixture\n"
                      f"accessTokenEnv=CUT_KEY\ngrantAccountId={ACCOUNT}\ngrantAccessTokenEnv=CUT_BRIDGE\n",
                      encoding="utf-8")
    ids = {}

    def call(path, body=None, token=auth):
        with closing(http.client.HTTPConnection("127.0.0.1", port, timeout=8)) as connection:
            connection.request("GET" if body is None else "POST", "/v2/" + path,
                               None if body is None else json.dumps(body).encode(),
                               {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            response = connection.getresponse()
            data = json.loads(response.read())
            if response.status != 200:
                raise AssertionError((path, response.status, data))
            return data

    def say(key, conversation, *, occurred="2025-02-01T12:00:00Z", kind="observation"):
        sender = PEER if conversation == PEER_DM else AUTHOR
        body = dict(context=context(conversation), source_instance="fixture", event_id=key,
                    occurred_at=occurred, content=key, kind=kind, speaker=sender, source_venue="QQ")
        if kind == "observation":
            body["channel"] = dict(platform="qq", type="qq_group" if conversation == GROUP else "qq_private",
                                   account_id=ACCOUNT, sender_id=sender, conversation_id=conversation,
                                   message_id=str(1000 + len(ids)))
        else:
            body["speaker"] = "小雾"
        ids[key] = call("ingest", body)["record_id"]

    def grant(key):
        writer = context(AUTHOR_DM, "ORCHESTRATOR")
        return call("visibility-grants", dict(context=writer, source_instance="fixture", event_id="grant-" + key,
                    source_ref=dict(record_id=ids[key], revision=1), target_conversation_id=GROUP,
                    use="CITE", reason="允许在测试群引用"), bridge)["grant_id"]

    log = directory / "memory.log"
    with log.open("wb") as output, subprocess.Popen(
        ["java", "-jar", str(jar), "--config", str(config), "--stop-on-eof"], cwd=directory,
        env=dict(os.environ, CUT_KEY=auth, CUT_BRIDGE=bridge), stdout=output, stderr=subprocess.STDOUT,
        stdin=subprocess.PIPE, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    ) as process:
        try:
            deadline = time.monotonic() + 20
            while True:
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError(log.read_text(encoding="utf-8", errors="replace"))
                try:
                    call("health")
                    break
                except (ConnectionRefusedError, ConnectionResetError):
                    time.sleep(0.05)

            say("group-before", GROUP)
            say("private-only", PEER_DM)
            say("shared", AUTHOR_DM)
            grant("shared")
            say("later-authorized", AUTHOR_DM)
            say("authorization-revoked", AUTHOR_DM)
            revoke_id = grant("authorization-revoked")
            say("withdrawn", GROUP)
            say("group-target", GROUP)
            # 晚入库但时间戳更早，按消息时间而非入账水位筛选会泄露该答案。
            say("group-future", GROUP, occurred="2025-01-01T12:00:00Z", kind="self_expression")
            grant("later-authorized")
            call(f"visibility-grants/{revoke_id}/revoke", dict(
                context=context(AUTHOR_DM, "ORCHESTRATOR"),
                source_instance="fixture", event_id="revoke", reason="收回授权"), bridge)
            call("corrections", dict(context=context(GROUP), source_instance="fixture", event_id="withdraw",
                                     record_id=ids["withdrawn"], expected_revision=1,
                                     operation="RETRACT", reason="原文已撤回"))
            say("private-target", PEER_DM)
            snapshot = directory / "snapshot.sqlite"
            with closing(sqlite3.connect((directory / "db/memory.sqlite").as_uri() + "?mode=ro", uri=True)) as source:
                with closing(sqlite3.connect(snapshot)) as destination:
                    source.backup(destination)
        finally:
            process.stdin.close()
            process.wait(timeout=15)
    return snapshot, ids


class PlaygroundSnapshotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temporary.cleanup)
        path, cls.ids = write_fixture(Path(temporary.name))
        cls.path, cls.before = path, hashlib.sha256(path.read_bytes()).digest()

    def test_group_and_private_history_keep_the_target_separate(self):
        """同一发言先从时间线定位，再从截点读取。有效授权进入材料，另一私聊有自己的对话前缀。"""
        snapshot = Snapshot(self.path)
        kinds = {row["id"]: row["kind"] for row in snapshot.conversations()}
        self.assertEqual((kinds[GROUP], kinds[PEER_DM]), ("group", "private"))
        timeline = snapshot.timeline(GROUP)
        self.assertEqual([row["id"] for row in timeline],
                         [self.ids[k] for k in ("group-before", "group-target", "group-future")])
        case = snapshot.cut(GROUP, self.ids["group-target"])
        self.assertEqual(case["target"]["id"], self.ids["group-target"])
        self.assertEqual([row["text"] for row in case["prefix"]], ["group-before"])
        self.assertEqual((case["target"]["seq"], case["target"]["revision"]), (timeline[1]["seq"], 1))
        records = {(row["id"], row["revision"]) for row in case["records"]}
        self.assertTrue({(self.ids["group-before"], 1), (self.ids["shared"], 1)} <= records)
        private = snapshot.cut(PEER_DM, self.ids["private-target"])
        self.assertEqual([row["text"] for row in private["prefix"]], ["private-only"])

    def test_no_future_foreign_withdrawn_or_late_granted_evidence(self):
        """一个沙箱起点同时受到入账序、会话和导出时引用权限制，重复取样也必须保持源文件完整。"""
        case = Snapshot(self.path).cut(GROUP, self.ids["group-target"])
        visible = {row["id"] for row in [*case["records"], *case["prefix"]]}
        for hidden in ("group-target", "group-future", "private-only", "withdrawn",
                       "later-authorized", "authorization-revoked"):
            with self.subTest(hidden=hidden):
                self.assertNotIn(self.ids[hidden], visible)
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).digest(), self.before)

    def copy_with_version(self, version):
        """在独立备份上改格式号，记录打开前的字节摘要以核对只读约定。"""
        other = self.path.with_name(f"format-{version}.sqlite")
        shutil.copyfile(self.path, other)
        with closing(sqlite3.connect(other)) as connection:
            connection.execute(f"PRAGMA user_version={version}")
        return other, hashlib.sha256(other.read_bytes()).digest()

    def test_supported_versions_read_the_expected_message_without_rewriting_the_file(self):
        """各支持格式都能定位同一条真实入账的 QQ 发言，取样后副本逐字节保持一致。"""
        for version in (4, 5, 6, 7):
            with self.subTest(version=version):
                other, before = self.copy_with_version(version)
                case = Snapshot(other).cut(GROUP, self.ids["group-target"])
                self.assertEqual(case["target"]["id"], self.ids["group-target"])
                self.assertEqual(hashlib.sha256(other.read_bytes()).digest(), before)

    def test_unrecognized_versions_are_rejected_without_rewriting_the_file(self):
        """早于 4 或晚于 7 的格式在打开时被拒绝，版本号和历史均保留。"""
        for version in (3, 8):
            with self.subTest(version=version):
                other, before = self.copy_with_version(version)
                with self.assertRaises(ValueError):
                    Snapshot(other)
                self.assertEqual(hashlib.sha256(other.read_bytes()).digest(), before)


if __name__ == "__main__":
    unittest.main()
