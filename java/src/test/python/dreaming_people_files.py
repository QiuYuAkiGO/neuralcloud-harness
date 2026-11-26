"""用正式 Memory 和控制台验收 people 文件的命名、升级清理及写入边界。"""
import argparse
import hashlib
from pathlib import Path

from dreaming import DreamTrial, SCOPE, context, ref


def hashed_name(character, subject):
    """旧导出契约使用名称 UTF-8 字节的 SHA-256；主题继续沿用这个路径。"""
    digest = hashlib.sha256(subject.encode("utf-8")).hexdigest()
    return f"dream-{character}-{digest}.md"


def publish(t, event, entries):
    """通过 Memory 的入账、取证和提交入口准备一组已经生效的权威档案。"""
    names = "、".join(subject for _, subject in entries)
    source = t.ingest(event, f"这次排练登记了以下名称：{names}。", "记录员")
    scope = "dreaming:trial"
    batch = t.mem("dreaming/batch", dict(context=context(scope), scopes=[SCOPE], limit=16))
    changes = [dict(kind=kind, subject=subject, expected_version=0, text=f"我记得登记了{subject}。",
                    scope="北岸排练", reason="记录员核对了登记者的称呼。", source_refs=[ref(source)])
               for kind, subject in entries]
    position = {key: batch[key] for key in ("stream_id", "policy_version", "after", "through")}
    receipt = t.mem("dreaming/commit", dict(context=context(scope, "ROUTER"), source_instance="neuralcloud-java",
                                           event_id="publish-" + event, changes=changes, **position))
    assert receipt["status"] == "DURABLE"
    return source


def current_file(path, subject):
    return path.is_file() and f"# {subject}" in path.read_text(encoding="utf-8").splitlines()


def named_files(t):
    """正例从旧版导出升级，随后在停机期间撤回其中一份档案的唯一依据。"""
    withdrawn = publish(t, "person", [("person", "林澄")])
    publish(t, "staff-and-topic", [("person", "Mira"), ("topic", "青砾星图")])
    people = t.out / "people"
    people.mkdir(parents=True)
    topic = t.out / "topics" / hashed_name("trial", "青砾星图")
    legacy = people / hashed_name("trial", "林澄")
    legacy.write_text(f"# 林澄\n\n我记得登记了林澄。\n\n## 适用范围\n\n北岸排练\n\n## 修订理由\n\n"
                      f"记录员核对了登记者的称呼。\n\n## 一手依据\n\n- {withdrawn['record_id']} · 修订 1\n\n"
                      "DREAMING · 档案版本 1\n", encoding="utf-8")
    protected = {people / "轮值手记.md": b"# manual\n", people / hashed_name("other", "林澄"): b"# other\n"}
    for path, content in protected.items():
        path.write_bytes(content)

    t.start_dreaming()
    t.wait_for(lambda: current_file(people / "林澄.md", "林澄") and current_file(people / "Mira.md", "Mira")
               and current_file(topic, "青砾星图") and not legacy.exists(), "主要称呼和主题发布并完成旧文件迁移")
    t.stop_harness()
    t.correct(withdrawn)
    t.start_dreaming()
    t.wait_for(lambda: not (people / "林澄.md").exists(), "重启后收回已撤回的人物文件")
    assert current_file(people / "Mira.md", "Mira") and current_file(topic, "青砾星图")
    for path, content in protected.items():
        assert path.read_bytes() == content, path


def name_boundaries(t):
    """反例包含会形成子路径、姓名碰撞、超长分量及与手工文稿同名的档案。"""
    long_stem = "北" * 90
    subjects = ["南/北:夏%", "../越界", "Nova", "nova", "Éli", "E\u0301li", long_stem + "甲", long_stem + "乙"]
    publish(t, "edge", [("person", subject) for subject in subjects])
    people = t.out / "people"
    t.start_dreaming()

    def all_distinct():
        # 只数 people 直属目录的文件。按正文标题反查每个名称，允许宿主为碰撞选择区分后缀。
        docs = {p.name: p.read_text(encoding="utf-8").splitlines() for p in people.glob("*.md")}
        paths = []
        for subject in subjects:
            matches = [name for name, lines in docs.items() if f"# {subject}" in lines]
            if len(matches) != 1:
                return False
            if subject.startswith(long_stem) and "北" not in matches[0]:
                return False
            paths.extend(matches)
        return len(set(paths)) == len(subjects)

    t.wait_for(all_distinct, "斜杠、大小写、Unicode 等价字形与两个超长称呼各有独立人物文件")
    assert current_file(people / "南%2F北%3A夏%25.md", "南/北:夏%")
    assert not (t.out / "越界.md").exists()
    t.stop_harness()

    # 通过新一份真实档案触发文件名冲突。跨过一次完整的后台周期，核对原稿字节，再移走原稿重试。
    manual = people / "夏竹.md"
    original = b"Personal manuscript\r\n"
    manual.write_bytes(original)
    publish(t, "manual-conflict", [("person", "夏竹")])
    previous = len(t.batches)
    t.start_dreaming()
    t.wait_for(lambda: len(t.batches) >= previous + 2, "宿主尝试写入与手稿同名的人物档")
    assert manual.read_bytes() == original
    manual.rename(people / "手稿原件.md")
    t.wait_for(lambda: current_file(manual, "夏竹"), "解除冲突后按主要称呼发布")
    assert (people / "手稿原件.md").read_bytes() == original


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    parser.set_defaults(live=False)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for test in (named_files, name_boundaries):
        trial = DreamTrial(argparse.Namespace(**{**vars(args), "output": args.output / test.__name__}))
        try:
            memory = trial.start("memory", ["-jar", trial.mjar, "--config", trial.mc])
            trial.ready(lambda: trial.mem("health")["status"] == "TEXT_READY", memory)
            test(trial)
            print("PASS", test.__name__, flush=True)
        finally:
            trial.close()
