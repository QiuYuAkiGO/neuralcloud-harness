"""正式 ConsoleMain 与同时提供 Dreaming 和会话共享的 Memory v2 启动握手验收。"""
import argparse
from pathlib import Path

from dreaming import DreamTrial
from qq_observe import call


class HandshakeTrial(DreamTrial):
    """正例透传真实健康响应。反例只撤去 dreaming，使用相同的 Memory 库和控制台配置。"""
    def __init__(self, args):
        super().__init__(args)
        self.withhold = False
        self.announcements = []

    def route(self, path, body, headers):
        status, result = super().route(path, body, headers)
        if path == "/v2/health" and status == 200:
            if self.withhold:
                result = dict(result, capabilities=[c for c in result["capabilities"] if c != "dreaming"])
            self.announcements.append(result)
        return status, result


def run(t):
    memory = t.start("memory", ["-jar", t.mjar, "--config", t.mc])
    t.ready(lambda: t.mem("health")["status"] == "TEXT_READY", memory)
    t.start_dreaming()
    assert t.announcements, "宿主必须经过 Memory v2 握手"
    health = t.announcements[-1]
    assert health.get("session_isolation") is False and {"dreaming", "identity_lookup"} <= set(health["capabilities"]), (
        "这对用例需要共享模式、支持身份目录与 Dreaming 的 Memory v2 JAR", health)
    t.ready(lambda: call(t.hp, "/api/nodes")[0] == 200, t.hproc)
    t.stop_harness()

    # 空库正例已经从正式入口领取了首批并开放控制台。缺少所选功能的能力时，启动应在握手边界退出。
    seen, batches = len(t.announcements), len(t.batches)
    t.withhold = True
    t.launch(t.settings(), t.console)
    assert t.hproc.wait(timeout=15) == 1, "缺少 dreaming 的对端应导致启动失败"
    assert len(t.announcements) > seen and len(t.batches) == batches
    denied = t.announcements[-1]
    assert denied["session_isolation"] is False and set(denied["capabilities"]) == set(health["capabilities"]) - {"dreaming"}
    assert not t.failures, t.failures
    print("PASS 共享 Memory 已就绪并接受首批取证；仅撤去 dreaming 时宿主在启动阶段拒绝。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("harness", "memory", "output"):
        parser.add_argument(name, type=Path)
    parser.set_defaults(live=False)
    trial = HandshakeTrial(parser.parse_args())
    try:
        run(trial)
    finally:
        trial.close()
