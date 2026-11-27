"""从带首轮备选回复的多轮会话，提取相同前文下的首轮偏好候选。"""

import argparse
import json
from pathlib import Path
from tempfile import TemporaryDirectory


REPLY_FIELDS = {"reasoning_content", "content"}
SOURCE_FIELDS = {"id", "family", "kind", "history", "chosen", "rejected", "opening_rejected"}


def check_fields(value, fields, location):
    """在源文件边界指出缺失或多出的字段，保留有效原文。"""
    if not isinstance(value, dict):
        raise ValueError(f"{location} 应为对象")
    different = fields.symmetric_difference(value)
    if different:
        raise ValueError(f"{location} 字段不符：{', '.join(sorted(different))}")


def check_text(value, location):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{location} 须为非空白字符串")


def opening_candidate(row, seen):
    """先校验整段来源，再按首次发言当时可见的历史提取两个续答。"""
    check_fields(row, SOURCE_FIELDS, "会话")
    for key in ("id", "family", "kind"):
        check_text(row[key], key)
    if row["id"] in seen:
        raise ValueError("id 须在文件内唯一")

    history = row["history"]
    if not isinstance(history, list) or len(history) < 3 or len(history) % 2 != 1:
        raise ValueError("history 须含至少三条消息，并以用户发言结束")
    for index, message in enumerate(history):
        location = f"history[{index}]"
        role = "assistant" if index % 2 else "user"
        text_fields = REPLY_FIELDS if role == "assistant" else {"content"}
        check_fields(message, text_fields | {"role"}, location)
        if message["role"] != role:
            raise ValueError(f"{location}.role 应为 {role}")
        for key in text_fields:
            check_text(message[key], f"{location}.{key}")

    for field in ("chosen", "rejected", "opening_rejected"):
        check_fields(row[field], REPLY_FIELDS, field)
        for key in REPLY_FIELDS:
            check_text(row[field][key], f"{field}.{key}")
    if row["chosen"] == row["rejected"]:
        raise ValueError("末轮 chosen 和 rejected 须有差异")

    chosen = {key: history[1][key] for key in ("reasoning_content", "content")}
    if chosen == row["opening_rejected"]:
        raise ValueError("opening_rejected 与首轮助理续答须有差异")
    seen.add(row["id"])
    return {"id": row["id"] + "-opening", "family": row["family"], "kind": row["kind"],
            "history": [history[0]], "chosen": chosen, "rejected": row["opening_rejected"]}


def extract(source, output):
    """同目录暂存整个 JSONL，末行通过校验后才发布，避免下游读到部分候选。"""
    seen = set()
    with TemporaryDirectory(dir=output.parent) as temp:
        pending = Path(temp) / "candidates.jsonl"
        with source.open("rb") as inputs, pending.open("w", encoding="utf-8", newline="\n") as stream:
            for number, raw in enumerate(inputs, 1):
                try:
                    row = json.loads(raw.decode("utf-8"))
                    candidate = opening_candidate(row, seen)
                except ValueError as error:
                    raise ValueError(f"第 {number} 行：{error}") from error
                stream.write(json.dumps(candidate, ensure_ascii=False, separators=(",", ":")) + "\n")
        if not seen:
            raise ValueError("第 1 行：缺少输入会话")
        pending.replace(output)


def main():
    """读取 High 作者标注的会话，为独立审核和密封查重准备输入。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    extract(args.input, args.output)


if __name__ == "__main__":
    main()
