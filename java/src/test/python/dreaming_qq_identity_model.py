"""真实 ConsoleMain 与 Memory 验收 QQ 称呼提案，推理按两个正式协议分别走回环挡板。"""
import argparse
import json
from pathlib import Path

from dreaming import GEMINI_PATH, OPENAI_PATH, ref, tool_call
from dreaming_qq import A, QqDreamTrial


INTRO = "我叫栖湾。"
HOBBY = "我也叫湾湾。我周末喜欢上山拍照。"


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
        note = INTRO if path == OPENAI_PATH else HOBBY
        evidence, = [e for e in packet["evidence"] if note in e["content"]]
        pid = evidence["speaker_person_id"]
        person, = [p for p in packet["persons"] if p["person_id"] == pid]
        assert isinstance(person["aliases"], list)
        attempt = len(self.model_calls)
        claim = dict(person_id=pid, expected_identity_version=person["identity_version"],
                     name="栖湾" if note == INTRO else "湾湾", relation="SELF_NAME", scope="global",
                     source_refs=[dict(ref(evidence), quote=note.split("。", 1)[0] + "。")])
        changes = []
        if note == INTRO and attempt == 0:
            # 同格式的另一标识从未签发给模型。
            claim["person_id"] = pid[:-1] + ("0" if pid[-1] != "0" else "1")
            assert all(p["person_id"] != claim["person_id"] for p in packet["persons"])
        elif note == INTRO and attempt == 1:
            claim["source_refs"][0]["quote"] = "我已经连续三年使用栖湾这个名字。"
        elif note == HOBBY:
            assert person["subject"] == "栖湾", person
            changes = [dict(kind="person", subject="栖湾", person_id=pid, expected_version=0,
                            text="我记得栖湾周末喜欢上山拍照。", scope="群里的自述", reason="本人说明周末爱好。",
                            source_refs=[ref(evidence)])]
        result = dict(changes=changes, name_claims=[claim])
        self.model_calls.append(dict(path=path, input=packet, person=person, proposal=result))
        if path == OPENAI_PATH:
            return 200, tool_call("submit_dreaming", result)
        return 200, {"candidates": [{"finishReason": "STOP", "content": {"role": "model", "parts": [
            {"text": json.dumps(result, ensure_ascii=False)}]}}]}


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
    assert len(attempts) == 3 and all(m["input"] == attempts[0]["input"] for m in attempts)
    assert only["command"]["name_claims"] == attempts[2]["proposal"]["name_claims"]
    for invalid in attempts[:2]:
        assert all(r["command"].get("name_claims") != invalid["proposal"]["name_claims"] for r in t.commits), (
            "未签发的人物或伪造原句不应送达 Memory", invalid["proposal"])
    assert t.profiles() == [], "只登记称呼不应生成没有认识的人物档案"

    # 同一账号再发一条原话。另一协议要看到第一次登记结果，并可同批登记别称和归属人物认识。
    t.stop_harness()
    t.start_dreaming(gemini=True)
    t.wait_for(lambda: t.panel()["connected"], "重启后群接收就绪")
    t.emit(A, 762, HOBBY, at=False)
    combined = t.wait_for(lambda: submitted("湾湾"), "原生 Gemini 同批登记新别称和既有人物认识")
    latest = next(m for m in t.model_calls if m["path"] == GEMINI_PATH)
    first = attempts[2]["person"]
    assert latest["person"]["person_id"] == first["person_id"]
    assert latest["person"]["identity_version"] > first["identity_version"]
    assert combined["response"]["status"] == "DURABLE"
    assert all(combined["command"][k] == latest["proposal"][k] for k in ("name_claims", "changes"))
    profile, = t.profiles()
    assert (profile["subject"], profile["text"]) == ("栖湾", latest["proposal"]["changes"][0]["text"])
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
