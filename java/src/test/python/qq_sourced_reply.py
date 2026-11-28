"""用真实 QQ 私聊入口、Memory v2 授权与回环模型验收有来源的已送达回复。"""
import argparse
import json
import os
import secrets
import unittest
from pathlib import Path
from unittest.mock import patch

from dialogue_replay import GREET, QQ, SOURCE_INSTANCE, ReplayTrial, ref
from qq_observe import A, ACCOUNT, call, context, until


AUTHOR, PEER = 54321, 67890
PRIVATE = f"c2c:onebot:{ACCOUNT}:{PEER}"
TOPIC = "青藤换票"
LOCAL = "青藤换票要带浅蓝色的票根。"
SHARED = "青藤换票的接待处有一枚紫杉印章。"
QUESTION = "青藤换票要带什么，接待处有什么标记？"
OLD = "票根和紫杉印章我刚才记反了，怕给你添麻烦。"
COMFORT = "没关系，你已经帮上忙了，别担心。"
THANKS = "听你这么说，我就放心了。"
QUOTE = "我刚才说过：" + OLD
SAFE = "嗯，谢谢你。"


def private(peer):
    scope = f"c2c:onebot:{ACCOUNT}:{peer}"
    return dict(character_id="trial", session_id=scope, conversation_id=scope,
                role="ORCHESTRATOR", actor_id="ORCHESTRATOR", qq=dict(account_id=str(ACCOUNT), peer_id=str(peer)))


def previous_answer(messages):
    return any(row["role"] == "assistant" and OLD in row["content"] for row in messages)


class SourcedTrial(ReplayTrial):
    """模型只在真实 assistant 历史里认得自己说过的话。每次 QQ 发送沿用基座的独立 OneBot 回执。"""

    mode = "lookup"

    def route(self, path, body):
        status, reply = super().route(path, body)
        if path != "/v1/chat/completions" or body.get("tools"):
            return status, reply
        messages = body["messages"]
        if self.mode == "lookup":
            material = json.dumps(messages, ensure_ascii=False)
            answer = OLD if LOCAL in material and SHARED in material else "还缺换票的资料。"
        elif self.mode == "comfort":
            answer = THANKS if previous_answer(messages) else OLD
        else:
            answer = QUOTE if previous_answer(messages) else SAFE
        self.checkpoint("sourced-draft")
        reply["choices"][0]["message"]["content"] = answer
        return status, reply

    def authorize(self, path, key, **fields):
        """用合成群发言者的私聊身份和独立渠道凭据授予或收回指定版本的 CITE 权限。"""
        body = dict(context=private(AUTHOR), source_instance=SOURCE_INSTANCE, event_id=key, reason="合成换票验收", **fields)
        status, receipt = call(self.mp, "/v2/" + path, body, token=self.grant_token)
        assert status == 200 and receipt["status"] == "DURABLE", (status, receipt)
        return receipt

    def first_reply(self):
        """本私聊的一条原文与获准引用的一条群原文共同支撑旧答，取得真实送达和持久回执。"""
        common = dict(source_instance=SOURCE_INSTANCE, kind="observation", occurred_at="2026-01-01T11:00:00Z")
        local = self.mem("ingest", dict(common, context=private(PEER), event_id="private-ticket", content=LOCAL))
        group = context(A)
        shared = self.mem("ingest", dict(common, context=group, event_id="group-ticket", content=SHARED,
                          speaker=str(AUTHOR), channel=dict(platform="qq", type="qq_group", account_id=str(ACCOUNT),
                          sender_id=str(AUTHOR), conversation_id=group["conversation_id"], message_id="701")))
        shared_ref = {key: shared[key] for key in ("record_id", "revision")}
        grant = self.authorize("visibility-grants", "allow-shared", target_conversation_id=PRIVATE,
                               use="CITE", source_ref=shared_ref)
        self.harness()
        rows, history = self.turn(QQ, QUESTION, OLD, TOPIC)
        delivery, = [row for row in rows if row["type"] == "qq_delivery"]
        assert shared_ref in delivery["data"]["source_refs"], delivery
        facts = {(b["item"]["ref"]["record_id"], b["item"]["ref"]["revision"])
                 for row in rows if row["type"] == "memory_recall" for b in row["data"]["bindings"]}
        assert {(item["record_id"], item["revision"]) for item in (local, shared)} <= facts, facts
        assert ref(rows, "SELF_EXPRESSION")["revision"] == 1
        return history, grant["grant_id"]

    def follow_up(self, text):
        """发送独立的私聊入站，与正在模型屏障处等候的事件用 OneBot 消息号区分。"""
        self.inputs.clear()
        self.query = ""
        self.number += 1
        self.ws.send(json.dumps(dict(post_type="message", message_type="private", sub_type="friend", self_id=ACCOUNT,
                          user_id=PEER, message_id=self.number, message=[dict(type="text", data=dict(text=text))])))

    def finished_history(self):
        rows = self.audit(QQ)
        start = next((i for i, row in enumerate(rows) if row["type"] == "qq_message"
                      and row["data"]["messageId"] == self.number), None)
        if start is not None and any(row["type"] in {"qq_delivery", "node_failed", "qq_delivery_failed"}
                                     for row in rows[start:]):
            return rows


