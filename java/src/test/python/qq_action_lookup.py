"""用正式 Harness、Memory v2 和回环 OneBot 验收动作分流、已有回执及 QQ 规划失败。"""
import argparse
import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

from conversation_responsibilities import tool
from qq_observe import A, B, ACCOUNT, Trial, call, context, until


QUESTION = "查一下之前的青瓷交接回执，登记的编号是什么？"
RECEIPT = "青瓷交接已完成，登记编号为纸鸢七号。"
CLARIFICATION = "你指的是哪次交接？"
NEXT = "你好，新话题开始了。"
ACK = "你好呀！"
PEER = 67890  # 与回环 OneBot 基座的私聊白名单一致。


class ActionTrial(Trial):
    """真实入口负责选路、记忆查询、计划校验和渠道送达。只替代模型及 OneBot 对端。"""

    def __init__(self, args):
        super().__init__(args)
        self.plan = {"kind": "HISTORY", "query": "青瓷"}
        self.plans, self.generations, self.writes, self.dispatches = [], [], [], []
        # 状态采样只支持 Linux。在支持它的主机上接入现场能力，同时在所有平台核对公开的派发审计。
        if sys.platform.startswith("linux"):
            task = self.config("task", endpoint=f"http://127.0.0.1:{self.proxy.server_port}/v1/chat/completions",
                               model="trial-task", apiKeyEnv="TRIAL_KEY",
                               connectTimeout="PT3S", requestTimeout="PT10S")
            with self.hc.open("a", encoding="utf-8") as config:
                config.write(f"taskWorkerConfig={task.as_posix()}\n")

    def route(self, path, body):
        """模型只可在实际收到旧结果时说出编号。现场执行入口即使被误调也不操作主机。"""
        if path == "/v2/ingest":
            self.writes.append(body)
        if path != "/v1/chat/completions":
            return super().route(path, body)
        if body["model"] == "trial-task":
            self.dispatches.append(body)
            return tool("no_supported_action", {})
        names = [entry["function"]["name"] for entry in body.get("tools", [])]
        current = body["messages"][-1].get("content") or ""
        if names == ["select_route"]:
            return tool("select_route", {"route": "CONVERSATION" if NEXT in current else "ACTION_REQUEST"})
        if names == ["select_reply"]:
            return tool("select_reply", {"decision": "SKIP"})
        if names == ["plan_turn"]:
            self.plans.append(body)
            return tool("plan_turn", {"kind": "REPLY", "query": ""} if NEXT in current else self.plan)
        if names:
            return super().route(path, body)
        self.generations.append(body)
        if NEXT in current:
            answer = ACK
        elif self.plan["kind"] == "CLARIFY":
            answer = CLARIFICATION
        else:
            material = [message.get("content") or "" for message in body["messages"]]
            answer = "旧回执登记的是纸鸢七号。" if any(RECEIPT in text for text in material) else "没有查到编号。"
        return 200, {"model": "trial", "choices": [{"finish_reason": "stop", "message": {"content": answer}}]}

    def private(self, number, text):
        """以白名单发件人的 OneBot 原始私聊事件投递，包括测试中的同号重投。"""
        self.ws.send(json.dumps(dict(post_type="message", message_type="private", sub_type="friend",
                                     self_id=ACCOUNT, user_id=PEER, message_id=number,
                                     message=[{"type": "text", "data": {"text": text}}])))

    def private_events(self):
        status, result = call(self.hp, f"/api/audit/chains/qq-private-{ACCOUNT}-{PEER}")
        if status == 404:  # OneBot 已投递但首个入站事件尚未写出。
            return []
        assert status == 200, (status, result)
        return result["events"]

    def sent_texts(self, action, recipient):
        """只统计送往指定收件人的 OneBot 消息，兼容协议允许的纯文本和文本段。"""
        key = "user_id" if action == "send_private_msg" else "group_id"
        messages = [command["params"]["message"] for command in self.sent if command["action"] == action
                    and int(command["params"][key]) == recipient]
        return [message if isinstance(message, str) else "".join(
            part["data"]["text"] for part in message if part["type"] == "text") for message in messages]


