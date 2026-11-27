"""从公开命令行核对首轮偏好的提取与失败时的文件保留。"""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]


def annotated_session(identifier, family, kind, user, chosen, alternative):
    """构造含三条历史消息的标注会话，末轮偏好使用独立的答复文本。"""
    return {
        "id": identifier,
        "family": family,
        "kind": kind,
        "history": [
            user,
            {"role": "assistant", **chosen},
            {"role": "user", "content": "我们十点见好吗？"},
        ],
        "chosen": {"reasoning_content": "已知道时间。", "content": "十点见。"},
        "rejected": {"reasoning_content": "已知道时间。", "content": "八点见。"},
        "opening_rejected": alternative,
    }


def convert(directory, sessions):
    """把本例的 UTF-8 JSONL 交给真实 CLI，收集进程状态和诊断。"""
    source = directory / "sessions.jsonl"
    source.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in sessions),
        encoding="utf-8",
    )
    return subprocess.run(
        [
            sys.executable, "tools/misty27_opening_preferences.py",
            "--input", source, "--output", directory / "openings.jsonl",
        ],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )


class OpeningPreferencesTest(unittest.TestCase):
    def test_emits_first_user_and_both_opening_answers_in_input_order(self):
        """两组输入次序与 ID 排序相反，两组首轮偏好各仅相差一个文本字段。"""
        first_user = {"role": "user", "content": "  把写着‘山’的书签留给我。\n谢谢！"}
        first_chosen = {"reasoning_content": "她说的是书签。", "content": " 好，我给你留着。 "}
        first_alternative = {"reasoning_content": "她说的是那本书。", "content": " 好，我给你留着。 "}
        second_user = {"role": "user", "content": "八和六相差几？"}
        second_chosen = {"reasoning_content": "八减六等于二。", "content": "相差 2。"}
        second_alternative = {"reasoning_content": "八减六等于二。", "content": "相差 4。"}
        sessions = [
            annotated_session("z-8", "书签", "对话", first_user, first_chosen, first_alternative),
            annotated_session("a-3", "数值", "计算", second_user, second_chosen, second_alternative),
        ]
        sessions[0]["history"].extend([
            {"role": "assistant", "reasoning_content": "还需确认地点。", "content": "在哪见面？"},
            {"role": "user", "content": "门口。"},
        ])
        expected = [
            {"id": "z-8-opening", "family": "书签", "kind": "对话", "history": [first_user],
             "chosen": first_chosen, "rejected": first_alternative},
            {"id": "a-3-opening", "family": "数值", "kind": "计算", "history": [second_user],
             "chosen": second_chosen, "rejected": second_alternative},
        ]

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = convert(directory, sessions)
            self.assertEqual(result.returncode, 0, result.stdout)
            lines = (directory / "openings.jsonl").read_text(encoding="utf-8").splitlines()
            self.assertEqual([json.loads(line) for line in lines], expected)

    def test_identical_opening_answers_on_second_line_preserve_previous_output(self):
        """首行合法、次行首轮偏好完全相同，入口须定位错误并保留已有文件的字节。"""
        user = {"role": "user", "content": "还剩几张票？"}
        chosen = {"reasoning_content": "还剩两张。", "content": "两张。"}
        other = {"reasoning_content": "还剩一张。", "content": "一张。"}
        sessions = [
            annotated_session("valid", "余票", "询问", user, chosen, other),
            annotated_session("invalid", "余票", "询问", user, chosen, chosen),
        ]
        previous_row = {
            "id": "old-opening", "family": "余票", "kind": "询问", "history": [user],
            "chosen": chosen, "rejected": other,
        }
        previous = (json.dumps(previous_row, ensure_ascii=False) + "\r\n").encode("utf-8")

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output = directory / "openings.jsonl"
            output.write_bytes(previous)
            result = convert(directory, sessions)
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("opening_rejected", result.stdout)
            self.assertRegex(result.stdout, r"(?<!\d)2(?!\d)")
            self.assertEqual(output.read_bytes(), previous)


if __name__ == "__main__":
    unittest.main()
