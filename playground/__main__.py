"""启动回环实验服务，或将已生成的公开题带到装有 CharacterRM 的机器评分。"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from . import benchmark
from .service import Playground, fields


def public_job(app, entry):
    if entry["status"] not in ("awaiting_scores","complete"):
        raise ValueError("任务仍无完整的生成文件")
    root = app.root/"benchmark"/entry["id"]
    job = json.loads((root/"job.json").read_text(encoding="utf-8"))
    if benchmark.file_hash(root/"results/generation.jsonl") != job["generationHash"]:
        raise ValueError("生成文件与任务清单不一致")
    # 包内保留原始 UTF-8（含换行）；解码再序列化会使生成文件的 SHA-256 改变。
    return {"job":{**job,"sourceRevision":benchmark.SOURCE_REVISION},
            "generationUtf8":(root/"results/generation.jsonl").read_bytes().decode("utf-8")}


def dispatch(app,method,path,query,body):
    """路由只接收业务 ID。文件、模型地址和测试库路径均取自服务端配置。"""
    if not path.startswith("/api/playground/"):
        raise KeyError(path)
    parts = path[len("/api/playground/"):].strip("/").split("/")
    name,*tail = parts
    if method=="GET":
        if name=="state" and not tail:
            return app.state()
        if name in ("conversations","messages") and not tail:
            src = app.source(query["source"])
            if name=="conversations":
                return src.conversations(query.get("kind"),query.get("q",""),query.get("after"),query.get("before"))
            return src.timeline(query["conversation"])
        if name=="runs" and len(tail)==1:
            return app.view(tail[0])
        if name=="batches" and len(tail)==1:
            return app.report(tail[0])
        if name=="benchmarks" and tail:
            entry = app.store.get("benchmark",tail[0])
            if len(tail)==1:
                return entry
            if tail[1:]==["export"]:
                return public_job(app,entry)
    if method=="POST":
        if not tail and name in ("configs","cases","runs","batches","benchmarks"):
            return {"configs":app.configuration,"cases":app.case,"runs":app.start,
                    "batches":app.batch,"benchmarks":app.start_benchmark}[name](body)
        if name=="runs" and len(tail)==2:
            if tail[1]=="review":
                return app.review(tail[0],body)
            if tail[1]=="turns":
                return app.start(body,parent=app.store.get("run",tail[0]))
        if name=="batches" and len(tail)==2 and tail[1]=="repeat":
            fields(body,("configs",))
            old=app.store.get("batch",tail[0])
            return app.batch(dict(name=old["name"],cases=old["cases"],configs=body["configs"]))
        if name=="benchmarks" and len(tail)==2 and tail[1]=="scores":
            return app.score_benchmark(tail[0],body)
    raise KeyError(path)


def serve(app,port):
    class Handler(BaseHTTPRequestHandler):
        def respond(self):
            url = urlsplit(self.path)
            try:
                if self.headers.get("Host","").split(":")[0] not in ("127.0.0.1","localhost"):
                    raise ValueError("实验接口仅接受本机 Host")
                query = {k:v[0] for k,v in parse_qs(url.query).items()}
                body = None
                if self.command=="POST":
                    limit = 4*1024*1024 if url.path.endswith("/scores") else 262144
                    size = int(self.headers.get("Content-Length","0"))
                    if self.headers.get_content_type()!="application/json" or not 0<size<=limit:
                        raise ValueError("JSON 请求体长度或类型不正确")
                    body = json.loads(self.rfile.read(size))
                result = dispatch(app,self.command,url.path,query,body)
                status = 200
            except (ValueError,TypeError) as exc:
                result,status = {"error":str(exc)},400
            except KeyError as exc:
                result,status = {"error":"缺少字段或资源 "+str(exc)},404
            except Exception:
                traceback.print_exc()
                result,status = {"error":"实验服务出错，详见服务日志"},500
            raw = json.dumps(result,ensure_ascii=False,allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type","application/json; charset=utf-8")
            self.send_header("Cache-Control","no-store")
            self.send_header("Content-Length",str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = respond

        def log_message(self,fmt,*args):
            # 搜索关键词、会话号和正文只在受限的实验数据目录中保存。
            pass

    with ThreadingHTTPServer(("127.0.0.1",port),Handler) as server:
        print(f"Playground 就绪 127.0.0.1:{port}",flush=True)
        server.serve_forever()


def adopt(app, initial):
    """启动时登记管理员明确指定、已锁定数据与权重的生成任务，避免重发已经完成的模型调用。"""
    repo=Path(app.settings["benchmarkRepo"])
    benchmark.check_source(repo)
    metric_map=json.loads((repo/"data/id2metric.jsonl").read_text(encoding="utf-8"))
    for folder in initial:
        root=Path(folder)
        run=json.loads((root/"job.json").read_text(encoding="utf-8"))
        digest=benchmark.file_hash(root/"results/generation.jsonl")
        rows=json.loads((root/"results/generation.jsonl").read_text(encoding="utf-8"))
        expected={str(x["id"]):[v[0] for v in metric_map[str(x["id"])]] for x in rows}
        if (digest!=run["generationHash"] or run["weightsRevision"]!=benchmark.WEIGHTS_REVISION or
                expected!=run["expectedIds"] or len(expected)!=len(rows) or
                (run["full"] and set(expected)!=set(metric_map))):
            raise ValueError("导入的基准任务与锁定版本不匹配")
        matches=[x for x in app.store.all("benchmark") if x.get("generationHash")==digest]
        if any(x["status"] in ("awaiting_scores","complete") for x in matches):
            continue
        proof=json.loads((root/"generation-manifest.json").read_text(encoding="utf-8"))
        if proof.get("source_rev")!=benchmark.SOURCE_REVISION:
            raise ValueError("导入的生成资料版本不符")
        entry=matches[0] if matches else app.store.add("benchmark",dict(
            model=proof["tested_model"],status="queued",generationHash=digest))
        dest=app.root/"benchmark"/entry["id"]
        (dest/"results").mkdir(parents=True,exist_ok=True)
        for path in ("job.json","generation-manifest.json","results/generation.jsonl"):
            shutil.copyfile(root/path,dest/path)
        (dest/"data").mkdir(exist_ok=True)
        for file in ("id2metric.jsonl","character_profiles.json"):
            shutil.copyfile(repo/"data"/file,dest/"data"/file)
        subprocess.run([sys.executable,"-X","utf8",str(repo/"transform_format.py")],cwd=dest,check=True)
        app.store.change("benchmark",entry["id"],lambda d:d.update(status="awaiting_scores",count=len(expected),
                         kind="full" if run["full"] else "sample",weightsRevision=run["weightsRevision"]))


@contextmanager
def instance(data):
    """持有实验目录的进程锁后才允许恢复中断任务。操作系统在进程退出时自动释放锁。"""
    root=Path(data)
    root.mkdir(parents=True,exist_ok=True)
    with (root/"service.lock").open("a+b") as handle:
        if not handle.tell():
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name=="nt":
                import msvcrt
                msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError as err:
            raise RuntimeError("另一个实例正在使用这个实验目录") from err
        yield


def main():
    opts=argparse.ArgumentParser(description=__doc__)
    sub=opts.add_subparsers(dest="command",required=True)
    start=sub.add_parser("serve")
    start.add_argument("--config",type=Path,required=True)
    score=sub.add_parser("score",help="以下载的任务和固定权重执行作者的 CUDA 评分脚本")
    score.add_argument("--package",type=Path,required=True)
    score.add_argument("--repo",type=Path,required=True)
    score.add_argument("--weights",type=Path,required=True)
    score.add_argument("--out",type=Path,required=True)
    args=opts.parse_args()
    if args.command=="serve":
        cfg=json.loads(args.config.read_text(encoding="utf-8-sig"))
        with instance(cfg["data"]):
            app=Playground(cfg)
            if cfg.get("initialBenchmarks"):
                adopt(app,cfg["initialBenchmarks"])
            serve(app,cfg["port"])
    else:
        benchmark.score_package(args.package,args.repo,args.weights,args.out)


if __name__=="__main__":
    main()
