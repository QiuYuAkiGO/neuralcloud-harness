"""在独立进程中监视单个 Colab 作业，通过该 VM 的管理端点归还运行时。"""

import argparse
import fcntl
import http.client
import json
import os
from pathlib import Path
import signal
from contextlib import contextmanager
from tempfile import NamedTemporaryFile
import time
from urllib.parse import urlsplit


def publish(path, value):
    """先完整写入同目录临时文件，其他进程只会读到一个完整状态。"""
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.write("\n")
    os.replace(stream.name, path)


def read(path):
    return json.loads(path.read_bytes())


@contextmanager
def transaction(directory):
    """串行化进度、终态和释放意图的发布，网络调用始终在锁外。"""
    with (directory / "state.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def process_start(pid):
    """Linux 进程起始时钟能区分 PID 复用；僵尸进程已无法再执行作业。"""
    try:
        stat = Path(f"/proc/{pid}/stat").read_text()
    except (ProcessLookupError, FileNotFoundError):
        return None
    fields = stat.rsplit(")", 1)[1].split()
    return None if fields[0] in ("Z", "X") else fields[19]


def record(directory, action, value):
    """同一进度标记不会续期；首次结束声明一旦提交就不能再切换结果。"""
    with transaction(directory):
        if not (directory / "ready.json").is_file():
            raise ValueError("state-dir 中还没有已就绪的守护")
        end = directory / "outcome.json"
        if end.exists() and action == "finish" and read(end)["reason"] == value:
            return
        if end.exists() or (directory / "releasing.json").exists():
            raise ValueError("作业已结束，不能再提交进度或不同的结束结果")
        if action == "finish":
            publish(end, {"reason": value})
            return
        activity = directory / "activity.json"
        if not activity.exists() or read(activity)["marker"] != value:
            publish(activity, {"marker": value, "at": time.monotonic()})


def release_reason(directory, ready, signalled):
    """持锁确定首次释放原因。硬期限自第一次启动起算，恢复守护也不会延长。"""
    pending = directory / "releasing.json"
    if pending.exists():
        return read(pending)["reason"]
    end = directory / "outcome.json"
    if end.exists():
        return read(end)["reason"]
    if signalled:
        return "failed"
    if process_start(ready["owner_pid"]) != ready["owner_start"]:
        return "owner_exited"
    now = time.monotonic()
    if now >= ready["started"] + ready["max_seconds"]:
        return "hard_timeout"
    activity = directory / "activity.json"
    last = read(activity)["at"] if activity.exists() else ready["started"]
    if now >= last + ready["idle_seconds"]:
        return "idle_timeout"
    return None


def unassign(directory, address, reason):
    """使用 Colab 的 VM 内管理接口；仅 HTTP 200 确认归还，其余结果保留重试。"""
    attempt = 0
    delay = 0.5
    while True:
        attempt += 1
        connection = http.client.HTTPConnection(address.hostname, address.port, timeout=5)
        try:
            connection.request("POST", "/unassign", body=b"")
            status = connection.getresponse().status
        except (OSError, http.client.HTTPException) as exc:
            status = type(exc).__name__
        finally:
            connection.close()
        event = {"reason": reason, "attempt": attempt, "status": status, "at": time.time()}
        if status == 200:
            publish(directory / "released.json", event)
            return
        publish(directory / "last-release-attempt.json", event)
        time.sleep(delay)
        delay = min(10, delay * 2)


def watch(args):
    """先确认运行时、所属进程和排他锁，再开放 ready.json 供作业入口使用。"""
    addr = urlsplit("http://" + os.environ.get("TBE_RUNTIME_ADDR", ""))
    if not addr.hostname or addr.username or addr.password or addr.path or addr.query or addr.fragment:
        raise ValueError("TBE_RUNTIME_ADDR 须为本实例的 Colab 管理端主机地址")
    # 提前校验端口以免已开放 ready.json 的守护在第一次释放时才发现配置错误。
    if addr.port == 0:
        raise ValueError("TBE_RUNTIME_ADDR 端口必须大于零")
    if args.idle_seconds > args.max_seconds:
        raise ValueError("idle-seconds 不得大于 max-seconds")
    directory = args.state_dir
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "watch.lock").open("a") as owner_lock:
        try:
            fcntl.flock(owner_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ValueError("state-dir 已有运行中的守护") from exc
        notified = False

        def terminate(number, frame):
            nonlocal notified
            notified = True

        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, terminate)
        path = directory / "ready.json"
        settings = dict(owner_pid=args.owner_pid, max_seconds=args.max_seconds, idle_seconds=args.idle_seconds)
        if path.exists():
            ready = read(path)
            if (directory / "released.json").exists() or any(ready[k] != v for k, v in settings.items()):
                raise ValueError("state-dir 已归还或与原作业的 PID、时限不符")
        else:
            identity = process_start(args.owner_pid)
            if identity is None:
                raise ValueError("owner-pid 须指向正在运行的 Linux 进程")
            ready = dict(**settings, owner_start=identity, started=time.monotonic())
            publish(path, ready)
        while True:
            with transaction(directory):
                reason = release_reason(directory, ready, notified)
                if reason:
                    publish(directory / "releasing.json", {"reason": reason})
            if reason:
                unassign(directory, addr, reason)
                return
            time.sleep(0.25)


def positive(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("须为正整数")
    return number


def main():
    """watch 在专用 Colab VM 上运行；调用方按真实工作进度及交付结果通知守护。"""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for name in ("watch", "progress", "finish"):
        sub = commands.add_parser(name)
        sub.add_argument("--state-dir", type=Path, required=True)
        if name == "watch":
            sub.add_argument("--owner-pid", type=positive, required=True)
            sub.add_argument("--max-seconds", type=positive, required=True)
            sub.add_argument("--idle-seconds", type=positive, required=True)
        elif name == "progress":
            sub.add_argument("--marker", required=True)
        else:
            sub.add_argument("--outcome", choices=("completed", "failed"), required=True)
    args = parser.parse_args()
    if not args.state_dir.is_absolute():
        parser.error("state-dir 须为绝对路径")
    try:
        if args.action == "watch":
            watch(args)
        elif args.action == "progress":
            if not args.marker.strip():
                raise ValueError("marker 须为非空进度标记")
            record(args.state_dir, args.action, args.marker)
        else:
            record(args.state_dir, args.action, args.outcome)
    except (FileNotFoundError, ValueError) as exc:
        parser.exit(2, f"{exc}\n")


if __name__ == "__main__":
    main()
