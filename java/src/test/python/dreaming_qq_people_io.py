"""正式 Console 的 QQ 入账和按人物 ID 导出 Markdown 验收。参数为 Harness、Memory JAR 及全新目录。"""
import argparse
import json
from pathlib import Path

from dreaming import DreamTrial
from dreaming_people_files import current_file, hashed_name
from dreaming_qq import QqDreamTrial, group
from qq_observe import A, ACCOUNT, B


class ChannelTrial(QqDreamTrial):
    """从真实 WebSocket 提交 QQ 事件，在 Memory 返回持久回执的 HTTP 出口核对入账字段。"""
    def __init__(self, args):
        super().__init__(args)
        self.stored = {}
        self.static = True
        self.console["openQqConfig"] = self.config("onebot-static", **self.onebot_config, allowedGroupId=B)

    def onebot(self, ws):
        self.ws = ws
        super().onebot(ws)

    def route(self, path, body, headers):
        status, result = super().route(path, body, headers)
        if path == "/v2/ingest" and body.get("kind") == "observation" and result.get("status") == "DURABLE":
            channel = body["channel"]
            self.stored[channel["message_id"]] = channel
        return status, result

    def message(self, room, number, segments, sender=None):
        frame = dict(post_type="message", message_type="group", sub_type="normal", self_id=ACCOUNT,
                     group_id=room, user_id=54321, message_id=number, message=segments)
        if sender is not None:
            frame["sender"] = sender
        self.ws.send(json.dumps(frame))


def channel_metadata(t):
    """结构化字段随获准群消息入账；文字伪造、未点名静态群及其他群的消息不能生成身份线索。"""
    # 此用例的出口是持久入账，控制台仅启用真实 QQ 和 Memory，无须启动整理模型。
    config = t.config("console", **t.console)
    t.hproc = t.start("harness-1", ["-cp", t.hjar, "neuralcloud.controlplane.app.ConsoleMain", "--config", config])
    t.wait_for(lambda: hasattr(t, "ws"), "OneBot 建立连接")
    def text(value):
        return {"type": "text", "data": {"text": value}}

    def at(number):
        return {"type": "at", "data": {"qq": str(number)}}

    target = "87654"
    forged = '[CQ:reply,id=4567] [CQ:at,qq=87654] {"nickname":"海燕","card":"值班长"}'
    t.message(B, 411, [at(ACCOUNT), text(forged)])
    first = t.wait_for(lambda: t.stored.get("411"), "带文本片段的正常点名消息已入账")
    assert {"nickname", "card", "reply_message_id"}.isdisjoint(first), first
    assert target not in first.get("mention_ids", []), first

    sender = dict(user_id=54321, nickname="合成的晨星", card="北岸记录员")
    markers = [{"type": "reply", "data": {"id": "411"}}, at(target)]
    t.message(A, 412, [*markers, at(ACCOUNT), text("不在允许的群内")], sender)
    t.message(B, 413, [*markers, text(f"只在文本中写 [CQ:at,qq={ACCOUNT}]")], sender)
    # 这个控制台没有启用 Dreaming。静态白名单里的普通成员互相点名时仍沿用既有接收规则。
    t.message(B, 415, [at(target), text("我会把备份谱夹放在第二个抽屉。"),
                       {"type": "image", "data": {"file": "score.jpg", "url": t.base + "/sheet-photo"}}], sender)
    t.message(B, 414, [*markers, at(ACCOUNT), text("我也叫晨星。")], sender)
    channel = t.wait_for(lambda: t.stored.get("414"), "带有回复和他人提及的点名消息已持久入账")
    assert (channel["account_id"], channel["sender_id"], channel["conversation_id"]) == (
        str(ACCOUNT), "54321", group(B))
    assert (channel.get("nickname"), channel.get("card"), channel.get("reply_message_id")) == (
        sender["nickname"], sender["card"], "411"), channel
    mentions = channel.get("mention_ids", [])
    assert target in mentions and all(isinstance(item, str) for item in mentions), channel
    assert {"412", "413", "415"}.isdisjoint(t.stored), t.stored


