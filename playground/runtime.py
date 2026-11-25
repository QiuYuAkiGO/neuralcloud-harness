"""用两个正式 JVM 和仅监听回环的 OneBot 收件端执行一轮。每一侧只持有自己的目录与凭据。"""
import http.client
import json
import os
import secrets
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
from contextlib import ExitStack, closing
from pathlib import Path

from websockets.exceptions import ConnectionClosed
from websockets.sync.server import serve


def properties(path):
    """读取管理者指定的模型配置，后续仍由正式 Java 配置边界做协议与字段校验。"""
    return dict(line.split("=", 1) for raw in Path(path).read_text(encoding="utf-8-sig").splitlines()
                if (line := raw.strip()) and not line.startswith(("#", "!")))


def write_config(path, values):
    path.write_text("".join(f"{key}={value.as_posix() if isinstance(value,Path) else value}\n"
                            for key, value in values.items()), encoding="utf-8")
    return path


def local_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def local_json(port, path, token=None):
    with closing(http.client.HTTPConnection("127.0.0.1",port,timeout=4)) as conn:
        conn.request("GET",path,headers={"Authorization": "Bearer "+token} if token else {})
        res = conn.getresponse()
        data = json.loads(res.read())
        if res.status != 200:
            raise RuntimeError(f"隔离服务 {path} 返回 {res.status}")
        return data


def wait_for(process, read, seconds, label):
    """启动探测只重试连接尚未建立的情况。模型与渠道错误从实际审计出口返回。"""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{label} 进程退出 {process.returncode}，见该实验日志")
        try:
            value = read()
        except (ConnectionRefusedError, ConnectionResetError, http.client.RemoteDisconnected):
            value = None
        if value:
            return value
        time.sleep(.12)
    raise TimeoutError(label+" 超时")


def seed_audit(home, chain, prefix):
    """将历史消息投影为正式 AuditLog 可读的入站/已送达事件；本轮观测在正式渠道只投递一次。"""
    folder = home/"audit"
    folder.mkdir()
    with (folder/"000-prefix.jsonl").open("w",encoding="utf-8") as out:
        number = 0

        def append(node, kind, data, when, parent=None):
            nonlocal number
            number += 1
            event = dict(schemaVersion=1,runId="snapshot",eventId=number,causedBy=parent,
                         chain=chain,node=node,recordedAt=when,type=kind,data=data)
            out.write(json.dumps(event,ensure_ascii=False)+"\n")
            return number

        for msg in prefix:
            when = msg["time"]
            if msg["kind"] == "observation":
                payload = {"text":msg["text"]}
                if msg["messageId"] and str(msg["messageId"]).isdecimal():
                    payload["messageId"] = int(msg["messageId"])
                append("openqq","qq_message",payload,when)
            else:
                parent = append("chat-worker","chat_worker_reply",{"text":msg["text"]},when)
                sending = append("openqq","qq_send_requested",{},when,parent)
                append("openqq","qq_delivery",{},when,sending)


def copy_db(source, destination):
    """SQLite online backup 只复制一个已完成的实验起点，不继承父运行的写入句柄。"""
    with closing(sqlite3.connect(source.resolve().as_uri()+"?mode=ro",uri=True)) as src:
        with closing(sqlite3.connect(destination)) as dst:
            src.backup(dst)


