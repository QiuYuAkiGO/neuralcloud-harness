"""Playground 评分、回填和实录汇总的黑盒验收，全部输入由测试构造。

在仓库根目录执行 python -m unittest discover -s java/src/test/python -p playground_scores_test.py -v
"""

import hashlib
import json
import math
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from playground.benchmark import accept_scores, metric_report
from playground.scoring import summarize


def scored_row(example_id, metric, score):
    """CharacterRM 将分数写在以 metric_en 的值命名的字段中。"""
    return {"id": example_id, "metric_en": metric, metric: score}


class CharacterEvalReportTest(unittest.TestCase):
    """同一题可以分配多个指标；每个题目和指标的组合只有一次观测。"""

    def test_per_metric_denominators_and_run_scope(self):
        expected = {"11": ["Accuracy", "Behavior"], "22": ["Accuracy"]}
        rows = [scored_row(11, "Behavior", 4.25), scored_row(22, "Accuracy", 3.5),
                scored_row(11, "Accuracy", 1.5)]
        for full, kind in [(True, "full"), (False, "sample")]:
            with self.subTest(scope=kind):
                report = metric_report(rows, expected_ids=expected, full=full)
                self.assertEqual((kind, "complete"), (report["kind"], report["status"]))
                self.assertEqual(2, report["metrics"]["Accuracy"]["count"])
                self.assertAlmostEqual(2.5, report["metrics"]["Accuracy"]["mean"])
                self.assertEqual(1, report["metrics"]["Behavior"]["count"])
                self.assertAlmostEqual(4.25, report["metrics"]["Behavior"]["mean"])

    def test_missing_assignment_keeps_full_run_incomplete(self):
        # 两道题均有产出，缺少其中一道的第二个指定指标，仍不构成完整评测。
        expected = {"11": ["Accuracy", "Behavior"], "22": ["Accuracy"]}
        rows = [scored_row(11, "Accuracy", 3), scored_row(22, "Accuracy", 5)]
        report = metric_report(rows, expected_ids=expected, full=True)
        self.assertEqual("incomplete", report["status"])

    def test_invalid_score_rows_are_rejected(self):
        expected = {"11": ["Accuracy"]}
        valid = scored_row(11, "Accuracy", 4.0)
        inputs = {
            "重复计分": [valid, valid.copy()],
            "未分配的指标": [scored_row(11, "Behavior", 4.0)],
            "不属于本次的题": [scored_row(12, "Accuracy", 4.0)],
            "非数值结果": [scored_row(11, "Accuracy", math.nan)],
            "无穷结果": [scored_row(11, "Accuracy", math.inf)],
        }
        for label, rows in inputs.items():
            with self.subTest(label=label), self.assertRaises(ValueError):
                metric_report(rows, expected_ids=expected, full=True)


