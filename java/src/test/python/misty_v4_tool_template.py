"""在推理端点所在主机以 MISTY_CHAT_API_KEY 运行 python3 misty_v4_tool_template.py。"""

import json
import os
import re
import unittest
from contextlib import closing
from http.client import HTTPConnection


MODEL = "qwen3.5-9b-mistyv4"
PIXEL = ("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1Pe"
         "AAAADElEQVR4nGOof6cAAAN9AY50oEhGAAAAAElFTkSuQmCC")


class MistyV4ToolTemplate(unittest.TestCase):
    def post(self, path, **body):
        """携带部署凭据直连回环推理入口，两例均关闭思考。"""
        headers = {"Content-Type": "application/json",
                   "Authorization": "Bearer " + os.environ["MISTY_CHAT_API_KEY"]}
        body.update(model=MODEL, chat_template_kwargs={"enable_thinking": False})
        with closing(HTTPConnection("127.0.0.1", 18083, timeout=300)) as connection:
            connection.request("POST", path, json.dumps(body).encode(), headers)
            response = connection.getresponse()
            payload = response.read()
        self.assertEqual(response.status, 200, payload)
        return json.loads(payload)

    def test_named_plan_turn_keeps_the_visible_answer_out_of_the_memory_query(self):
        """常识问答可以直接回复；提交本轮规划时，记忆检索词应为空。"""
        function = {
            "name": "plan_turn",
            "description": "规划本轮对话。",
            "parameters": {
                "type": "object",
                "properties": {
                    "kind": {"type": "string", "enum": ["REPLY", "HISTORY"]},
                    "query": {"type": "string"},
                },
                "required": ["kind", "query"],
                "additionalProperties": False,
            },
        }
        result = self.post(
            "/v1/chat/completions",
            messages=[{"role": "user", "content": "请用一个字告诉我，猫通常有几条腿？"}],
            tools=[{"type": "function", "function": function}],
            tool_choice={"type": "function", "function": {"name": "plan_turn"}},
            parallel_tool_calls=False, temperature=0, max_tokens=512,
        )
        call, = result["choices"][0]["message"]["tool_calls"]
        self.assertEqual(call["function"]["name"], "plan_turn")
        self.assertEqual(json.loads(call["function"]["arguments"]), {"kind": "REPLY", "query": ""})

    def test_regular_image_conversation_uses_the_checkpoint_prompt(self):
        """不传工具，通过公开模板端点比对第四版检查点的固定图文对话渲染。"""
        result = self.post("/apply-template", messages=[
            {"role": "system", "content": "请用中文答。"},
            {"role": "user", "content": "今天我画了一张明信片。"},
            {"role": "assistant", "content": "画了什么？"},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": PIXEL}},
                {"type": "text", "text": "看这张。"},
            ]},
        ])
        # llama.cpp 的图片位置使用进程专属标记，其余部分逐字匹配原检查点的 apply_chat_template 输出。
        vision = "<|vision_start|><|image_pad|><|vision_end|>"
        prompt = re.sub(r"<__media_[^<>\s]+__>", vision, result["prompt"])
        expected = (
            "<|im_start|>system\n请用中文答。<|im_end|>\n"
            "<|im_start|>user\n今天我画了一张明信片。<|im_end|>\n"
            "<|im_start|>assistant\n画了什么？<|im_end|>\n"
            f"<|im_start|>user\n{vision}看这张。<|im_end|>\n"
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
        )
        self.assertEqual(prompt, expected)


if __name__ == "__main__":
    unittest.main(verbosity=2)
