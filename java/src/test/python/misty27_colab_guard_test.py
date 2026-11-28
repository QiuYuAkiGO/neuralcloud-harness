"""从 Linux 命令行和本地管理端验收 Colab 运行时释放。"""

import json
import os
import subprocess
import sys
import threading
import time
import unittest
from contextlib import ExitStack, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[4]
GUARD = ("/usr/bin/python3", "tools/misty27_colab_guard.py")


class ManagementHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        """记录真实 HTTP 请求；重试案例由测试控制何时发出第二次响应。"""
        endpoint = self.server
        endpoint.posts.append((self.path, time.monotonic()))
        if endpoint.first_status is not None and len(endpoint.posts) == 1:
            status = endpoint.first_status
        else:
            endpoint.awaiting_ack.set()
            endpoint.allow_ack.wait()
            status = 200
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()
        endpoint.response_sent.set()

    def log_message(self, *args):
        """请求路径和到达时间已由案例记录。"""


@unittest.skipUnless(sys.platform == "linux", "需要 Linux 或 WSL 的进程和时钟")
class ColabGuardTest(unittest.TestCase):
    @contextmanager
    def local_vm(self, first_status=None):
        """每个场景使用独立状态目录和环回管理端，退出时回收案例启动的进程。"""
        with TemporaryDirectory() as directory, ThreadingHTTPServer(
            ("127.0.0.1", 0), ManagementHandler
        ) as endpoint, ExitStack() as processes:
            self.state = Path(directory)
            self.endpoint = endpoint
            self.processes = processes
            self.env = dict(
                os.environ, TBE_RUNTIME_ADDR=f"127.0.0.1:{endpoint.server_port}",
                NO_PROXY="127.0.0.1", no_proxy="127.0.0.1",
            )
            endpoint.posts = []
            endpoint.first_status = first_status
            endpoint.response_sent = threading.Event()
            endpoint.awaiting_ack = threading.Event()
            endpoint.allow_ack = threading.Event()
            if first_status is None:
                endpoint.allow_ack.set()
            thread = threading.Thread(target=endpoint.serve_forever, args=(0.05,))
            thread.start()
            try:
                yield
            finally:
                endpoint.allow_ack.set()
                endpoint.shutdown()
                thread.join()

    def command(self, action, *options):
        """仅从正式 CLI 入口启动命令，保留子进程输出用于失败定位。"""
        process = self.processes.enter_context(subprocess.Popen(
            [*GUARD, action, "--state-dir", str(self.state), *map(str, options)],
            cwd=ROOT, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True,
        ))
        self.processes.callback(process.kill)
        return process

    def exit_status(self, process, success=True):
        output, _ = process.communicate(timeout=8)
        self.assertEqual(process.returncode == 0, success, msg=output)

    def wait_file(self, name, process=None, timeout=8):
        """就绪与回执只由公开文件判断；启动拒绝时报告 CLI 诊断。"""
        path = self.state / name
        deadline = time.monotonic() + timeout
        while not path.is_file():
            if process is not None and process.poll() not in (None, 0):
                self.fail(process.communicate()[0])
            if time.monotonic() >= deadline:
                self.fail(f"等待 {name} 超时，管理端收到 {self.endpoint.posts}")
            time.sleep(0.05)
        return path

    def arm(self, maximum=30, idle=15, owner=None):
        """调用方观察到 ready.json 后才提交进度或结束信号。"""
        options = (
            "--owner-pid", os.getpid() if owner is None else owner,
            "--max-seconds", maximum, "--idle-seconds", idle,
        )
        self.wait_file("ready.json", self.command("watch", *options))
        return options

    def expect_release(self, reason, attempts=1, timeout=8):
        receipt = json.loads(self.wait_file("released.json", timeout=timeout).read_text())
        self.assertEqual(receipt["reason"], reason)
        self.assertEqual([path for path, _ in self.endpoint.posts], ["/unassign"] * attempts)

    def test_completed_job_releases_runtime(self):
        with self.local_vm():
            self.arm(maximum=30, idle=30)
            self.exit_status(self.command("finish", "--outcome", "completed"))
            self.expect_release("completed")

    def test_failures_and_limits_release_only_after_confirmation(self):
        with self.subTest(reason="failed", first_response=503), self.local_vm(first_status=503):
            self.arm()
            finish = self.command("finish", "--outcome", "failed")
            self.assertTrue(self.endpoint.awaiting_ack.wait(20), "503 后未收到重试")
            self.assertFalse((self.state / "released.json").exists(), "200 发出前已有回执")
            self.endpoint.allow_ack.set()
            self.exit_status(finish)
            self.expect_release("failed", attempts=2)

        with self.subTest(first_response=204), self.local_vm(first_status=204):
            self.arm()
            self.command("finish", "--outcome", "failed")
            self.assertTrue(self.endpoint.response_sent.wait(8), "管理端未收到释放请求")
            # 首次响应已发出。若守护重试，管理端会扣住 200，便于单独检验 204 的效果。
            self.endpoint.awaiting_ack.wait(1.5)
            self.assertFalse((self.state / "released.json").exists(), "204 被当成释放确认")
            self.assertEqual(self.endpoint.posts[0][0], "/unassign")

        with self.subTest(reason="owner_exited"), self.local_vm():
            with subprocess.Popen(["/bin/sleep", "60"]) as owner:
                try:
                    self.arm(owner=owner.pid)
                finally:
                    owner.kill()
                    owner.wait()
            self.expect_release("owner_exited")

        with self.subTest(reason="idle_timeout", progress="none"), self.local_vm():
            started = time.monotonic()
            self.arm(maximum=20, idle=2)
            self.expect_release("idle_timeout", timeout=5)
            self.assertGreaterEqual(self.endpoint.posts[0][1] - started, 1.8)

        with self.subTest(reason="idle_timeout", progress="repeated"), self.local_vm():
            self.arm(maximum=25, idle=6)
            started = time.monotonic()
            self.exit_status(self.command("progress", "--marker", "batch-1"))
            self.assertFalse(self.endpoint.awaiting_ack.wait(4), "空闲期限前已请求释放")
            self.exit_status(self.command("progress", "--marker", "batch-1"))
            # 若相同标记也续期，最早约第 10 秒才释放。给正常的 6 秒期限留出轮询余量。
            self.expect_release("idle_timeout", timeout=max(0, started + 8.5 - time.monotonic()))
            self.assertGreaterEqual(self.endpoint.posts[0][1] - started, 5.8)

        with self.subTest(reason="hard_timeout"), self.local_vm():
            started = time.monotonic()
            self.arm(maximum=8, idle=4)
            ready = time.monotonic()
            # 不同标记让作业持续推进，跨过首个空闲期限后仍受原总时限约束。
            for batch in range(1, 3):
                if self.endpoint.awaiting_ack.wait(2.7):
                    break
                self.exit_status(self.command("progress", "--marker", f"batch-{batch}"))
            self.expect_release("hard_timeout", timeout=max(0, ready + 11 - time.monotonic()))
            self.assertGreaterEqual(self.endpoint.posts[0][1] - started, 7.8)

        with self.subTest(action="parallel_watch"), self.local_vm():
            options = self.arm()
            self.exit_status(self.command("watch", *options), success=False)
            self.exit_status(self.command("finish", "--outcome", "completed"))
            self.expect_release("completed")

        for maximum, idle in [(4, 0), ("2.5", 1), (1, 2)]:
            with self.subTest(maximum=maximum, idle=idle), self.local_vm():
                refused = self.command(
                    "watch", "--owner-pid", os.getpid(),
                    "--max-seconds", maximum, "--idle-seconds", idle,
                )
                self.exit_status(refused, success=False)
                self.assertFalse((self.state / "ready.json").exists())
                self.assertFalse((self.state / "released.json").exists())
                self.assertEqual(self.endpoint.posts, [])


if __name__ == "__main__":
    unittest.main()
