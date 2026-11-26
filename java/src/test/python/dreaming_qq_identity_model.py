"""真实 ConsoleMain 和 Memory 验收 QQ 称呼的单次补正、稳定人物归档与同批挂起。"""
import argparse
import json
import re
from pathlib import Path

from dreaming import GEMINI_PATH, OPENAI_PATH, ref, tool_call
from dreaming_qq import A, QqDreamTrial
from qq_observe import call


INTRO = "我叫栖湾。"
HOBBY = "我也叫湾湾。我周末喜欢上山拍照。"
NICK = "我也叫岚岚。"
NAMES = {INTRO: "栖湾", HOBBY: "湾湾", NICK: "岚岚"}


class IdentityModels(QqDreamTrial):
    """按批次实际签发的人物和原话提出称呼，记录进入模型与到达 Memory 的公开载荷。"""
    def model_reply(self, path, body, headers):
        if path == OPENAI_PATH:
            user, = [m["content"] for m in body["messages"] if m["role"] == "user"]
            schema = body["tools"][0]["function"]["parameters"]
        else:
            assert path == GEMINI_PATH
            user = "".join(part["text"] for m in body["contents"] if m.get("role", "user") == "user"
                           for part in m["parts"])
            generation = body["generationConfig"]
            schema = generation.get("responseJsonSchema") or generation["responseSchema"]
        assert "name_claims" in schema["properties"], "模型输出契约应声明称呼操作"
        assert "person_id" in schema["properties"]["changes"]["items"]["properties"]
        packet = json.loads(user)
        # 本批也可能附上旧档案的一手依据。选择最新投递的那句作为此次提案的来源。
        by_text = {e["content"]: e for e in packet["evidence"]}
        note = next(n for n in (NICK, HOBBY, INTRO) if n in by_text)
        evidence = by_text[note]
        pid = evidence["speaker_person_id"]
        person, = [p for p in packet["persons"] if p["person_id"] == pid]
        assert isinstance(person["aliases"], list)
        attempt = sum(m["note"] == note for m in self.model_calls)
        claim = dict(person_id=pid, expected_identity_version=person["identity_version"],
                     name=NAMES[note], relation="SELF_NAME", scope="global",
                     source_refs=[dict(ref(evidence), quote=note.split("。", 1)[0] + "。")])
        changes = []
        if note == INTRO and attempt == 0:
            # 同格式的另一标识从未签发给模型。
            claim["person_id"] = pid[:-1] + ("0" if pid[-1] != "0" else "1")
            assert all(p["person_id"] != claim["person_id"] for p in packet["persons"])
        elif note == NICK and attempt < 2:
            # 两次均伪造引文。若发生第三次调用则返回合法答案，使越界重试在提交出口也可见。
            claim["source_refs"][0]["quote"] = ("我小时候就叫岚岚。", "大家一直叫我岚岚。")[attempt]
        elif note == HOBBY:
            assert person["subject"] == "栖湾", person
            changes = [dict(kind="person", subject="栖湾", person_id=pid, expected_version=0,
                            text="我记得栖湾周末喜欢上山拍照。", scope="群里的自述", reason="本人说明周末爱好。",
                            source_refs=[ref(evidence)])]
        result = dict(changes=changes, name_claims=[claim])
        self.model_calls.append(dict(path=path, note=note, input=packet, person=person, proposal=result))
        if path == OPENAI_PATH:
            return 200, tool_call("submit_dreaming", result)
        return 200, {"candidates": [{"finishReason": "STOP", "content": {"role": "model", "parts": [
            {"text": json.dumps(result, ensure_ascii=False)}]}}]}


def check_correction(attempts, field, rule):
    """同批恰好一轮补正，证据和已签发的人物一致，新增诊断应指出这次违反的字段和规则。"""
    first, second = [attempt["input"] for attempt in attempts]
    assert "validation_error" not in first
    error = second["validation_error"]
    assert field in error and re.search(rule, error, re.I), (field, error)
    assert {k: v for k, v in second.items() if k != "validation_error"} == first


