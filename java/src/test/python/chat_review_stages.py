"""从正式私聊、模型与 Memory 端点核对前台六项复核开关。命令行传入两个 JAR 和新验收目录。"""
import argparse
import os
import secrets
import unittest
from pathlib import Path
from unittest.mock import patch

from dialogue_replay import GREET, QQ, SOURCE_INSTANCE, ref
from qq_observe import ACCOUNT
from qq_sourced_reply import LOCAL, PEER, PRIVATE, SAFE, SHARED, SourcedTrial, private


EXTRA_QUERY = "银杉展期"
EXTRA = "银杉展期延至九月。"
MARKERS = {LOCAL, SHARED, EXTRA}
STAGES = ("chatReviewRecallSources", "chatReviewBeforePlan", "chatReviewAfterPlan",
          "chatReviewBeforeDraft", "chatReviewAfterDraft", "chatReviewBeforeReturn")


class StageTrial(SourcedTrial):
    """为已获准的换票问题再指定一个点查主题，两次召回与两次模型调用划分出公开观察窗口。"""

    def __init__(self, args):
        self.sequence = []
        super().__init__(args)

    def harness(self):
        """用一次真实问候留下已送达自述，供当前轮从同一受众首次读取并检查是否反复重读。"""
        super().harness()
        self.mode = "quote"
        prior, _ = self.turn(QQ, GREET, SAFE)
        self.history_id = ref(prior, "SELF_EXPRESSION")["record_id"]
        self.sequence.clear()
        self.mode = "lookup"

    def route(self, path, body):
        # 自控模型先规划点查另一个主题，起草仍由现成的测试对端依据实收材料回答。
        if path == "/v1/chat/completions":
            names = [item["function"]["name"] for item in body.get("tools", [])]
            if names == ["plan_turn"]:
                self.sequence.append(("plan", None))
                if self.mode == "lookup":
                    self.query = EXTRA_QUERY
            elif not names:
                self.sequence.append(("draft", None))
        status, result = super().route(path, body)
        if path == "/v2/recall":
            self.sequence.append(("recall", result["items"]))
        elif path == "/v2/read" and body["context"]["role"] == "CHAT_WORKER":
            self.sequence.append(("read", body))
        return status, result


class StageSwitchCases(unittest.TestCase):
    def observe(self, label, config):
        args = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory, output=ARGS.output / label)
        trial = StageTrial(args)
        try:
            with trial.hc.open("a", encoding="utf-8") as file:
                for key, value in config.items():
                    file.write(f"{key}={value}\n")
            trial.grant_token = secrets.token_urlsafe(36)
            with trial.mc.open("a", encoding="utf-8") as file:
                file.write(f"sessionIsolation=true\ngrantAccountId={ACCOUNT}\n"
                           "grantAccessTokenEnv=TRIAL_SOURCED_GRANT\n")
            with patch.dict(os.environ, {"TRIAL_SOURCED_GRANT": trial.grant_token}):
                trial.start_memory("memory")
            trial.mem("ingest", dict(context=private(PEER), source_instance=SOURCE_INSTANCE,
                      kind="observation", occurred_at="2026-01-01T11:00:00Z", event_id="extra-topic", content=EXTRA))
            # 基座从真实入站触发自动召回，核对同一私聊实收一条回复、精确来源版本及持久回执。
            trial.first_reply()
            events = trial.sequence
        finally:
            trial.close()

        self.assertEqual([kind for kind, _ in events if kind != "read"], ["recall", "plan", "recall", "draft"])
        first, second = [items for kind, items in events if kind == "recall"]
        texts = {item["ref"]["record_id"]: item["text"] for item in [*first, *second]
                 if item.get("text") in MARKERS}
        automatic, requested = [{item["text"] for item in items if item.get("text") in MARKERS}
                                for items in (first, second)]
        self.assertEqual(automatic, {LOCAL, SHARED})
        self.assertIn(EXTRA, requested)
        self.assertNotIn(LOCAL, requested, "自动召回的本地材料用于区别最后两个复核阶段")

        # 只追踪上述实收记录 ID。允许 Memory 合并批次，同一窗口内的读请求数量不属于本契约。
        windows = [set() for _ in range(4)]
        history_reads = [0] * 5
        window = -1
        for kind, data in events:
            if kind != "read":
                window += 1
                continue
            targets = {t["record_id"] for t in data["targets"]}
            matched = {texts[target] for target in targets if target in texts}
            if trial.history_id in targets:
                history_reads[window + 1] += 1
            if matched or trial.history_id in targets:
                self.assertEqual((data["kind"], data["use"], data["context"]["conversation_id"]),
                                 ("HISTORY", "CITE", PRIVATE))
            if matched:
                self.assertGreaterEqual(window, 0, "初次召回之前不应复核这组独立资料")
                windows[window].update(matched)
        self.assertEqual(history_reads[0], 1, "自动召回前要按当前受众读取一次实收自述")
        return windows, automatic, requested, history_reads[1:]

    def test_omitted_and_false_skip_extra_reads(self):
        """反例。各开关省略或显式关闭时，两个主题仍真实命中并完成送达，中途不重读这些来源。"""
        for label, config in (("omitted", {}), ("explicit-false", dict.fromkeys(STAGES, "false"))):
            with self.subTest(label=label):
                windows, _, _, history = self.observe(label, config)
                self.assertEqual(windows, [set(), set(), set(), set()])
                self.assertEqual(history, [0, 0, 0, 0])

    def test_each_enabled_check_reads_at_its_own_boundary(self):
        """正例。逐项独立开启时，公开端点只在对应阶段按原用途重读已经取到的资料。"""
        for stage in STAGES:
            with self.subTest(stage=stage):
                windows, automatic, requested, history = self.observe(stage, {stage: "true"})
                expected = {
                    "chatReviewRecallSources": [{SHARED}, set(), requested & {SHARED}, set()],
                    "chatReviewBeforePlan": [automatic, set(), set(), set()],
                    "chatReviewAfterPlan": [set(), automatic, set(), set()],
                    "chatReviewBeforeDraft": [set(), set(), requested, set()],
                    "chatReviewAfterDraft": [set(), set(), set(), requested],
                    "chatReviewBeforeReturn": [set(), set(), set(), automatic],
                }
                self.assertEqual(windows, expected[stage])
                marked = [i for i, reads in enumerate(history) if reads]
                if stage == "chatReviewRecallSources":
                    self.assertFalse(marked)
                else:
                    self.assertEqual(marked, [i for i, items in enumerate(expected[stage]) if items])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__])
