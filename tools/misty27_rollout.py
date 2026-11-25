"""从聊天补全接口采集小雾 27B 的真实多轮答卷，保留服务返回的两个通道。"""

import argparse
import json
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import Request, urlopen


def publish_json(path, value):
    """在目标目录完成整份写入才替换结果，失败时保留已有文件。"""
    stream = NamedTemporaryFile(dir=path.parent, delete=False)
    temporary = Path(stream.name)
    try:
        with stream:
            stream.write((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_cases(path):
    """采集端只接收编号与用户原话，在发起任何请求前校验全部输入。"""
    cases = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(cases, list) or not cases:
        raise ValueError("cases 须为非空数组")
    ids = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) != {"id", "turns"}:
            raise ValueError("cases 的字段须为 id、turns")
        key, turns = case["id"], case["turns"]
        if not isinstance(key, str) or not key.strip() or key in ids:
            raise ValueError("id 须为唯一非空字符串")
        if not isinstance(turns, list) or not turns or not all(isinstance(t, str) and t.strip() for t in turns):
            raise ValueError(f"{key} 的 turns 须为非空的用户原话数组")
        ids.add(key)
    return cases


def complete(endpoint, model, history):
    """每轮使用同一思考与解码设置，长度耗尽时照实保存不完整的输出。"""
    payload = {
        "model": model, "messages": history, "temperature": 0, "seed": 27,
        "max_tokens": 2048, "chat_template_kwargs": {"enable_thinking": True},
    }
    req = Request(endpoint, data=json.dumps(payload).encode("utf-8"),
                  headers={"Content-Type": "application/json"})
    with urlopen(req, timeout=600) as response:
        result = json.load(response)
    choices = result["choices"]
    if not isinstance(choices, list) or len(choices) != 1:
        raise ValueError("choices 须有一条返回")
    choice = choices[0]
    reason, message = choice["finish_reason"], choice["message"]
    if reason not in ("stop", "length"):
        raise ValueError(f"finish_reason 不在实验契约内：{reason!r}")
    channels = {}
    for field in ("reasoning_content", "content"):
        value = message[field]
        if not isinstance(value, str) or (reason == "stop" and not value.strip()):
            raise ValueError(f"{field} 须返回文本，正常停止时须非空")
        channels[field] = value
    return {**channels, "finish_reason": reason}


def collect(case, endpoint, model):
    """模型只看到自己的历史正文，评测侧另存每轮推理以便裁判检查。"""
    history, turns = [], []
    for user in case["turns"]:
        history.append({"role": "user", "content": user})
        turn = complete(endpoint, model, history)
        turns.append(turn)
        history.append({"role": "assistant", "content": turn["content"]})
    return {"id": case["id"], "turns": turns}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--cases", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    cases = read_cases(args.cases)
    publish_json(args.output, [collect(case, args.endpoint, args.model) for case in cases])


if __name__ == "__main__":
    main()