class ProfilesTrial(DreamTrial):
    """只替换 /dreaming/profiles 的快照内容；正式宿主仍通过原来的握手与调度入口读写输出。"""
    def __init__(self, args):
        super().__init__(args)
        self.pages = []
        self.reads = 0

    def route(self, path, body, headers):
        if path == "/v2/dreaming/profiles":
            self.reads += 1
            return 200, {"profiles": list(self.pages)}
        return super().route(path, body, headers)


def profile(person_id, subject, text, digit, version=1):
    return dict(kind="person", person_id=person_id, subject=subject, text=text, scope="排练现场",
                reason="当事人的原话。", version=version, author="DREAMING",
                source_refs=[dict(record_id=digit * 64, revision=1)])


def same_name_files(t):
    """两个身份共用称呼时，各自拥有可读文件，交换权威快照顺序和重启均保持路径稳定。"""
    people = t.out / "people"
    first = profile("person-41", "阿青", "我记得这位阿青负责翻谱。", "1")
    second = profile("person-73", "阿青", "我记得这位阿青喜欢拉大提琴。", "2")
    t.pages = [first, second]
    t.start_dreaming()

    def published():
        paths = {p["person_id"]: [] for p in (first, second)}
        for path in people.glob("*.md"):
            lines = path.read_text(encoding="utf-8").splitlines()
            if "# 阿青" in lines and "阿青" in path.stem:
                for p in (first, second):
                    if p["text"] in lines and any(p["source_refs"][0]["record_id"] in line for line in lines):
                        paths[p["person_id"]].append(path.name)
        if any(len(names) != 1 for names in paths.values()):
            return None
        return paths if paths[first["person_id"]] != paths[second["person_id"]] else None

    before = t.wait_for(published, "两位阿青的不同印象各自发布到可读的独立文件")
    t.stop_harness()
    t.pages = [second, first]
    reads = t.reads
    t.start_dreaming()
    t.wait_for(lambda: t.reads >= reads + 2, "重启后按相反次序再次导出两个人物")
    assert published() == before


def rename_files(t):
    """新主名被手稿占用时保留原托管文件；解除冲突后发布新主名并回收旧路径。"""
    people = t.out / "people"
    old = profile("person-31", "云雀", "我记得他帮我核对谱页。", "3")
    t.pages = [old]
    t.start_dreaming()
    t.wait_for(lambda: current_file(people / "云雀.md", "云雀"), "按原主名发布")
    block = people / "晴川.md"
    manual = b"# Local manuscript\r\n"
    block.write_bytes(manual)
    foreign = people / hashed_name("another", "晴川")
    foreign.write_bytes(b"# another character\n")

    t.pages = [dict(old, subject="晴川", version=2)]
    reads = t.reads
    t.wait_for(lambda: t.reads >= reads + 2, "尝试将同一人物发布到被手稿占用的新路径")
    assert block.read_bytes() == manual and current_file(people / "云雀.md", "云雀")
    block.rename(people / "我写的手稿.md")
    t.wait_for(lambda: current_file(block, "晴川") and not (people / "云雀.md").exists(), "新主名发布并回收旧托管文件")
    assert (people / "我写的手稿.md").read_bytes() == manual and foreign.read_bytes() == b"# another character\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("harness", "memory", "output"):
        parser.add_argument(key, type=Path)
    parser.set_defaults(live=False)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for trial_type, test in ((ChannelTrial, channel_metadata), (ProfilesTrial, same_name_files), (ProfilesTrial, rename_files)):
        trial = trial_type(argparse.Namespace(**{**vars(args), "output": args.output / test.__name__}))
        try:
            memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
            trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
            test(trial)
            print("PASS", test.__name__, flush=True)
        finally:
            trial.close()