class Inbox:
    def __init__(self, token, account, group):
        self.token, self.account, self.group = token, account, group
        self.ws = None
        self.sent, self.actions = [], []

    def handle(self, ws):
        if ws.request.headers.get("Authorization") != "Bearer "+self.token:
            ws.close(1008,"token")
            return
        self.ws = ws
        try:
            for frame in ws:
                cmd = json.loads(frame)
                action, params = cmd["action"], cmd["params"]
                ok = True
                if action == "get_login_info":
                    data = {"user_id":self.account}
                elif action == "get_group_list":
                    data = ([dict(group_id=self.group,group_name=str(self.group),member_count=2,max_member_count=2)]
                            if self.group else [])
                elif action == "get_group_member_info":
                    ok, data = False, {}  # 快照不证明当前群成员身份
                elif action in ("send_group_msg","send_private_msg"):
                    self.sent.append(params)
                    data = {"message_id":secrets.randbelow(2**30)+1}
                else:
                    self.actions.append(action)
                    ok, data = False, {}
                ws.send(json.dumps(dict(status="ok" if ok else "failed",retcode=0 if ok else 1404,
                                        echo=cmd["echo"],data=data)))
        except ConnectionClosed:
            pass

    def deliver(self, kind, peer, sender, text, addressed):
        segments = [{"type":"text","data":{"text":text}}]
        if kind == "group" and addressed:
            segments.insert(0,{"type":"at","data":{"qq":str(self.account)}})
        message = dict(post_type="message",message_type=kind,sub_type="normal" if kind=="group" else "friend",
                       self_id=self.account,user_id=int(sender),message_id=secrets.randbelow(2**30)+1,message=segments)
        if kind == "group":
            message["group_id"] = peer
        self.ws.send(json.dumps(message,ensure_ascii=False))