class ActionLookupTest(unittest.TestCase):
    """每个场景启动独立的正式服务并从空会话开始，数据和失败重投互不影响。"""
    def setUp(self):
        self.t = ActionTrial(SimpleNamespace(harness=ARGS.harness, memory=ARGS.memory,
                                             output=ARGS.output / self._testMethodName))
        self.addCleanup(self.t.close)
        memory = self.t.start("memory", ["-jar", self.t.mjar, "--config", self.t.mc])
        def ready():
            health = self.t.mem("health")
            self.capabilities = health["capabilities"]
            return health["status"] == "TEXT_READY"
        self.t.ready(ready, memory)

    def reply_group(self):
        self.t.harness()
        self.t.policy(B, "OBSERVE_REPLY")
        return self.t

    def test_existing_receipt_reaches_qq_without_a_new_action(self):
        """动作路由仍向群 Worker 提供本群可读选项，并从 Memory 的旧工具结果回答。"""
        t = self.reply_group()
        source = t.mem("ingest", dict(context=context(B), source_instance="neuralcloud-java",
                       event_id="archive-receipt", occurred_at="2026-11-20T09:00:00Z",
                       kind="tool_result", action_id="ceramic-handoff", content=RECEIPT))
        self.assertEqual("DURABLE", source["status"])
        t.emit(B, 101, QUESTION)
        request = until(lambda: t.plans and t.plans[0], "首次 Chat Worker 规划")
        schema = request["tools"][0]["function"]["parameters"]["properties"]["kind"]["enum"]
        instructions = "\n".join(m["content"] for m in request["messages"] if m["role"] == "system")
        required = {"HISTORY", "CLARIFY", "ACTION"}
        if "identity_lookup" in self.capabilities:
            required.add("IDENTITY")
        for kind in sorted(required):
            self.assertIn(kind, schema)
            self.assertRegex(instructions, rf"(?<![A-Z_]){kind}(?![A-Z_])")
        self.assertNotIn("SELF_HISTORY", schema)
        until(lambda: any(e["type"] == "qq_delivery" for e in t.events(B)), "查证结果送达群")
        messages = t.sent_texts("send_group_msg", B)
        self.assertEqual(1, len(messages))
        self.assertIn("纸鸢七号", messages[0])
        events = t.events(B)
        self.assertTrue(any(e["type"] == "router_route" and e["data"]["route"] == "ACTION_REQUEST" for e in events))
        self.assertNotIn("task_worker_dispatch", [e["type"] for e in events])
        self.assertTrue(any(e["type"] == "memory_recall" and any(
            row["item"]["ref"] == {"record_id": source["record_id"], "revision": source["revision"]}
            for row in e["data"]["bindings"]) for e in events))
        self.assertFalse(t.dispatches)

    def test_unclear_action_target_can_be_clarified(self):
        """现场目标不明时，合法 CLARIFY 规划可以通过同一动作路由把问题送回用户。"""
        t = self.reply_group()
        t.plan = {"kind": "CLARIFY", "query": ""}
        t.emit(B, 102, "再查一下那个。")
        result = until(lambda: next((e for e in t.events(B) if e["type"] in {"qq_delivery", "node_failed"}), None),
                       "澄清轮次出口")
        self.assertEqual("qq_delivery", result["type"], result)
        self.assertEqual([CLARIFICATION], t.sent_texts("send_group_msg", B))
        self.assertFalse(t.dispatches)

    def test_invalid_private_plan_sends_one_failure_and_writes_no_answer(self):
        """非法规划以失败结束。相同消息重投后只收到一次说明，后继正常消息用于确认队列已前进。"""
        t = self.t
        t.roster = []  # 私聊案例没有可共享通知的群，不需要另外模拟群成员查询。
        t.harness()
        t.plan = {"kind": "UNKNOWN_PLAN", "query": ""}
        t.private(201, QUESTION)
        failure = until(lambda: next((e for e in t.private_events() if e["type"] == "node_failed"), None),
                        "非法规划的审计失败")
        self.assertTrue(t.plans)
        self.assertTrue(failure["data"]["detail"])
        messages = until(lambda: t.sent_texts("send_private_msg", PEER), "向私聊用户说明处理失败", 8)
        self.assertEqual(1, len(messages))
        self.assertRegex(messages[0], "失败|未能|没能|没法|未完成|没.*成功|无法|出错|遇到.*问题|没.*处理")
        self.assertFalse(t.generations)
        self.assertFalse(any(e["type"] == "chat_worker_reply" for e in t.private_events()))
        t.private(201, QUESTION)
        t.private(202, NEXT)
        until(lambda: any(e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"
                          for e in t.private_events()), "后继正常回复送达并落盘")
        self.assertEqual([messages[0], ACK], t.sent_texts("send_private_msg", PEER))
        self.assertEqual([ACK], [w["content"] for w in t.writes if w["kind"] == "self_expression"])
        self.assertFalse(t.dispatches)

    def test_observation_and_declined_participation_remain_silent(self):
        """相同问题发到仅观察群和参与判断选择 SKIP 的群，两个渠道都只收取原文。"""
        t = self.reply_group()
        t.plan = {"kind": "UNKNOWN_PLAN", "query": ""}
        t.emit(A, 301, QUESTION)
        t.emit(B, 302, QUESTION, at=False)
        until(lambda: t.status(A)["pending"] == 1, "仅观察入账")
        until(lambda: any(e["type"] == "qq_participation" and e["data"]["decision"] == "SKIP"
                          for e in t.events(B)), "常规群消息不参与")
        self.assertFalse(t.plans)
        self.assertFalse(t.sent)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
