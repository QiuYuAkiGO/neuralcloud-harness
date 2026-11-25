"""对话实验的应用边界。浏览器只能选已登记的快照和模型，无法提供网络端点或写入生产路径。"""
import json
import os
import secrets
import shutil
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from zipfile import ZipFile

from . import benchmark
from .models import judge
from .runtime import execute
from .scoring import summarize
from .snapshot import QQ, Snapshot
from .store import Store, now


def fields(body, required, optional=()):
    if not isinstance(body,dict) or not set(required)<=body.keys()<=set(required)|set(optional):
        raise ValueError("请求字段不符合约定 "+", ".join(required))


def string(value, field, limit=65536):
    if not isinstance(value,str) or not value.strip() or len(value)>limit:
        raise ValueError(f"{field} 须为非空文本，最多 {limit} 字")
    return value


def choice(value, allowed, field):
    if value not in allowed:
        raise ValueError(f"{field} 不属于允许值")
    return value


class Playground:
    def __init__(self, settings):
        self.settings = settings
        self.root = Path(settings["data"]).resolve()
        self.root.mkdir(parents=True,exist_ok=True)
        self.sources = {key:Snapshot(v["path"],v.get("audit")) for key,v in settings["snapshots"].items()}
        self.store = Store(self.root)
        self.pool = ThreadPoolExecutor(max_workers=2,thread_name_prefix="playground")
        # 保留实际使用的 JAR 和模型配置，配置文件或本地构建更新后仍能复核并延续旧分支。
        self.assets = self.root/"assets"
        self.release = {key:self.freeze(key,settings[key]) for key in ("harnessJar","memoryJar","routerConfig","masterConfig")}
        self.profiles = {key:self.freeze("model",path) for key,path in settings["models"].items()}
        self.release["judge"] = self.profiles[settings["judge"]]
        with ZipFile(self.asset("harnessJar",self.release["harnessJar"])) as jar:
            self.persona = jar.read("config/chat-worker-misty-prompt.txt").decode("utf-8")
            self.preferences = jar.read("config/chat-worker-misty-reply-preferences.txt").decode("utf-8")
        if not self.store.all("config"):
            self.configuration({"name":"正式基线","model":settings["defaultModel"],"persona":self.persona,
                                "preferences":self.preferences})

    def asset(self, name, digest):
        return self.assets/digest/name

    def freeze(self, name, source):
        digest = benchmark.file_hash(source)
        dest = self.asset(name,digest)
        if not dest.exists():
            dest.parent.mkdir(parents=True,exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=dest.parent,delete=False) as tmp, open(source,"rb") as src:
                shutil.copyfileobj(src,tmp)
            try:
                if benchmark.file_hash(tmp.name)!=digest:
                    raise ValueError("运行配置在锁定版本时发生了变化")
                os.replace(tmp.name,dest)
            finally:
                Path(tmp.name).unlink(missing_ok=True)
        return digest

    def source(self,key):
        if key not in self.sources:
            raise ValueError("快照未登记")
        return self.sources[key]

    def state(self):
        return {"sources":[dict(id=k,character=v.character,watermark=v.watermark,digest=v.digest)
                           for k,v in self.sources.items()],"models":list(self.settings["models"]),
                "configs":self.store.all("config"),"cases":self.store.all("case"),
                "batches":self.store.all("batch"),"benchmarks":self.store.all("benchmark"),
                "runs":self.store.recent_runs(),
                "fixedPersona":self.persona,"judge":self.settings["judge"]}

    def configuration(self,b):
        fields(b,("name","model","persona","preferences"))
        for k in ("name","persona","preferences"):
            string(b[k],k,128 if k=="name" else 32000)
        choice(b["model"],self.settings["models"],"model")
        return self.store.add("config",{**b,"modelProfile":self.profiles[b["model"]]})

    def case(self,b):
        fields(b,("source","conversation","target","split"),("name","variant","expected","rationale"))
        source = self.source(b["source"])
        cut = source.cut(b["conversation"],b["target"])
        item = cut["target"]
        kind, _, peer = QQ.fullmatch(b["conversation"]).groups()
        kind = "group" if kind=="group" else "private"
        variant = b.get("variant",{})
        fields(variant,(),("policy","addressed","speaker"))
        policy = variant.get("policy",item["policy"])
        addressed = variant.get("addressed",item["addressed"])
        if kind=="private" and (policy is not None or addressed is not True):
            raise ValueError("私聊使用直接寻址")
        if kind=="group":
            choice(policy,("OBSERVE","OBSERVE_REPLY","OFF",None),"policy")
        if addressed is not None and type(addressed) is not bool:
            raise ValueError("addressed 须为布尔值")
        speaker = variant.get("speaker",item["sender"])
        if speaker is not None and (not isinstance(speaker,str) or not speaker.isdecimal()
                or int(speaker)<=0 or (kind=="private" and speaker!=peer)):
            raise ValueError("speaker 须为该会话的发言人账号")
        expected = b.get("expected")
        if expected is not None:
            choice(expected,("REPLY","SKIP","EITHER"),"expected")
            string(b.get("rationale"),"rationale")
        choice(b["split"],("dev","regression"),"split")
        return self.store.add("case",dict(name=string(b.get("name",item["time"]),"name",160),source=b["source"],
              snapshot=source.digest,conversation=b["conversation"],target=item["id"],revision=item["revision"],
              watermark=cut["watermark"],kind=kind,character=source.character,policy=policy,addressed=addressed,
              speaker=speaker,variant=variant,split=b["split"],expected=expected,rationale=b.get("rationale","")),
              split_key=source.character+"/"+b["conversation"])

    def start(self,b, *, parent=None):
        case,plan = self.plan_run(b,parent)
        doc = self.store.add("run",plan)
        self.pool.submit(self._run,doc,case)
        return {"id":doc["id"]}

    def plan_run(self,b,parent=None):
        """先将快照、输入、两个配置版本全部绑定，再将运行登记到工作队列。"""
        if parent is None:
            fields(b,("caseId","configs"))
            case = self.store.get("case",b["caseId"])
            configs = b["configs"]
            if not isinstance(configs,list) or len(configs)!=2 or len(set(configs))!=2:
                raise ValueError("A/B 须选择两个不同的配置版本")
            configs = [self.store.get("config",i)["id"] for i in configs]
            secrets.SystemRandom().shuffle(configs)
            plan = list(zip("AB",configs))
            source = self.source(case["source"])
            if source.digest != case["snapshot"]:
                raise ValueError("案例绑定的快照版本已变化")
            target = next(x for x in source.timeline(case["conversation"]) if x["id"]==case["target"])
            turn = dict(speaker=case["speaker"],addressed=case["addressed"],text=target["text"])
        else:
            fields(b,("side","speaker","text","addressed"))
            if parent["status"] not in ("complete","failed"):
                raise ValueError("父运行尚未结束")
            lane = next((x for x in parent["lanes"] if x["side"]==b["side"]),None)
            if not lane or lane["status"]!="complete":
                raise ValueError("所选分支未成功完成")
            case = self.store.get("case",parent["caseId"])
            if self.source(case["source"]).digest != case["snapshot"]:
                raise ValueError("案例绑定的快照版本已变化")
            plan = [(lane["side"],lane["config"])]
            turn = {k:b[k] for k in ("speaker","text","addressed")}
            string(turn["text"],"text",16000)
            if case["kind"]=="private":
                if turn["speaker"]!=case["conversation"].split(":")[-1] or turn["addressed"] is not True:
                    raise ValueError("私聊续聊须由原对方直接发言")
            else:
                members = {x["sender"] for x in self.source(case["source"]).timeline(case["conversation"])
                           if x["seq"]<=case["watermark"]+1}
                if turn["speaker"] not in members|{case["speaker"]}:
                    raise ValueError("只能选择截点前已出现的群成员")
        if (type(turn["addressed"]) is not bool or not turn["speaker"]
                or (case["kind"]=="group" and case["policy"] is None)):
            raise ValueError("缺少寻址、发言人或群策略证据，请显式创建条件变体")
        lanes = []
        for side,ident in plan:
            conf = self.store.get("config",ident)
            profile = conf["modelProfile"] if "modelProfile" in conf else self.profiles[conf["model"]]
            if parent:
                profile = lane.get("modelProfile",profile)
            lanes.append(dict(side=side,config=ident,modelProfile=profile,status="queued",reviews=[]))
        return case,dict(caseId=case["id"],parent=parent["id"] if parent else None,
            release=parent.get("release",self.release) if parent else self.release,
            status="queued",turn=turn,lanes=lanes)

    def _lane(self, rid, side, result):
        def change(doc):
            next(x for x in doc["lanes"] if x["side"]==side).update(result)
        self.store.change("run",rid,change)

    def _run(self,doc,case):
        rid = doc["id"]
        folder = self.root/"runs"/rid
        self.store.change("run",rid,lambda d:d.update(status="running"))
        try:
            folder.mkdir(parents=True)
            source = self.source(case["source"])
            cut = source.cut(case["conversation"],case["target"])
            assets = doc["release"]
            rig = {**self.settings,**{k:str(self.asset(k,assets[k])) for k in
                    ("harnessJar","memoryJar","routerConfig","masterConfig")}}
            with ZipFile(rig["harnessJar"]) as jar:
                fixed_persona = jar.read("config/chat-worker-misty-prompt.txt").decode("utf-8")
            base = folder/"base.sqlite"
            if not doc["parent"]:
                source.materialize(cut,base,rig["memoryJar"])
            for entry in doc["lanes"]:
                side = entry["side"]
                self._lane(rid,side,{"status":"running"})
                try:
                    parent = self.root/"runs"/doc["parent"]/side if doc["parent"] else None
                    config = self.store.get("config",entry["config"])
                    rig["models"] = {config["model"]:self.asset("model",entry["modelProfile"])}
                    lane = execute(rig,folder/side,case,config,
                                   doc["turn"],base=base,prefix=cut["prefix"],parent=parent)
                    if lane["reply"]:
                        try:
                            lane["initial"] = judge(self.asset("model",assets["judge"]),fixed_persona,
                                    [{k:x[k] for k in ("kind","sender","speaker","text")} for x in cut["prefix"][-24:]],lane)
                        except (OSError,ValueError,KeyError,RuntimeError) as err:
                            lane["judgeError"] = str(err)
                    self._lane(rid,side,{**lane,"status":"complete"})
                except Exception as err:
                    # 此侧保留失败和栈，其他侧仍可运行。绝不将失败登记为静默。
                    import traceback
                    (folder/(side+"-failure.log")).write_text(traceback.format_exc(),encoding="utf-8")
                    self._lane(rid,side,dict(status="failed",error=str(err)))
            def finish(d):
                d["status"] = "complete" if all(l["status"]=="complete" for l in d["lanes"]) else "failed"
            self.store.change("run",rid,finish)
        except Exception as err:
            def fail(d):
                d.update(status="failed",error=str(err))
                for lane in d["lanes"]:
                    if lane["status"]!="complete":
                        lane.update(status="failed",error=str(err))
            self.store.change("run",rid,fail)

    def view(self,rid):
        doc = self.store.get("run",rid)
        case = self.store.get("case",doc["caseId"])
        done = any(lane["reviews"] for lane in doc["lanes"]) and all(
            lane["reviews"] or lane["status"]=="failed" for lane in doc["lanes"])
        doc["revealed"] = done
        self.flag_reviews(doc)
        for lane in doc["lanes"]:
            conf = lane.pop("config")
            if done:
                lane["configuration"] = self.store.get("config",conf)["name"]
            else:
                lane.pop("modelProfile",None)
                lane.pop("modelInputs",None)
                # 审计可带模型名与配置线索，盲评时只显示决定和路由，揭晓后可查看完整原始证据。
                lane["trace"] = [dict(type=e["type"],data=e["data"]) for e in lane.get("trace",[])
                                 if e["type"] in ("qq_participation","router_route","memory_durable","memory_recall")]
        if done and not doc["parent"]:
            rows = self.source(case["source"]).timeline(case["conversation"])
            pos = next(i for i,x in enumerate(rows) if x["id"]==case["target"])
            doc["historical"] = []
            for item in rows[pos+1:]:
                if item["kind"]!="self_expression":
                    break
                doc["historical"].append(item)
        return doc

    @staticmethod
    def flag_reviews(run):
        """将两项独立初评的不确定、失败及两侧分歧送人工复核，保留原初评。"""
        replies = [l for l in run["lanes"] if l.get("decision")=="REPLY"]
        ratings = {tuple(l["initial"][k] for k in ("persona","reasonable")) for l in replies if l.get("initial")}
        for lane in replies:
            verdict = lane.get("initial",{})
            if not lane["reviews"]:
                if len(ratings)>1:
                    lane["reviewReason"] = "两侧回复的初评结论有分歧"
                elif not verdict or "REVIEW" in (verdict["persona"],verdict["reasonable"]):
                    lane["reviewReason"] = lane.get("judgeError","裁判请求人工复核")

    def review(self,rid,b):
        fields(b,("side","persona","reasonable","reason"))
        string(b["reason"],"reason",4000)
        def append(doc):
            lane = next((x for x in doc["lanes"] if x["side"]==b["side"]),None)
            if not lane or lane["status"]!="complete":
                raise ValueError("待评一侧尚无成功运行")
            options = ("PASS","FAIL") if lane["decision"]=="REPLY" else (None,)
            for k in ("persona","reasonable"):
                choice(b[k],options,k)
            lane["reviews"].append({**b,"at":now()})
        self.store.change("run",rid,append)
        return self.view(rid)

    def batch(self,b):
        fields(b,("name","cases","configs"))
        string(b["name"],"name",120)
        if not isinstance(b["cases"],list) or not b["cases"] or len(set(b["cases"]))!=len(b["cases"]):
            raise ValueError("批次须包含互不重复的案例")
        cases = [self.store.get("case",i) for i in b["cases"]]
        if len({c["split"] for c in cases}) != 1:
            raise ValueError("批次案例须同属一个分集")
        planned = [self.plan_run({"caseId":c["id"],"configs":b["configs"]}) for c in cases]
        # 批次清单和各运行原子登记，重启后的报告仍可逐条核对未完成的项。
        with self.store.transaction() as db:
            tasks = [(case,self.store.insert(db,"run",plan)) for case,plan in planned]
            batch = self.store.insert(db,"batch",{**b,"runs":[run["id"] for _,run in tasks]})
        for case,run in tasks:
            self.pool.submit(self._run,run,case)
        return {"id":batch["id"]}

    def report(self,bid):
        batch = self.store.get("batch",bid)
        rows, details = [], []
        for rid in batch["runs"]:
            run = self.store.get("run",rid)
            self.flag_reviews(run)
            case = self.store.get("case",run["caseId"])
            for lane in run["lanes"]:
                if lane["status"] not in ("complete","failed"):
                    continue
                mark = (lane["reviews"] or [lane.get("initial",{})])[-1]
                row = dict(id=rid+"/"+lane["side"],kind=case["kind"],addressing="DIRECT" if case["addressed"] else "AMBIENT",
                    policy=case["policy"],expected=case["expected"],decision=lane.get("decision"),status=lane["status"],
                    persona=mark.get("persona"),reasonable=mark.get("reasonable"))
                if lane.get("reviewReason"):
                    row.update(persona=None,reasonable=None)
                rows.append((lane["config"],row))
                details.append({"run":rid,"case":case["name"],"side":lane["side"],
                                "reviewReason":lane.get("reviewReason"),**row})
        # 匿名分支与配置的对应关系在盲评中保密，具名的批次比较只提供汇总数字。
        byconfig = {}
        for conf in batch["configs"]:
            totals = summarize([r for key,r in rows if key==conf])
            totals.pop("links")
            byconfig[conf] = totals
        return {**batch,"overall":summarize([r for _,r in rows]),"byConfig":byconfig,"items":details}

    def start_benchmark(self,b):
        fields(b,("model","limit"))
        choice(b["model"],self.settings["models"],"model")
        if b["limit"] is not None and (type(b["limit"])!=int or b["limit"]<1):
            raise ValueError("limit 须为正整数或 null")
        repo = self.settings.get("benchmarkRepo")
        if not repo:
            raise ValueError("尚未配置 CharacterEval 目录")
        profile = self.profiles[b["model"]]
        job = self.store.add("benchmark",dict(status="queued",modelProfile=profile,**b))
        def generate():
            try:
                info = benchmark.generate_job(self.root/"benchmark"/job["id"],repo,
                                               self.asset("model",profile),b["limit"],b["model"])
            except Exception as exc:
                info = dict(status="failed",error=str(exc))
            self.store.change("benchmark",job["id"],lambda d:d.update(info))
        self.pool.submit(generate)
        return {"id":job["id"]}

    def score_benchmark(self,ident,b):
        fields(b,("generationHash","weightsRevision","rows"))
        job = self.store.get("benchmark",ident)
        if job["status"] not in ("awaiting_scores","complete"):
            raise ValueError("任务尚未生成可评结果")
        root = self.root/"benchmark"/ident
        result = benchmark.accept_scores(root,b["rows"],b["generationHash"],b["weightsRevision"])
        return self.store.change("benchmark",ident,lambda d:d.update(status="complete",report=result))
