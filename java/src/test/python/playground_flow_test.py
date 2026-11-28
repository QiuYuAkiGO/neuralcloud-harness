"""对已配置的隔离 Playground 执行 HTTP 黑盒验收。仅使用合成的预选案例与公开接口。

准备两个名称各有唯一标记的配置，以及仅观察群的点名消息、允许回复群的点名消息、
有历史原答的私聊三个案例。标记应仅出现在配置名称和原答中。--speaker 是私聊对方 QQ 号。
运行 python java/src/test/python/playground_flow_test.py --help 查看参数。

网页人工正反验收：
1. 搜索群、私聊及时间，点击某条用户发言。截点线前后位置应正确；未核实点名信息的群消息应提示缺失。
   另建点名变体后切回原案例，应仍能看到原来源。用两人正在对谈、向全群征求意见各测一次主动参与。
2. A/B 并排显示回复或跳过，点开实际输入和参与依据；造一次模型连接失败，页面应显示失败原因。
   在盲评前查找配置名和历史原答均应无结果。两侧评完后应能看到两者及原始评分和改判理由。
3. 修改一份提示词并保存新版本。旧批次应能用旧配置重跑，沿 A 侧续聊只应在该分支留下新发言。
4. 在固定批次报告点击误插话、漏接话和待复核数，应进入对应的案例和证据；均可标签应保留其原意。
   将同一原始会话的不同截点分别加入调试集与留出集，应有可观察的拒绝原因。
"""

import argparse
import json
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, build_opener, ProxyHandler


class Client:
    """从操作员的同一入口运行各用例，错误信息只含接口状态。"""

    def __init__(self, url, timeout):
        self.url = url.rstrip("/")
        self.timeout = timeout
        self.http = build_opener(ProxyHandler({}))

    def call(self, path, body=None):
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        request = Request(self.url + path, data=data, headers={"Content-Type": "application/json"})
        with self.http.open(request, timeout=self.timeout) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def finished(self, run_id):
        """只有 complete 表示完成；等待期间允许服务返回仍在运行的状态。"""
        deadline = time.monotonic() + self.timeout
        path = "/runs/" + quote(run_id, safe="")
        while time.monotonic() < deadline:
            run = self.call(path)
            assert run["id"] == run_id, "返回了其他运行"
            if run["status"] == "complete":
                return run
            if run["status"] == "failed":
                raise AssertionError(f"运行 {run_id} 失败，请在报告中查看逐侧原因")
            time.sleep(0.4)
        raise TimeoutError(f"运行 {run_id} 超时")


def expect_invalid(client, path, body):
    """仅字段校验或状态冲突视为正确拒绝；不存在入口和服务崩溃都不能通过。"""
    try:
        client.call(path, body)
    except HTTPError as error:
        if error.code not in (400, 409, 422):
            raise
        error.close()
        return
    raise AssertionError(f"{path} 接受了非法输入")


def check_lanes(run, sides, decision):
    """跳过必须显式报告，生成路径必须交出非空正文。"""
    lanes = run["lanes"]
    assert sorted(item["side"] for item in lanes) == sorted(sides), "运行侧别不符"
    for item in lanes:
        assert item["decision"] == decision, f"{item['side']} 侧参与决定不符"
        if decision == "SKIP":
            assert item["reply"] is None, "跳过仍返回回复"
        else:
            assert isinstance(item["reply"], str) and item["reply"].strip(), "回复正文为空"


def visible(value, marker):
    """遍历公开结果的字符串值，同时覆盖实际输入、审计详情和揭晓数据。"""
    if isinstance(value, dict):
        return any(visible(item, marker) for item in value.values())
    if isinstance(value, list):
        return any(visible(item, marker) for item in value)
    return isinstance(value, str) and marker in value


def verify(args):
    client = Client(args.url, args.timeout)
    configs = [args.config_a, args.config_b]
    state = client.call("/state")
    for key in ("configs", "cases"):
        assert isinstance(state[key], list), f"缺少 {key} 列表"
    expect_invalid(client, "/runs", {"caseId": args.private_case, "configs": configs[:1]})

    # 同样的点名条件在两种群策略下有相反结果；私聊也须经过真实回复流程。
    def run_case(case):
        created = client.call("/runs", {"caseId": case, "configs": configs})
        return client.finished(created["id"])

    check_lanes(run_case(args.observe_case), "AB", "SKIP")
    check_lanes(run_case(args.addressed_case), "AB", "REPLY")
    parent = run_case(args.private_case)
    check_lanes(parent, "AB", "REPLY")
    path = "/runs/" + quote(parent["id"], safe="")
    hidden = [args.name_a, args.name_b, args.reference]
    assert all(not visible(parent, marker) for marker in hidden), "双盲运行提前揭晓"
    other_peer = "11001" if args.speaker != "11001" else "11002"
    expect_invalid(client, path + "/turns", {
        "side": "A", "speaker": other_peer, "text": "换人后的私聊", "addressed": True,
    })

    # 两侧各续一轮，以实际输入的唯一标记辨认隔离情况，无须模型照抄特定输出。
    tags = {side: "续聊验收-" + uuid.uuid4().hex for side in "AB"}
    child_ids = {parent["id"]}
    for side, other in (("A", "B"), ("B", "A")):
        body = {"side": side, "speaker": args.speaker, "text": tags[side], "addressed": True}
        created = client.call(path + "/turns", body)
        assert created["id"] not in child_ids, "父子或两条分支共用运行"
        child_ids.add(created["id"])
        child = client.finished(created["id"])
        check_lanes(child, side, "REPLY")
        assert visible(child, tags[side]), "续聊实际输入中缺少本侧标记"
        assert not visible(child, tags[other]), "另一分支的私聊标记进入本次运行"
    parent_again = client.call(path)
    assert all(not visible(parent_again, tag) for tag in tags.values()), "续聊修改了原运行"

    # 第一次复核后全局盲态继续；第二侧提交才允许两个名称与历史参考同时出现。
    review = {"side": "A", "persona": "PASS", "reasonable": "PASS", "reason": "已核对测试对话中的具体发言"}
    expect_invalid(client, path + "/review", {**review, "persona": "UNKNOWN"})
    client.call(path + "/review", review)
    one_review = client.call(path)
    assert all(not visible(one_review, marker) for marker in hidden), "仅评一侧就揭晓"
    client.call(path + "/review", {**review, "side": "B"})
    revealed = client.call(path)
    assert all(visible(revealed, marker) for marker in hidden), "双侧评价后的对照资料缺失"
    print("通过群策略、私聊、续聊隔离、双盲揭晓与非法请求验收。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", required=True, help="控制台同源入口，含 /api/playground")
    parser.add_argument("--observe-case", required=True, help="仅观察群的点名案例 ID")
    parser.add_argument("--addressed-case", required=True, help="观察与回复群的点名案例 ID")
    parser.add_argument("--private-case", required=True, help="有已知原答的私聊案例 ID")
    parser.add_argument("--speaker", required=True, help="私聊对方的十进制 QQ 号")
    parser.add_argument("--config-a", required=True, help="A 配置版本 ID")
    parser.add_argument("--config-b", required=True, help="B 配置版本 ID")
    parser.add_argument("--name-a", required=True, help="仅出现在 A 配置名中的标记")
    parser.add_argument("--name-b", required=True, help="仅出现在 B 配置名中的标记")
    parser.add_argument("--reference", required=True, help="仅出现在历史原答中的标记")
    parser.add_argument("--timeout", type=float, default=150, help="单次运行等待秒数")
    verify(parser.parse_args())
