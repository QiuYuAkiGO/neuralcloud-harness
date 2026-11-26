"""从正式 ConsoleMain 验收 Dreaming 补正、挂起与恢复，仅使用回环模型和独立 Memory。"""
import argparse
import json
import re
import time
from pathlib import Path

from dreaming import DreamTrial, GEMINI_PATH, OPENAI_PATH, SCOPE, context, ref, tool_call
from qq_observe import call


NOTE = "北岸组每次收工都把两本值班记录放回西柜。"
CHANGED = "更正，北岸组现在把值班记录放回东柜。"


class ValidationTrial(DreamTrial):
    """对真实请求记录模型可读的指令及材料，每个用例只指定首答和补正的结果。"""
    def model_reply(self, path, body, headers):
        if path == OPENAI_PATH:
            schema = body["tools"][0]["function"]["parameters"]
            texts = [m["content"] for m in body["messages"]
                     if m["role"] != "assistant" and isinstance(m.get("content"), str)]
            prompt = "\n".join(m["content"] for m in body["messages"] if m["role"] == "system")
        else:
            assert path == GEMINI_PATH
            config = body["generationConfig"]
            schema = config.get("responseJsonSchema") or config["responseSchema"]
            instruction = body.get("systemInstruction") or body.get("system_instruction") or {}
            prompt = "\n".join(p["text"] for p in instruction.get("parts", []) if "text" in p)
            texts = [prompt] + [p["text"] for m in body["contents"] if m.get("role", "user") != "model"
                                for p in m["parts"] if "text" in p]
        refs_schema = schema["properties"]["changes"]["items"]["properties"]["source_refs"]
        self.model_calls.append(dict(texts=texts, prompt=prompt, schema=refs_schema))
        attempt = len(self.model_calls)
        # 只让指定那次补正失败。若重试失控，第三次将生成合法空提交，Memory 出口也能观察到越界。
        changes = []
        if attempt <= 2:
            if attempt == 1:
                refs = []
            elif self.scenario == "correct":
                refs = [ref(self.source)]
            else:
                refs = None
            changes.append(dict(kind="topic", subject="收工记录", expected_version=0, text="我记下收工时的归档习惯。",
                                scope=SCOPE, reason="值班员直接说明了收工时的动作。", source_refs=refs))
        result = {"changes": changes}
        if path == OPENAI_PATH:
            return 200, tool_call("submit_dreaming", result)
        content = dict(role="model", parts=[{"text": json.dumps(result, ensure_ascii=False)}])
        return 200, dict(candidates=[dict(content=content, finishReason="STOP")], modelVersion="dream-trial")


def check_request(trial):
    """在模型端分别核对同批材料、补正新增的具体错误和初答前已经可见的数量限制。"""
    first, correction = trial.model_calls[:2]
    for sent in (first, correction):
        material = "\n".join(sent["texts"])
        assert NOTE in material and trial.source["record_id"] in material, "补正必须收到同一条证据"
    extra = "\n".join(t for t in correction["texts"] if t not in first["texts"])
    assert "source_refs" in extra and re.search(r"(?<!\d)0(?!\d)|空数组|empty array", extra, re.I), (
        "补正应指出 source_refs 为空或实际为 0 条", extra)
    schema = first["schema"]
    assert (schema.get("minItems"), schema.get("maxItems")) == (1, 16), "模型 schema 应限定每条引用为 1–16 条"
    assert "source_refs" in first["prompt"] and re.search(r"(?:1|一).{0,40}?(?:16|十六)", first["prompt"], re.S), (
        "提示词应告诉模型引用的上下限", first["prompt"])


def run(trial, scenario):
    """一次补正获准时只写一次，连续两次不合约时跨重启保留同一覆盖位置。"""
    memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
    trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
    trial.scenario = scenario
    trial.source = trial.ingest("source", NOTE, "值班员")
    gemini = scenario == "suspend"
    trial.start_dreaming(gemini=gemini)
    trial.wait_for(lambda: len(trial.model_calls) >= 2, "模型收到同批的补正请求")
    if scenario == "correct":
        row = trial.wait_committed(trial.source)
        assert len(trial.model_calls) == 2 and len(trial.commits) == 1
        assert len(row["command"]["changes"]) == 1 and row["command"]["changes"][0]["source_refs"] == [ref(trial.source)]
        check_request(trial)
        return

    offered = next(b for b in trial.batches if b["after"] < b["through"])
    # 配置的轮询间隔为一秒。分别在两次运行里给调度器多个周期来尝试错误的第三次调用。
    for restarted in (False, True):
        if restarted:
            trial.stop_harness()
            trial.launch(trial.settings(gemini=True), trial.console)
            trial.ready(lambda: call(trial.hp, "/api/nodes")[0] == 200, trial.hproc)
        time.sleep(3.2)
        assert len(trial.model_calls) == 2 and not trial.commits, "同批补正失败后轮询和重启都必须停止调用与提交"
    current = trial.mem("dreaming/batch", dict(context=context("dreaming:trial"), scopes=[SCOPE], limit=16))
    assert (current["after"], current["through"]) == (offered["after"], offered["through"]), "挂起不能推进覆盖"
    check_request(trial)

    # 换成 Memory 新签发的证据修订后，宿主可以重新整理；合法空 changes 同样取得持久回执。
    trial.correct(trial.source, CHANGED)
    trial.wait_for(lambda: trial.commits, "领取变化后的材料并取得回执")
    row, = trial.commits
    assert len(trial.model_calls) == 3 and CHANGED in "\n".join(trial.model_calls[-1]["texts"])
    assert row["response"]["status"] == "DURABLE" and row["command"]["changes"] == []


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("harness", "memory", "output"):
        parser.add_argument(arg, type=Path)
    parser.set_defaults(live=False)
    args = parser.parse_args()
    for case in ("correct", "suspend"):
        trial = ValidationTrial(argparse.Namespace(**{**vars(args), "output": args.output / case}))
        try:
            run(trial, case)
            print("PASS", case)
        finally:
            trial.close()