class CharacterEvalImportTest(unittest.TestCase):
    """离线评分须与此批的生成字节、权重版本和全部题目指标一一对应。"""

    weights = "e953da5f57b86db7adca3a2714b4c3720f5b96a2"

    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.job = Path(directory.name)
        self.generation = self.job / "results" / "generation.jsonl"
        self.generation.parent.mkdir()
        record = {"id": 11, "role": "虚构角色", "novel_name": "验收", "context": "提问者：几点？",
                  "model_output": "我记得是上午。"}
        payload = (json.dumps([record], ensure_ascii=False) + "\n").encode("utf-8")
        self.generation.write_bytes(payload)
        self.digest = hashlib.sha256(payload).hexdigest()
        manifest = {"generationHash": self.digest, "weightsRevision": self.weights,
                    "expectedIds": {"11": ["Accuracy", "Behavior"]}, "full": False}
        (self.job / "job.json").write_text(json.dumps(manifest), encoding="utf-8")
        self.rows = [scored_row(11, "Accuracy", 3.5), scored_row(11, "Behavior", 4.25)]
        self.report = self.job / "report.json"

    def test_complete_offline_scores_are_saved(self):
        result = accept_scores(self.job, self.rows, self.digest, self.weights)
        self.assertEqual(("sample", "complete"), (result["kind"], result["status"]))
        for metric, mean in [("Accuracy", 3.5), ("Behavior", 4.25)]:
            self.assertEqual((1, mean), (result["metrics"][metric]["count"],
                                         result["metrics"][metric]["mean"]))
        saved = json.loads(self.report.read_text(encoding="utf-8"))
        for key in ("kind", "status", "metrics"):
            self.assertEqual(result[key], saved[key])

    def test_rejected_import_preserves_previous_report(self):
        previous = b'{"kind":"sample","status":"awaiting_scores"}\n'
        self.report.write_bytes(previous)
        wrong_hash = "0" * 64
        wrong_version = "0" * 40
        failures = [
            ("缺少指定指标", self.rows[:1], self.digest, self.weights),
            ("误入另一题", self.rows + [scored_row(12, "Behavior", 2)], self.digest, self.weights),
            ("调用方错批", self.rows, wrong_hash, self.weights),
            ("权重版本有误", self.rows, self.digest, wrong_version),
        ]
        for label, rows, digest, version in failures:
            with self.subTest(label=label):
                with self.assertRaises(ValueError):
                    accept_scores(self.job, rows, digest, version)
                self.assertEqual(previous, self.report.read_bytes())
        # 调用方与任务清单仍然相符；单独检验实际生成文件已经变更的情况。
        self.generation.write_bytes(b"[]\n")
        with self.assertRaises(ValueError):
            accept_scores(self.job, self.rows, self.digest, self.weights)
        self.assertEqual(previous, self.report.read_bytes())


def observation(identifier, kind, addressing, policy, expected, decision,
                persona=None, reasonable=None, status="complete"):
    """构造一个实录运行的公开汇总输入，独立指定人工参与标签、实际决定和两项判分。"""
    return {"id": identifier, "kind": kind, "addressing": addressing, "policy": policy,
            "expected": expected, "decision": decision, "status": status,
            "persona": persona, "reasonable": reasonable}


class DialogueReportTest(unittest.TestCase):
    """分别核对群聊参与和已生成回复，防止把静默当作一条高质量答复。"""

    def test_correct_participation_and_acceptable_replies(self):
        report = summarize([
            observation("invited", "group", "AMBIENT", "OBSERVE_REPLY", "REPLY", "REPLY",
                        "PASS", "PASS"),
            observation("overhearing", "group", "AMBIENT", "OBSERVE_REPLY", "SKIP", "SKIP"),
            observation("private", "private", "DIRECT", None, None, "REPLY", "PASS", "PASS"),
        ])
        for key in ("unwanted", "missed"):
            self.assertEqual({"count": 0, "total": 1}, report["participation"][key])
        for kind in ("group", "private"):
            self.assertEqual((1, 1, 0, 0), tuple(report[kind][key]
                             for key in ("passed", "total", "pending", "failed")))

    def test_mistakes_ambiguous_examples_and_ungraded_outputs(self):
        report = summarize([
            observation("interrupt", "group", "AMBIENT", "OBSERVE_REPLY", "SKIP", "REPLY",
                        "PASS", "FAIL"),
            observation("miss", "group", "AMBIENT", "OBSERVE_REPLY", "REPLY", "SKIP"),
            observation("either", "group", "AMBIENT", "OBSERVE_REPLY", "EITHER", "REPLY",
                        "FAIL", "PASS"),
            observation("mention", "group", "DIRECT", "OBSERVE_REPLY", None, "REPLY"),
            observation("observe-only", "group", "DIRECT", "OBSERVE", None, "SKIP"),
            observation("service-error", "group", "AMBIENT", "OBSERVE_REPLY", "REPLY", None,
                        status="failed"),
            observation("private-pending", "private", "DIRECT", None, None, "REPLY"),
        ])
        for key in ("unwanted", "missed"):
            self.assertEqual({"count": 1, "total": 1}, report["participation"][key])
        for kind, counts in [("group", (0, 2, 1, 1)), ("private", (0, 0, 1, 0))]:
            self.assertEqual(counts, tuple(report[kind][key]
                             for key in ("passed", "total", "pending", "failed")))


if __name__ == "__main__":
    unittest.main()