def run(t):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.start_dreaming()
    t.wait_for(lambda: t.panel()["connected"], "OneBot 名单就绪")
    t.emit(A, 761, INTRO, at=False)

    def submitted(name):
        return next((r for r in t.commits if r["status"] == 200 and
                     any(c["name"] == name for c in r["command"].get("name_claims", []))), None)

    only = t.wait_for(lambda: submitted("栖湾"), "兼容模型以精确原句只登记称呼")
    assert only["command"]["changes"] == [] and only["response"]["status"] == "DURABLE"
    attempts = [m for m in t.model_calls if m["path"] == OPENAI_PATH]
    check_correction(attempts, "person_id", r"本批|签发|已发|提供|issued|offered|batch")
    assert only["command"]["name_claims"] == attempts[1]["proposal"]["name_claims"]
    assert all(r["command"].get("name_claims") != attempts[0]["proposal"]["name_claims"] for r in t.commits), (
        "未签发的人物不应送达 Memory")
    assert t.profiles() == [], "只登记称呼不应生成没有认识的人物档案"

    # 同一账号再发一条原话。另一协议要看到第一次登记结果，并可同批登记别称和归属人物认识。
    t.stop_harness()
    t.start_dreaming(gemini=True)
    t.wait_for(lambda: t.panel()["connected"], "重启后群接收就绪")
    t.emit(A, 762, HOBBY, at=False)
    combined = t.wait_for(lambda: submitted("湾湾"), "原生 Gemini 同批登记新别称和既有人物认识")
    latest = next(m for m in t.model_calls if m["path"] == GEMINI_PATH)
    first = attempts[1]["person"]
    assert latest["person"]["person_id"] == first["person_id"]
    assert latest["person"]["identity_version"] > first["identity_version"]
    assert combined["response"]["status"] == "DURABLE"
    assert all(combined["command"][k] == latest["proposal"][k] for k in ("name_claims", "changes"))
    profile, = t.profiles()
    assert (profile["person_id"], profile["subject"], profile["text"]) == (
        first["person_id"], "栖湾", latest["proposal"]["changes"][0]["text"])

    # 同一账号再提供一个别称，但两次提案都伪造引文。挂起应写明原批位置和引文错误。
    t.emit(A, 763, NICK, at=False)

    def suspended():
        status, audit = call(t.hp, "/api/audit/chains/dreaming")
        if status == 404:
            return None  # 回执刚抵达时，该次启动的第一条整理审计可能尚未发布。
        assert status == 200, (status, audit)
        return next((e["data"] for e in audit["events"] if e["type"] == "dreaming_suspended"), None)

    stopped = t.wait_for(suspended, "首答和补正都伪造引文后挂起该批")
    bad = [m for m in t.model_calls if m["note"] == NICK]
    check_correction(bad, "quote", r"原句|原话|原文|逐字|证据|content|verbatim|\bsource\b|evidence")
    offered = next(b for b in t.batches if any(e["content"] == NICK for e in b["evidence"]))
    position = {k: offered[k] for k in ("stream_id", "policy_version", "after", "through", "lane") if k in offered}
    assert all(stopped["position"].get(k) == v for k, v in position.items()) and "quote" in stopped["detail"]

    # 重启后观察 Memory 再次签发此位置。多次轮询期间均不能有第三次调用或未纠正的称呼提交。
    t.stop_harness()
    start = len(t.batches)
    t.start_dreaming(gemini=True)
    t.wait_for(lambda: sum(all(b.get(k) == v for k, v in position.items()) and b["evidence"] == offered["evidence"]
                           for b in t.batches[start:]) >= 2, "重启后仍保留原批位置和证据")
    assert len([m for m in t.model_calls if m["note"] == NICK]) == 2
    assert not any(all(r["command"].get(k) == v for k, v in position.items()) for r in t.commits)
    assert not t.failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    parser.set_defaults(live=False)
    trial = IdentityModels(parser.parse_args())
    outcome = "FAIL"
    try:
        run(trial)
        outcome = "PASS"
    finally:
        trial.close()
        trial.report(outcome)
