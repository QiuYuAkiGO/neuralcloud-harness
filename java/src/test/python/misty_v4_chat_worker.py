"""在远端控制台主机运行 python3 misty_v4_chat_worker.py，核对本机小雾第四版的部署。"""

import json
import unittest
from contextlib import closing
from http.client import HTTPConnection
from uuid import uuid4


MODEL = "qwen3.5-9b-mistyv4"


def request(port, path, body=None):
    """只连接部署主机的回环入口，返回 HTTP 状态与原始响应正文。"""
    with closing(HTTPConnection("127.0.0.1", port, timeout=300)) as connection:
        if body is None:
            connection.request("GET", path)
        else:
            connection.request("POST", path, json.dumps(body).encode(), {"Content-Type": "application/json"})
        response = connection.getresponse()
        return response.status, response.read()


class MistyV4ChatWorker(unittest.TestCase):
    def test_remote_worker_answers_with_misty_v4(self):
        """节点出口核对模型身份，再经具名 Worker 的独立网页会话验证普通回复。"""
        status, payload = request(18900, "/api/nodes")
        self.assertEqual(status, 200, payload)
        nodes = json.loads(payload)["nodes"]
        self.assertEqual([node["model"] for node in nodes if node["id"] == "chat-worker"], [MODEL])

        status, payload = request(18900, "/api/nodes/chat-worker/messages", {
            "chain": "misty-v4-" + uuid4().hex,
            "text": "请用中文回答，自然飘落的新雪通常是什么颜色？",
        })
        self.assertEqual(status, 200, payload)
        reply = json.loads(payload)
        self.assertEqual(reply["node"], "chat-worker")
        self.assertIn("白", reply["data"]["text"])

    def test_model_endpoint_rejects_missing_credential(self):
        """在同一模型推理入口省略凭据，其余请求字段合法，鉴权必须拒绝生成。"""
        status, payload = request(18083, "/v1/chat/completions", {
            "model": MODEL,
            "messages": [{"role": "user", "content": "请说一声早安。"}],
        })
        self.assertIn(status, (401, 403), payload)
        self.assertNotIn("choices", json.loads(payload))


if __name__ == "__main__":
    unittest.main(verbosity=2)
