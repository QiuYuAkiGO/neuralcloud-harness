"""从 Console、模型和 Memory HTTP 边界验收群历史修订同人档案时的来源承接。"""
import argparse
import json
from pathlib import Path

from dreaming import GEMINI_PATH, OPENAI_PATH, ref, tool_call
from dreaming_qq import A, QqDreamTrial, group


def material(digit, revision):
    return {"record_id": digit * 64, "revision": revision}


PEOPLE = [dict(person_id=d * 64, identity_version=1, subject="阿禾", aliases=[]) for d in ("a", "b")]
OLD = [material("1", 2), material("2", 1)]
NEW = [material("3", 1), material("4", 1)]
PRIVATE_QUOTE = "这句合成私聊原文不随群历史交付。"


class HistorySources(QqDreamTrial):
    def route(self, path, body, headers):
        if path == "/v2/dreaming/batch":
            status, page = super().route(path, body, headers)
            if status == 200 and group(A) in body["scopes"] and not self.commits:
                # 同名两人各有一份旧档，也各有一条本页群原话。旧来源只有精确引用和档案认识。
                evidence = [dict(src, conversation_id=group(A), kind="observation", occurred_at="2026-01-01T12:00:00Z",
                              speaker_person_id=p["person_id"], content=note)
                            for src, p, note in zip(NEW, PEOPLE, ("我改用蓝色谱夹。", "我改用绿色谱夹。"))]
                profiles = [dict(kind="person", subject=p["subject"], person_id=p["person_id"], version=3, author="DREAMING",
                               text="我记得阿禾以前的谱夹。", scope="选用谱夹", reason="过往认识。", source_refs=[src])
                            for p, src in zip(PEOPLE, OLD)]
                return status, dict(page, after=0, through=1, lane="group_history", evidence=evidence,
                                    profiles=profiles, persons=PEOPLE)
            return status, page
        if path == "/v2/dreaming/commit":
            assert self.model_calls[-1]["case"] == "同人承接", ("非法提案被送达 Memory", body)
            assert body["lane"] == "group_history" and body["changes"] == self.model_calls[-1]["result"]["changes"]
            receipt = dict(status="DURABLE", **{k: body[k] for k in ("stream_id","policy_version","after","through","lane","event_id")})
            self.commits.append(dict(command=body, status=200, response=receipt))
            return 200, receipt
        return super().route(path, body, headers)

    def model_reply(self, path, body, headers):
        if path == OPENAI_PATH:
            user, = [m["content"] for m in body["messages"] if m["role"] == "user"]
        else:
            assert path == GEMINI_PATH
            user = body["contents"][0]["parts"][0]["text"]
        packet = json.loads(user)
        assert [ref(e) for e in packet["evidence"]] == NEW and PRIVATE_QUOTE not in user
        assert [p["source_refs"] for p in packet["profiles"]] == [[OLD[0]], [OLD[1]]]
        choices = [("只有旧引用", [OLD[0]]), ("借同名人旧引用", [NEW[0], OLD[1]]),
                   ("改写旧修订", [NEW[0], dict(OLD[0], revision=1)]), ("用旧引用报称呼", [NEW[0]]),
                   ("同人承接", [NEW[0], OLD[0]])]
        assert len(self.model_calls) < len(choices), "宿主未接受合法的同人旧档引用"
        label, sources = choices[len(self.model_calls)]
        change = dict(kind="person", subject="阿禾", person_id=PEOPLE[0]["person_id"], expected_version=3,
                      text="我记得阿禾原来用旧谱夹，现在改用蓝色。", scope="练习时的用具", reason="群友刚说了新选择。",
                      source_refs=sources)
        claims = []
        if label == "用旧引用报称呼":
            claims = [dict(person_id=PEOPLE[0]["person_id"], expected_identity_version=1, name="青笺", relation="SELF_NAME",
                           scope="global", source_refs=[dict(OLD[0], quote="我也叫青笺。")])]
        result = dict(changes=[change], name_claims=claims)
        self.model_calls.append(dict(case=label, lane=packet.get("lane"), result=result))
        if path == OPENAI_PATH:
            return 200, tool_call("submit_dreaming", result)
        return 200, {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":json.dumps(result)}]}}]}


def run(t, gemini):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.start_dreaming(gemini=gemini)
    t.wait_for(lambda: t.commits, "仅同人旧引用加本页原话的提案取得回执")
    assert len(t.commits) == 1 and all(call["lane"] == "group_history" for call in t.model_calls)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    parser.set_defaults(live=False)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for protocol in ("OPENAI_CHAT", "GEMINI"):
        trial = HistorySources(argparse.Namespace(**{**vars(args), "output": args.output / protocol}))
        try:
            run(trial, protocol == "GEMINI")
            print("PASS", protocol, flush=True)
        finally:
            trial.close()
