"""原版 MistyAI 真机的隔离验收适配器。每次运行独占一个空的原版私聊邮箱，仅签发一个 B 会话。"""
import hmac
import json
import argparse
import os
import queue
import re
import secrets
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def call(url, body=None, headers=None):
    """使用原服务 HTTP 接口，写入返回的平台消息 ID 是唯一送达凭据。"""
    data = json.dumps(body, ensure_ascii=False).encode() if isinstance(body, dict) else body
    req = Request(url, data=data,
                  headers={"Content-Type": "application/json", **(headers or {})})
    with urlopen(req, timeout=45) as response:
        return json.load(response)


class PrivatePair:
    """先以 B 的群凭据确认已入群，再把该成员的一次验收邮箱配给手机；模型看不到凭据。"""

    def __init__(self, mobile, member_token, nickname, port):
        self.mobile = mobile
        self.group_auth = {"X-Member": member_token}
        self.authorize()
        profile = call(mobile + "/v1/group/profile", {"nickname": nickname}, self.group_auth)
        self.member = profile["id"]
        if call(mobile + "/v1/messages?after=0"):
            raise ValueError("须使用空的独立私聊邮箱")
        self.key = secrets.token_urlsafe(32)
        self.pending = queue.Queue()
        self.seen = set()
        self.lock = threading.Lock()
        binding = self

        class Handler(BaseHTTPRequestHandler):
            """只转发配对路径上的原版私聊协议；每次访问重新验证 B 仍在群中。"""
            def log_message(self, *args):
                pass  # 配对凭据在本机专用路径中，访问日志不得记录路径。

            def forward(self):
                target = urlsplit(self.path)
                if not hmac.compare_digest(target.path, "/" + binding.key + "/v1/messages"):
                    self.send_error(403)
                    return
                try:
                    binding.authorize()
                    body = None
                    if self.command == "POST":
                        if "Content-Length" not in self.headers:
                            self.send_error(411)
                            return
                        body = self.rfile.read(int(self.headers["Content-Length"]))
                    url = binding.mobile + "/v1/messages" + ("?" + target.query if target.query else "")
                    result = call(url, body, {"X-Misty-Client": "mobile"})
                    if self.command == "POST":
                        with binding.lock:
                            if result["id"] not in binding.seen:
                                binding.seen.add(result["id"])
                                binding.pending.put(result)
                except HTTPError as error:
                    self.send_error(error.code)
                    return
                data = json.dumps(result, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = forward

        self.http = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def authorize(self):
        """只查成员资格，空窗口避免把群聊天内容带入 B 的模型输入。"""
        call(self.mobile + "/v1/group/messages?after=9223372036854775807", headers=self.group_auth)

    def close(self):
        self.http.shutdown()
        self.http.server_close()


class GroupNotices:
    """把群服务确认的显式通知投递到 Memory。更新引用首发群消息 ID 和预期版本。"""

    def __init__(self, instance, mobile, memory, memory_token, reader_token, character, writer):
        self.instance, self.mobile, self.memory = instance, mobile, memory
        self.auth, self.reader = {"Authorization": "Bearer " + memory_token}, {"X-Member": reader_token}
        self.writer = writer
        scope = "group:mistyai:" + instance
        self.context = dict(character_id=character, session_id=scope, conversation_id=scope,
                            actor_id="mistyai-channel", role="ROUTER")
        self.after = 0

    def sync(self):
        """从真实群消息流顺序追平。在入账回执之后推进游标，重新启动时按原始事件键去重。"""
        while True:
            rows = call(self.mobile + "/v1/group/messages?after=" + str(self.after), headers=self.reader)
            for row in rows:
                self._project(row)
                self.after = row["id"]
            if len(rows) < 200:
                return

    def _project(self, row):
        """作者与原文均取自群服务；普通聊天不进入共享通知。"""
        message_id = row["id"]
        verb, _, args = row["text"].partition(" ")
        if verb not in ("/通知", "/变更通知"):
            return None
        channel = dict(platform="mistyai", type="mobile_group_notice", account_id=self.instance,
                       sender_id=str(row["member"]), conversation_id=self.context["conversation_id"],
                       message_id=str(message_id))
        prefix = "mistyai:" + self.instance + ":group:"
        cmd = dict(context=self.context, source_instance=self.writer, event_id=prefix + str(message_id),
                   content=row["text"], channel=channel)
        if verb == "/通知":
            if not args.strip():
                raise ValueError("群通知需要正文")
            return call(self.memory + "/ingest", dict(cmd, kind="observation", occurred_at=row["created_at"],
                        speaker=row["nickname"], source_venue="MistyAI 共享群通知"), self.auth)
        reference, _, content = args.partition(" ")
        match = re.fullmatch(r"([1-9][0-9]{0,18})@([1-9][0-9]{0,18})", reference)
        if not match or not content.strip():
            raise ValueError("变更通知需要 首发消息ID@预期版本 正文")
        origin, revision = match.groups()
        receipt = call(self.memory + "/receipts?" + urlencode(dict(source_instance=self.writer,
                       event_id=prefix + origin)), headers=self.auth)
        if receipt["revision"] != 1:
            raise ValueError("变更通知必须引用首发群消息")
        return call(self.memory + "/corrections", dict(cmd, record_id=receipt["record_id"],
                    expected_revision=int(revision), operation="REPLACE", reason="群成员发布变更通知"), self.auth)


def exchange(probe, request):
    """与测试 JVM 的管道同步交接一次请求，缺少明确回执即中止本次调试。"""
    probe.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
    probe.stdin.flush()
    line = probe.stdout.readline()
    if not line:
        raise RuntimeError("移动联调 JVM 在回执前退出")
    return json.loads(line)


def serve_turn(pair, notices, probe):
    """真实私聊产生入站，先追平群通知；交付前重读引用，再用原移动服务回执确认送达。"""
    row = pair.pending.get()
    pair.authorize()
    notices.sync()
    reply = exchange(probe, dict(instance=notices.instance, member_id=pair.member, message_id=row["id"], text=row["text"]))
    if reply["reply_to"] != row["id"]:
        raise ValueError("回复未匹配当前私聊")
    notices.sync()
    pair.authorize()
    refs = reply.get("source_refs", [])
    if refs:
        scope = f"c2c:mistyai:{notices.instance}:{pair.member}"
        ctx = dict(notices.context, conversation_id=scope, session_id=scope, actor_id="ROUTER",
                   mobile_member={"instance": notices.instance, "member_id": str(pair.member)})
        items = call(notices.memory + "/read", dict(context=ctx, kind="HISTORY", use="CITE", targets=refs),
                     notices.auth)["items"]
        if [item["ref"] for item in items if item["status"] == "ONLINE"] != refs:
            raise ValueError("交付前群通知版本已变化")
    sent = call(pair.mobile + "/v1/messages", dict(sender="ai", request_id="reply-" + str(row["id"]), text=reply["text"]))
    if sent["sender"] != "ai" or sent["text"] != reply["text"]:
        raise ValueError("移动服务未确认本次回复")
    return exchange(probe, dict(reply_to=row["id"], message_id=sent["id"]))


def main():
    """配置显式指向两套本机测试服务。配对文件仅交给本轮独占的设备，关闭进程即失效。"""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("mobile", "memory", "instance", "character", "nickname", "console-config", "classpath", "pair-file"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--device-port", type=int, required=True)
    args = parser.parse_args()
    if any(urlsplit(url).hostname != "127.0.0.1" for url in (args.mobile, args.memory)):
        parser.error("移动验收仅连接本机隔离实例")
    member, token = os.environ["MISTY_TEST_MEMBER"], os.environ["MISTY_TEST_MEMORY_TOKEN"]
    notices = GroupNotices(args.instance, args.mobile, args.memory, token, member, args.character, "neuralcloud-java")
    if "mobile_member_notices" not in call(args.memory + "/health", headers=notices.auth)["capabilities"]:
        raise ValueError("Memory 未提供移动成员通知能力")
    pair = PrivatePair(args.mobile, member, args.nickname, 0)
    pairing = Path(args.pair_file)
    try:
        with subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", args.classpath,
                               "neuralcloud.controlplane.app.MistyMobileProbe", "--config", args.console_config],
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8") as probe:
            try:
                if json.loads(probe.stdout.readline()) != {"ready": True}:
                    raise RuntimeError("移动联调 JVM 尚未就绪")
                # 只创建新文件；在支持 POSIX 权限的主机上只允许启动者读取配对凭据。
                descriptor = os.open(pairing, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                    json.dump(dict(port=pair.http.server_port,
                                   base=f"http://127.0.0.1:{args.device_port}/{pair.key}"), output)
                while True:
                    serve_turn(pair, notices, probe)
            finally:
                probe.stdin.close()
    finally:
        pair.close()


if __name__ == "__main__":
    main()
