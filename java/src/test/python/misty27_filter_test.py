"""由实验 Python 运行，环境变量 MISTY27_E5_PATH 指向冻结 E5 的本地目录。"""

import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


FILTER = Path(__file__).resolve().parents[4] / "tools" / "misty27_filter.py"
FIRING = "请把陶窑加热到九百八十度，然后记录青釉试片烧成后的光泽。"
PARAPHRASE = "请把陶窑升温至九百八十度，然后记录青釉试片烧成后的光泽。"
GRAMMAR = "Change 'the owl perched quietly' to the past perfect tense."


def run_filter(directory, blind, candidates, *options):
    """从文件提供两组独立样本，每组输入只调用一次公开入口。"""
    directory = Path(directory)
    arguments = [sys.executable, "-X", "utf8", str(FILTER)]
    for name, rows in (("blind", blind), ("candidates", candidates)):
        source = directory / f"{name}.json"
        source.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        arguments.extend([f"--{name}", str(source)])
    output = directory / "report.json"
    result = subprocess.run(
        [*arguments, "--encoder", os.environ["MISTY27_E5_PATH"], "--output", str(output), *options],
        capture_output=True, text=True, encoding="utf-8",
    )
    return result, output


class Misty27FilterTest(unittest.TestCase):
    def test_public_receipt_for_exact_semantic_duplicate_and_distant_candidates(self):
        """相同单轮和后窗近义改写均需剔除，共用一轮的不同完整情境可各自保留。"""
        geometry = "Given a triangle with side lengths 3, 4 and 5, calculate its area using Heron's formula."
        blind = [{"id": "private-73", "turns": [FIRING, "试片编号是  ＣＹ１７。"]}]

        # 几何前缀超过 512 词元，后部改写即使越过首窗也应触发语义剔除。
        late_paraphrase = " ".join([geometry] * 40 + [PARAPHRASE] * 32)
        candidates = [
            {"id": "C9", "turns": [geometry]},
            {"id": "C3", "turns": [GRAMMAR, "试片编号是 cy17。"]},
            {"id": "C4", "turns": [PARAPHRASE]},
            {"id": "C2", "turns": [geometry, GRAMMAR]},
            {"id": "C5", "turns": [geometry.upper().replace(" ", "   ")]},
            {"id": "C7", "turns": [late_paraphrase]},
        ]
        with tempfile.TemporaryDirectory() as directory:
            result, output = run_filter(directory, blind, candidates)
            self.assertEqual(result.returncode, 0, result.stderr)
            receipt = json.loads(output.read_text(encoding="utf-8"))

        # 对照完整公开字段集合，私有编号、原话、位置或相似度均不应出现在回执。
        self.assertEqual(set(receipt), {"accepted", "rejected"})
        self.assertEqual(receipt["accepted"], ["C9", "C2"])
        self.assertCountEqual(receipt["rejected"], [
            {"id": "C3", "reason": "exact"},
            {"id": "C4", "reason": "semantic"},
            {"id": "C5", "reason": "duplicate"},
            {"id": "C7", "reason": "semantic"},
        ])

    def test_invalid_field_prevents_publication(self):
        """候选重复编号与私有话轮类型错误分别破坏一个字段，任一项都应阻止发布。"""
        for invalid in ("id", "turns"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                blind = [{"id": "B1", "turns": ["纸盒里放着三枚衣扣。"]}]
                candidates = [{"id": "C1", "turns": ["Please label the x-axis in seconds."]}]
                if invalid == "id":
                    candidates.append({"id": "C1", "turns": ["What is the median of 2, 5 and 6?"]})
                    diagnostic = r"(?i)\bids?\b|编号"
                else:
                    blind[0]["turns"] = [17]
                    diagnostic = r"(?i)\bturns?\b|话轮"

                result, output = run_filter(directory, blind, candidates)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                self.assertRegex(result.stdout + result.stderr, diagnostic)

    def test_calibrated_review_rejects_matches_and_holds_every_other_candidate(self):
        """约 0.996 的近义句自动剔除，约 0.883 的同主题句和约 0.783 的异题句均待审。"""
        blind = [{"id": "sealed-73", "turns": [FIRING]}]
        candidates = [
            {"id": "exact-01", "turns": [FIRING]},
            {"id": "close-02", "turns": [PARAPHRASE]},
            {"id": "middle-03", "turns": ["在陶艺教室里，该用多少目的砂纸磨掉干坯底部的毛刺？"]},
            {"id": "far-04", "turns": [GRAMMAR]},
            {"id": "repeat-05", "turns": [GRAMMAR.upper()]},
        ]
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / "private.json"
            result, output = run_filter(
                directory, blind, candidates, "--calibrated-review", "--neighbors", str(private)
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            receipt = json.loads(output.read_text(encoding="utf-8"))
            neighbors = private.read_text(encoding="utf-8")

        self.assertEqual(receipt["accepted"], [])
        self.assertCountEqual(receipt["pending"], ["middle-03", "far-04"])
        self.assertCountEqual(receipt["rejected"], [
            {"id": "exact-01", "reason": "exact"},
            {"id": "close-02", "reason": "semantic"},
            {"id": "repeat-05", "reason": "duplicate"},
        ])
        self.assertNotIn("sealed-73", str(receipt))
        for identifier in ("sealed-73", "middle-03", "far-04"):
            self.assertIn(identifier, neighbors)

    def test_calibrated_review_requires_a_separate_private_destination(self):
        """漏传私有路径或与公开回执共用路径时，入口必须在发布前拒绝。"""
        blind = [{"id": "sealed-73", "turns": [FIRING]}]
        candidates = [{"id": "far-04", "turns": [GRAMMAR]}]
        for use_public_path in (False, True):
            with self.subTest(use_public_path=use_public_path), tempfile.TemporaryDirectory() as directory:
                options = ["--calibrated-review"]
                if use_public_path:
                    options.extend(["--neighbors", str(Path(directory) / "report.json")])
                result, output = run_filter(directory, blind, candidates, *options)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                # argparse 的 usage 会罗列可选参数，违规字段须出现在实际诊断中。
                diagnostics = re.sub(r"(?im)^usage:.*(?:\n[ \t].*)*", "", result.stdout + result.stderr)
                self.assertRegex(diagnostics, r"(?i)neighbors?|private|近邻|私有|输出路径")


if __name__ == "__main__":
    unittest.main()
