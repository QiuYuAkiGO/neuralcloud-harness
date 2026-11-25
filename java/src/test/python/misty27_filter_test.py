"""由实验 Python 运行，环境变量 MISTY27_E5_PATH 指向冻结 E5 的本地目录。"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


FILTER = Path(__file__).resolve().parents[4] / "tools" / "misty27_filter.py"


def run_filter(directory, blind, candidates):
    """从文件提供两组独立样本，每组输入只调用一次公开入口。"""
    directory = Path(directory)
    arguments = [sys.executable, "-X", "utf8", str(FILTER)]
    for name, rows in (("blind", blind), ("candidates", candidates)):
        source = directory / f"{name}.json"
        source.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        arguments.extend([f"--{name}", str(source)])
    output = directory / "report.json"
    result = subprocess.run(
        [*arguments, "--encoder", os.environ["MISTY27_E5_PATH"], "--output", str(output)],
        capture_output=True, text=True, encoding="utf-8",
    )
    return result, output


class Misty27FilterTest(unittest.TestCase):
    def test_public_receipt_for_exact_semantic_duplicate_and_distant_candidates(self):
        """相同单轮和后窗近义改写均需剔除，共用一轮的不同完整情境可各自保留。"""
        firing = "请把陶窑加热到九百八十度，然后记录青釉试片烧成后的光泽。"
        paraphrase = "请把陶窑升温至九百八十度，然后记录青釉试片烧成后的光泽。"
        geometry = "Given a triangle with side lengths 3, 4 and 5, calculate its area using Heron's formula."
        grammar = "Change 'the owl perched quietly' to the past perfect tense."
        blind = [{"id": "private-73", "turns": [firing, "试片编号是  ＣＹ１７。"]}]

        # 几何前缀超过 512 词元，后部改写即使越过首窗也应触发语义剔除。
        late_paraphrase = " ".join([geometry] * 40 + [paraphrase] * 32)
        candidates = [
            {"id": "C9", "turns": [geometry]},
            {"id": "C3", "turns": [grammar, "试片编号是 cy17。"]},
            {"id": "C4", "turns": [paraphrase]},
            {"id": "C2", "turns": [geometry, grammar]},
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


if __name__ == "__main__":
    unittest.main()
