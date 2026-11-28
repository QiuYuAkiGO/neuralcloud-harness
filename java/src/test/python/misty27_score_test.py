"""从公开命令行核对百分制汇总和回执不完整时的发布行为。"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCORER = Path(__file__).resolve().parents[4] / "tools" / "misty27_score.py"


def scoring_inputs():
    """12 个占位能力案中，01、06、08 回退，12 改善，净少答对两案。"""
    grades_by_case = [
        {"I1": 0},
        {"I1": 1},
        {"I1": 1},
        {"I1": 1},
        {"I1": 1},
        {"S1": 0.5},
        {"S1": 1},
        {"T1": 0.5},
        {"T1": 1},
        {"T1": 1},
        {"I2": 1, "S2": 1, "T2": 1},
        {"I3": 1, "S3": 1, "T3": 1},
    ]
    manifest = []
    judgments = {"cases": [], "collapse_evidence": []}
    baseline = {"cases": [], "collapse_evidence": []}
    for number, grades in enumerate(grades_by_case, start=1):
        case_id = f"{number:02d}"
        manifest.append({"id": case_id, "kind": "ability", "criteria": list(grades)})
        judgments["cases"].append(
            {"id": case_id, "grades": grades, "passed": number not in (1, 6, 8), "events": []}
        )
        baseline["cases"].append(
            {"id": case_id, "grades": dict.fromkeys(grades, 1), "passed": number != 12, "events": []}
        )
    return manifest, judgments, baseline


def run_score(directory, manifest, judgments, baseline):
    """写入三份调用方数据，只调用一次正式入口，并交回进程结果和发布位置。"""
    directory = Path(directory)
    arguments = [sys.executable, "-X", "utf8", str(SCORER)]
    for name, data in (("manifest", manifest), ("judgments", judgments), ("baseline", baseline)):
        path = directory / f"{name}.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        arguments.extend([f"--{name}", str(path)])
    output = directory / "score.json"
    result = subprocess.run(
        [*arguments, "--output", str(output)], capture_output=True, text=True, encoding="utf-8"
    )
    return result, output


class Misty27ScoreTest(unittest.TestCase):
    def test_weighted_dimensions_and_regression_penalty_at_qualification_threshold(self):
        """I1 五案得 4 分，S1 两案得 6 分，T1 三案得 10 分，退化扣分后恰好合格。"""
        with tempfile.TemporaryDirectory() as directory:
            result, output = run_score(directory, *scoring_inputs())
            self.assertEqual(result.returncode, 0, result.stderr)
            score = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(score["items"], {
            "I1": 4, "I2": 7, "I3": 8,
            "S1": 6, "S2": 12, "S3": 15,
            "T1": 10, "T2": 15, "T3": 18,
        })
        self.assertEqual(score["dimensions"], {"I": 19, "S": 33, "T": 43})
        self.assertEqual(score["raw"], 95)
        self.assertEqual(score["penalty"], 15)
        self.assertEqual(score["total"], 80)
        self.assertIs(score["qualified"], True)
        self.assertIs(score["target"], False)

    def test_incomplete_judgments_name_the_violation_and_publish_no_score(self):
        """分别漏交一个冻结考项和一份基线个案回执，单个缺项即阻止发布。"""
        for missing in ("grade", "case"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                manifest, judgments, baseline = scoring_inputs()
                if missing == "grade":
                    del judgments["cases"][10]["grades"]["S2"]
                    diagnostic = r"(?i)\bgrades\b|\bS2\b|考项"
                else:
                    del baseline["cases"][9]
                    diagnostic = r"(?i)\bcases?\b|\bids?\b|\b10\b|案例|题号|缺案"

                result, output = run_score(directory, manifest, judgments, baseline)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                self.assertRegex(result.stdout + result.stderr, diagnostic)


if __name__ == "__main__":
    unittest.main()