class SourcedReplyTest(unittest.TestCase):
    def open_trial(self, label):
        args = argparse.Namespace(harness=ARGS.harness, memory=ARGS.memory, output=ARGS.output / label)
        trial = SourcedTrial(args)
        self.addCleanup(trial.close)
        trial.grant_token = secrets.token_urlsafe(36)
        with trial.mc.open("a", encoding="utf-8") as config:
            config.write(f"sessionIsolation=true\ngrantAccountId={ACCOUNT}\ngrantAccessTokenEnv=TRIAL_SOURCED_GRANT\n")
        with patch.dict(os.environ, {"TRIAL_SOURCED_GRANT": trial.grant_token}):
            trial.start_memory("memory")
        return trial

    def test_authorized_assistant_can_receive_comfort(self):
        """正例。实收自述有本私聊及获准的外群来源，下一轮用原角色接住安抚。"""
        t = self.open_trial("authorized")
        initial, _ = t.first_reply()
        t.mode = "comfort"
        t.follow_up(COMFORT)
        history = until(t.finished_history, "安抚回复完成")
        self.assertEqual(history[:len(initial)], initial)
        plans = [messages for names, messages in t.inputs if names == ["plan_turn"]]
        drafts = [messages for names, messages in t.inputs if not names]
        self.assertTrue(plans and drafts)
        for messages in [*plans, *drafts]:
            self.assertTrue(previous_answer(messages), "上一轮实际送达的回复应作为 assistant 出现")
        self.assertEqual([cmd["params"]["message"][0]["data"]["text"] for cmd in t.sent], [OLD, THANKS])

    def test_withdrawing_a_citation_blocks_future_input_and_delivery(self):
        """反例。在规划中或生成中撤回外群授权，检查下一次模型调用、OneBot 发送以及下一轮回放。"""
        for boundary in ("replay-plan", "sourced-draft"):
            with self.subTest(boundary=boundary):
                t = self.open_trial(boundary)
                initial, grant = t.first_reply()
                t.mode = "quote"
                sent = len(t.sent)
                gate = t.arm(boundary)
                try:
                    # 使用纯问候，让本轮受众约束只来自回放，避免新查证独立拦截同一份来源。
                    t.follow_up(GREET)
                    gate.wait()
                    received_before_revoke = previous_answer(t.inputs[-1][1])
                    consumed = len(t.inputs)
                    t.authorize(f"visibility-grants/{grant}/revoke", "withdraw-shared")
                finally:
                    gate.release.set()
                until(t.finished_history, "受影响私聊结束")
                following = list(t.inputs[consumed:])
                t.follow_up(GREET)
                history = until(t.finished_history, "撤权后的下一轮结束")
                self.assertEqual(history[:len(initial)], initial)
                self.assertEqual(t.sent[-1]["params"]["message"][0]["data"]["text"], SAFE)
                for _, messages in [*following, *t.inputs]:
                    packet = json.dumps(messages, ensure_ascii=False)
                    self.assertNotIn(OLD, packet)
                    self.assertNotIn(SHARED, packet)
                for command in t.sent[sent:]:
                    packet = json.dumps(command["params"]["message"], ensure_ascii=False)
                    self.assertNotIn(OLD, packet)
                    self.assertNotIn(SHARED, packet)
                self.assertTrue(received_before_revoke, "撤权前的模型应已收到这条有效自述")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__])
