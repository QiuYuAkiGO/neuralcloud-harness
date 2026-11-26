"""通过正式 QQ 群入口、模型请求和 Memory 候选出口验收群印象的一次补正。"""
import argparse
import json
import threading
import unittest
from pathlib import Path

from conversation_responsibilities import tool
from qq_observe import A, B, Trial, correction_feedback, until


TOOL = "submit_impression"
CORRECTED = "根据这次核票记录，我目前倾向于沿用同一套票据编号。"
VALID = {"impression": CORRECTED, "evidence": [1]}


def source(group, index):
    return f"核对单【{group}/{index:02d}】"


class ValidationTrial(Trial):
    """固定模型在协议边界的首答和补正，收集实际请求以区别带反馈的补正与重新生成。"""

    def __init__(self, args, repair_succeeds):
        self.repair_succeeds = repair_succeeds
        self.requests = {A: [], B: []}
        self.extra_request = threading.Event()
        super().__init__(args)

    def route(self, path, body):
        if path != "/v1/chat/completions" or not body.get("tools"):
            return super().route(path, body)
        messages = body["messages"]
        group = B if any(source(B, 1) in (m.get("content") or "") for m in messages) else A
        self.requests[group].append(messages)
        if group == B:
            return tool(TOOL, {"impression": "", "evidence": []})
        attempt = len(self.requests[A])
        if attempt > 2:
            self.extra_request.set()
        if not self.repair_succeeds:
            if attempt == 1:
                return tool("unexpected_observation", VALID)
            return tool(TOOL, {"impression": CORRECTED, "evidence": [21]})
        if attempt == 1:
            status, result = tool(TOOL, {"impression": "根据第二条记录，我目前倾向于保留第一份草案。", "evidence": [2]})
            # 首答的两个调用各自合法，选择其中任一个都会把被拒的初稿写进 Memory。
            calls = result["choices"][0]["message"]["tool_calls"]
            calls.append({**calls[0], "id": "second"})
            return status, result
        return tool(TOOL, VALID)

    def emit_batch(self, group):
        # 20 条是从公开 QQ 入口立即触发一批观察所需的最少条数。
        for index in range(1, 21):
            self.emit(group, group * 100 + index, source(group, index), at=False)


class ObserveValidationCases(unittest.TestCase):
    def open_trial(self, repair_succeeds):
        inputs = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory,
                                   output=ARGS.output / self._testMethodName)
        t = ValidationTrial(inputs, repair_succeeds)
        self.addCleanup(t.proxy.server_close)
        self.addCleanup(t.close)
        memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
        t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
        t.harness()
        return t

    def feedback(self, t):
        """同批原文必须可见。只从补正新增的调用方消息取诊断，排除首答模型自行写下的内容。"""
        until(lambda: len(t.requests[A]) >= 2, "群印象补正请求")
        first, repair = t.requests[A][:2]
        material = json.dumps(repair, ensure_ascii=False)
        for index in range(1, 21):
            self.assertIn(source(A, index), material)
        return correction_feedback(first, repair)

    def test_multiple_tool_calls_are_repaired_and_written_once(self):
        """正例。拒绝两个初稿，反馈具体数量，使用唯一合法的补正写入并完成。"""
        t = self.open_trial(True)
        t.emit_batch(A)
        feedback = self.feedback(t)
        self.assertIn(TOOL, feedback)
        self.assertRegex(feedback, r"2|两|二|two")
        self.assertRegex(feedback, r"1|一|单次|单个|single|one|once")
        until(lambda: t.status(A)["lastResult"] == "IMPRESSION", "补正后候选持久化")
        self.assertEqual([CORRECTED], [p["content"] for p in t.posts])
        self.assertEqual(0, t.status(A)["pending"])
        self.assertEqual(2, len(t.requests[A]))

    def test_second_invalid_submission_stays_suspended_across_restart(self):
        """反例。首答工具名错误，补正引用第 21 条，同批保留原文和批号，其他群仍能完成。"""
        t = self.open_trial(False)
        t.emit_batch(A)
        feedback = self.feedback(t)
        self.assertIn(TOOL, feedback)
        self.assertIn("unexpected_observation", feedback)
        until(lambda: t.status(A)["state"] == "SUSPENDED", "挂起失败批次")
        before = t.status(A)
        self.assertTrue(before["currentBatchId"])
        self.assertEqual(20, before["pending"])
        self.assertFalse(before["lastBatchId"])
        t.stop_harness()
        t.harness()
        t.policy(B, "OBSERVE")
        t.emit_batch(B)
        until(lambda: t.status(B)["lastResult"] == "NO_IMPRESSION", "其他群完成")
        # 跨过原队列的五秒重试窗口，同时观察重启恢复与后续调度是否重复生成。
        self.assertFalse(t.extra_request.wait(6), "挂起批次产生了第三次模型请求")
        after = t.status(A)
        for key in ("state", "currentBatchId", "pending", "lastBatchId"):
            self.assertEqual(before[key], after[key], key)
        self.assertEqual([], t.posts)
        self.assertEqual(1, len(t.requests[B]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("harness", "memory", "output"):
        parser.add_argument(field, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2)
