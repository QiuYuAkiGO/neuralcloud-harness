"""从公开 CLI 和本地聊天补全端点验收连续对话采集。"""

import json
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
ROUTE = "/fixture/v1/chat/completions"


def completion(reasoning, content, finish_reason):
    """按聊天补全接口返回推理、正文及解码终点。"""
    return {
        "id": "local-completion",
        "object": "chat.completion",
        "created": 1,
        "model": "local-model",
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "reasoning_content": reasoning, "content": content},
            "finish_reason": finish_reason,
        }],
    }


@contextmanager
def chat_endpoint(replies):
    """记录线上格式的请求，按当前用户输入应答，让不同案例可以交错执行。"""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != ROUTE:
                self.send_error(404)
                return
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            requests.append(request)
            turn = request["messages"][-1]["content"]
            if turn not in replies:
                self.send_error(400, "Unexpected user turn")
                return
            data = json.dumps(replies[turn], ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, fmt, *args):
            pass

    with HTTPServer(("127.0.0.1", 0), Handler) as server:
        worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02})
        worker.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}{ROUTE}", requests
        finally:
            server.shutdown()
            worker.join()


class RolloutTest(unittest.TestCase):
    def run_rollout(self, cases, replies, *extra_args):
        """每个案例组仅调用一次 CLI，观察请求、退出状态、诊断和指定输出文件。"""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        source = Path(directory.name, "cases.json")
        output = Path(directory.name, "rollout.json")
        source.write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
        with chat_endpoint(replies) as (endpoint, requests):
            run = subprocess.run(
                [sys.executable, "tools/misty27_rollout.py", "--endpoint", endpoint,
                 "--model", "local-model", "--cases", str(source), "--output", str(output),
                 *extra_args],
                cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", timeout=20,
            )
        return run, requests, output

    def test_collects_independent_histories_and_preserves_both_channels(self):
        """第三轮验证完整历史，第二案首轮验证隔离，截断轮的空正文也须原样落盘。"""
        first, second, third, separate = "  λ-01\n", "μ-02", "ν-03", "ξ-01"
        cases = [{"id": "z", "turns": [first, second, third]}, {"id": "a", "turns": [separate]}]
        expected = {
            first: ("r-1\n", "\nbody-1  ", "stop"),
            second: ("r-2", "body-2", "stop"),
            third: ("\nr-3", "", "length"),
            separate: ("r-4", "body-4", "stop"),
        }
        run, requests, output = self.run_rollout(
            cases, {turn: completion(*reply) for turn, reply in expected.items()}
        )
        self.assertEqual(run.returncode, 0, run.stdout)
        self.assertCountEqual([req["messages"][-1]["content"] for req in requests], expected.keys())
        messages = {req["messages"][-1]["content"]: req["messages"] for req in requests}
        for opening in (first, separate):
            conversation = [m for m in messages[opening] if m["role"] in ("user", "assistant")]
            self.assertEqual(conversation, [{"role": "user", "content": opening}])
        for previous, current in ((first, second), (second, third)):
            self.assertEqual(messages[current], [
                *messages[previous],
                {"role": "assistant", "content": expected[previous][1]},
                {"role": "user", "content": current},
            ])

        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual([case["id"] for case in result], ["z", "a"])
        for case, original in zip(result, cases):
            channels = [
                (turn["reasoning_content"], turn["content"], turn["finish_reason"])
                for turn in case["turns"]
            ]
            self.assertEqual(channels, [expected[turn] for turn in original["turns"]])

    def test_missing_reasoning_after_a_valid_turn_does_not_publish(self):
        """第二轮 stop 缺少推理字段时，诊断应点名该字段，已收到的首轮也不能发布。"""
        first, second = "u-1", "u-2"
        malformed = completion("unused", "body-2", "stop")
        del malformed["choices"][0]["message"]["reasoning_content"]
        run, requests, output = self.run_rollout(
            [{"id": "x", "turns": [first, second]}],
            {first: completion("r-1", "body-1", "stop"), second: malformed},
        )
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("reasoning_content", run.stdout)
        self.assertEqual([req["messages"][-1]["content"] for req in requests], [first, second])
        self.assertFalse(output.exists())

    def test_llama_fingerprint_preserves_omitted_and_explicit_channels(self):
        """构建匹配时，省略、显式空串与带空白的推理串各自原样落盘。"""
        build = "b1234-0e7ac1"
        turns = ["u-1", "u-2", "u-3"]
        absent = completion("unused", " body-1\n", "stop")
        del absent["choices"][0]["message"]["reasoning_content"]
        replies = {
            turns[0]: absent,
            turns[1]: completion("", "", "stop"),
            turns[2]: completion("\n r-3 ", "body-3", "length"),
        }
        for reply in replies.values():
            reply["system_fingerprint"] = build

        run, _, output = self.run_rollout(
            [{"id": "x", "turns": turns}], replies, "--llama-fingerprint", build
        )
        self.assertEqual(run.returncode, 0, run.stdout)
        recorded = json.loads(output.read_text(encoding="utf-8"))[0]["turns"]
        channels = [(t["reasoning_content"], t["content"], t["finish_reason"]) for t in recorded]
        self.assertEqual(channels, [
            (None, " body-1\n", "stop"),
            ("", "", "stop"),
            ("\n r-3 ", "body-3", "length"),
        ])

    def test_llama_fingerprint_change_on_later_turn_does_not_publish(self):
        """首轮核验通过后，第二轮指纹多一个空格也必须指明字段并停止发布。"""
        build = "b1234-0e7ac1"
        turns = ["u-1", "u-2"]
        replies = {
            turns[0]: dict(completion("r-1", "body-1", "stop"), system_fingerprint=build),
            turns[1]: dict(completion("r-2", "body-2", "stop"), system_fingerprint=build + " "),
        }
        run, requests, output = self.run_rollout(
            [{"id": "x", "turns": turns}], replies, "--llama-fingerprint", build
        )
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("system_fingerprint", run.stdout)
        self.assertEqual([req["messages"][-1]["content"] for req in requests], turns)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