def start_jvm(stack, home, name, args, env, settings):
    native = home/(name+"-native")
    native.mkdir()
    log = stack.enter_context((home/(name+".log")).open("wb"))
    proc = subprocess.Popen([settings.get("java","java"), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                             "-Dstderr.encoding=UTF-8",
                             "-Dorg.sqlite.tmpdir="+str(native), *args],
                            cwd=home,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,env=env,
                            creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
    def stop():
        if proc.poll() is None:
            if name == "memory":
                proc.stdin.close()
            else:
                proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
        if not proc.stdin.closed:
            proc.stdin.close()
    stack.callback(stop)
    return proc


def execute(settings, home, case, config, turn, *, base, prefix=(), parent=None):
    """关闭子进程前必须见到本次入站和送达回执；只有已确认为 SKIP 的轮次可没有回复正文。"""
    home.mkdir()
    dbfolder = home/"db"
    dbfolder.mkdir()
    channel, _, account_id, peer_id = case["conversation"].split(":")
    account, peer = int(account_id), int(peer_id)
    kind = "group" if channel=="group" else "private"
    chain = f"qq-{kind}-{account}-{peer}"
    if parent is None:
        copy_db(base,dbfolder/"memory.sqlite")
        seed_audit(home,chain,prefix)
    else:
        copy_db(parent/"db"/"memory.sqlite",dbfolder/"memory.sqlite")
        shutil.copytree(parent/"audit",home/"audit")
    token = secrets.token_urlsafe(32)
    env = dict(os.environ,NC_PLAYGROUND_MEMORY=token)
    mp, hp = local_port(), local_port()
    while hp == mp:
        hp = local_port()
    memory = write_config(home/"memory.properties",dict(port=mp,dataDirectory=dbfolder,characterId=case["character"],
                          sourceInstance="neuralcloud-java",accessTokenEnv="NC_PLAYGROUND_MEMORY"))
    client = write_config(home/"client.properties",dict(endpoint=f"http://127.0.0.1:{mp}/",
                          characterId=case["character"],accessTokenEnv="NC_PLAYGROUND_MEMORY",apiVersion=2,recallMode="scoped"))
    (home/"persona.txt").write_text(config["persona"],encoding="utf-8")
    (home/"preferences.txt").write_text(config["preferences"],encoding="utf-8")
    chat = write_config(home/"chat.properties",{**properties(settings["models"][config["model"]]),
                        "replyPreferences":home/"preferences.txt"})
    policyfile = home/"groups.json"
    policyfile.write_text(json.dumps({"account":str(account),"policies":{
        str(peer):dict(revision=1,policy=case["policy"])} if kind=="group" else {}}),encoding="utf-8")
    inbox = Inbox(token,account,peer if kind=="group" else None)
    inputs = home/"inputs.jsonl"
    with ExitStack() as stack:
        bot = serve(inbox.handle,"127.0.0.1",0)
        thread = threading.Thread(target=bot.serve_forever,daemon=True)
        thread.start()
        stack.callback(bot.shutdown)
        qq = write_config(home/"qq.properties",dict(endpoint=f"ws://127.0.0.1:{bot.socket.getsockname()[1]}/",
                          accountId=account,allowedUserId=int(turn["speaker"]),accessTokenEnv="NC_PLAYGROUND_MEMORY",
                          groupPoliciesFile=policyfile))
        console = write_config(home/"console.properties",dict(listenHost="127.0.0.1",listenPort=hp,
            auditDirectory=home/"audit",chatWorkerConfig=chat,chatWorkerRolePrompt=home/"persona.txt",
            routerConfig=Path(settings["routerConfig"]),routerRolePrompt="classpath:/config/router-role-prompt.txt",
            masterConfig=Path(settings["masterConfig"]),masterMaxTokens=512,masterTemperature=0,
            openQqConfig=qq,memoryConfig=client,gpuMetrics="NONE",metricsIntervalSeconds=10,metricsTimeoutSeconds=5))
        mem = start_jvm(stack,home,"memory",["-jar",settings["memoryJar"],"--config",str(memory),"--stop-on-eof"],env,settings)
        wait_for(mem,lambda:local_json(mp,"/v2/health",token),30,"Memory 就绪")
        proc = start_jvm(stack,home,"harness",["-Dneuralcloud.modelInputLog="+str(inputs),"-cp",settings["harnessJar"],
                         "neuralcloud.controlplane.app.ConsoleMain","--config",str(console)],env,settings)
        def ready():
            view = local_json(hp,"/api/qq/groups")
            return view if view["connected"] and inbox.ws and (kind=="private" or any(
                r["id"] == str(peer) and r["policy"] == case["policy"] for r in view["groups"])) else None
        wait_for(proc,ready,60,"Harness 与测试收件端握手")
        def events():
            return local_json(hp,"/api/audit/chains/"+chain)["events"]
        # 当前实例的空链也可能返回 404。历史计数从已复制的审计文件取得。
        before = sum(1 for p in (home/"audit").glob("*.jsonl") for ln in p.read_text(encoding="utf-8").splitlines()
                     if json.loads(ln)["chain"] == chain)
        inbox.deliver(kind,peer,turn["speaker"],turn["text"],turn["addressed"])
        def completed():
            try:
                new = events()[before:]
            except RuntimeError as error:
                if "返回 404" in str(error):
                    return None
                raise
            for event in new:
                typ, data = event["type"], event["data"]
                if typ.endswith(("_failed","_unknown","_cancelled")):
                    raise RuntimeError(f"{typ} {data.get('detail',data.get('reason',''))}")
                if typ=="qq_participation" and data["decision"]=="SKIP":
                    return new
                if typ=="memory_durable" and (data.get("kind")=="SELF_EXPRESSION" or case["policy"]=="OBSERVE"):
                    return new
            return None
        if case["policy"]=="OFF":
            # 已核验正式群策略为 OFF；OneBot 收帧后即过滤，此路径没有渠道审计事件。
            if not inbox.ws.ping().wait(5):
                raise TimeoutError("仅限实验的 OneBot 收帧超时")
            trace = []
        else:
            trace = wait_for(proc,completed,180,"回复或静默的正式回执")
        replies = [e["data"]["text"] for e in trace if e["type"]=="chat_worker_reply" and "text" in e["data"]]
        if len(inbox.sent) != len(replies) or len(replies)>1:
            raise RuntimeError("正式回复与测试收件端回执不一致")
        model_inputs = [json.loads(line) for line in inputs.read_text(encoding="utf-8").splitlines()] if inputs.exists() else []
        actions = [e["data"] for e in trace if e["type"]=="router_route" and e["data"].get("route")=="ACTION_REQUEST"]
        return dict(decision="REPLY" if replies else "SKIP",reply=replies[0] if replies else None,
                    input=turn,trace=trace,modelInputs=model_inputs,deliveries=len(inbox.sent),
                    actionRequests=actions,inboxActions=inbox.actions)
