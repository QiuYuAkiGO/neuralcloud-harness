"""CharacterEval 的逐题指标契约。保留缺项和覆盖数，按官方脚本的算术平均聚合。"""
import json
import math
from collections import defaultdict
from pathlib import Path


def metric_report(rows, *, expected_ids, full):
    """每道题只接收作者分配的指标；重复返回会扭曲该指标的分母。"""
    wanted = {(str(i), metric) for i, metrics in expected_ids.items() for metric in metrics}
    seen, values = set(), defaultdict(list)
    for row in rows:
        metric = row["metric_en"]
        key = str(row["id"]), metric
        value = row.get(metric)
        if (key not in wanted or key in seen or type(value) not in (int, float)
                or not math.isfinite(value) or not 1 <= value <= 5):
            raise ValueError(f"CharacterRM 题号、指标或分数无效 {key}")
        seen.add(key)
        values[metric].append(value)
    return {"kind": "full" if full else "sample", "status": "complete" if seen == wanted and wanted else "incomplete",
            "expected": len(wanted), "scored": len(seen), "missing": sorted(wanted - seen),
            "metrics": {name: {"mean": sum(scores) / len(scores), "count": len(scores)}
                        for name, scores in sorted(values.items())}}

# 作者仓库、逐题分配和奖励模型都以不可变版本作为验收输入。
SOURCE_REVISION = "c3d44a6fc1790cc8c4b2fd7c01f0c72930655e0c"
WEIGHTS_REVISION = "e953da5f57b86db7adca3a2714b4c3720f5b96a2"


def file_hash(path):
    import hashlib
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save_json(path, value):
    """同一磁盘原子发布。读者只会看到完整文件；不同任务持有独立的输出目录。"""
    import os
    import tempfile
    path = Path(path)
    with tempfile.NamedTemporaryFile("w",dir=path.parent,encoding="utf-8",delete=False) as out:
        json.dump(value,out,ensure_ascii=False,indent=2,allow_nan=False)
        tmp = out.name
    os.replace(tmp,path)


def accept_scores(job_dir, scored_rows, generation_hash, weights_revision):
    """先绑定实际生成字节、权重和每题指标，全部通过后才发布报告。"""
    root = Path(job_dir)
    job = json.loads((root/"job.json").read_text(encoding="utf-8"))
    if (job["generationHash"] != generation_hash or file_hash(root/"results/generation.jsonl") != generation_hash
            or weights_revision != job["weightsRevision"]):
        raise ValueError("生成结果哈希或权重版本不匹配")
    report = metric_report(scored_rows,expected_ids=job["expectedIds"],full=job["full"])
    if report["status"] != "complete":
        raise ValueError("缺少本批分配的评分")
    save_json(root/"scores.json",scored_rows)
    save_json(root/"report.json",report)
    return report


def check_source(repo):
    """使用作者发布的原脚本和数据；工作区有改写时禁止以固定版本的名义评分。"""
    import subprocess
    files = ["data/test_data.jsonl","data/character_profiles.json","data/id2metric.jsonl",
             "transform_format.py","run_char_rm.py","compute_score.py"]
    revision = subprocess.check_output(["git","-C",str(repo),"rev-parse","HEAD"],text=True).strip()
    if revision != SOURCE_REVISION or subprocess.run(["git","-C",str(repo),"diff","--quiet","HEAD","--",*files]).returncode:
        raise ValueError("CharacterEval 数据与脚本版本不匹配")


def generate_job(root, repo, profile, limit, name):
    """按原角色和已发布上下文生成；正式角色提示、真实聊天及其记忆不进入公开基准。"""
    import shutil
    import subprocess
    import sys
    from .models import model_call
    repo,root = Path(repo),Path(root)
    check_source(repo)
    data = repo/"data"
    tests = json.loads((data/"test_data.jsonl").read_text(encoding="utf-8"))
    metrics = json.loads((data/"id2metric.jsonl").read_text(encoding="utf-8"))
    profiles = json.loads((data/"character_profiles.json").read_text(encoding="utf-8"))
    eligible = [row for row in tests if str(row["id"]) in metrics]
    if limit is not None and not 1 <= limit <= len(eligible):
        raise ValueError("抽样题数超出已分配指标的测试题范围")
    if limit is None:
        selected = eligible
    else:
        # 不受官方数据文件的排列顺序影响，跨角色均匀选取固定的可重放子集。
        by_role = defaultdict(list)
        for row in eligible:
            by_role[row["role"]].append(row)
        selected = []
        from itertools import zip_longest
        for group in zip_longest(*(by_role[k] for k in sorted(by_role))):
            selected.extend(row for row in group if row)
            if len(selected)>=limit:
                break
        selected = selected[:limit]
    (root/"results").mkdir(parents=True)
    (root/"data").mkdir()
    for filename in ("id2metric.jsonl","character_profiles.json"):
        shutil.copyfile(data/filename,root/"data"/filename)
    for filename in ("transform_format.py","run_char_rm.py","compute_score.py"):
        shutil.copyfile(repo/filename,root/filename)
    # 大批次按题落盘，生成失败时可核对已经完成的题目及调用成本。
    out = []
    with (root/"responses.ndjson").open("a",encoding="utf-8") as progress:
        for row in selected:
            prompt = str(profiles[row["role"]]) + "\n请根据以上资料扮演"+row["role"]+"，只写这个角色接下来的一句发言。"
            reply = model_call(profile,prompt,row["context"])
            item = {**row,"model_output":reply}
            out.append(item)
            progress.write(json.dumps(item,ensure_ascii=False)+"\n")
            progress.flush()
    result_file = root/"results/generation.jsonl"
    save_json(result_file,out)
    subprocess.run([sys.executable,"-X","utf8",str(repo/"transform_format.py")],cwd=root,check=True)
    expected = {str(row["id"]):[metric[0] for metric in metrics[str(row["id"])]] for row in selected}
    job = dict(generationHash=file_hash(result_file),weightsRevision=WEIGHTS_REVISION,sourceRevision=SOURCE_REVISION,
               model=name,expectedIds=expected,full=limit is None,unassigned=len(tests)-len(eligible))
    save_json(root/"job.json",job)
    return {"status":"awaiting_scores","count":len(selected),"kind":"full" if limit is None else "sample",
            "generationHash":job["generationHash"],"weightsRevision":WEIGHTS_REVISION}


def score_package(package, repo, weights, out):
    """在指定 GPU 机器校验权重后调用作者原脚本，输出可在同源网页导入的逐题分数。"""
    import hashlib
    import os
    import shutil
    import subprocess
    import sys
    from tempfile import TemporaryDirectory
    repo,weights=Path(repo).resolve(),Path(weights).resolve()
    check_source(repo)
    content=json.loads(Path(package).read_text(encoding="utf-8"))
    job=content["job"]
    raw=content["generationUtf8"].encode("utf-8")
    if (job["weightsRevision"]!=WEIGHTS_REVISION or job.get("sourceRevision")!=SOURCE_REVISION
            or hashlib.sha256(raw).hexdigest()!=job["generationHash"]):
        raise ValueError("公开任务的生成哈希或权重版本不正确")
    metrics=json.loads((repo/"data/id2metric.jsonl").read_text(encoding="utf-8"))
    examples=json.loads(raw)
    expected={str(x["id"]):[v[0] for v in metrics[str(x["id"])]] for x in examples}
    if len(expected)!=len(examples) or expected!=job["expectedIds"] or (job["full"] and set(expected)!=set(metrics)):
        raise ValueError("生成题目与作者分配的指标或完整测试范围不匹配")
    pinned=json.loads(Path(__file__).with_name("characterrm-files.json").read_text(encoding="utf-8"))
    for filename,expected in pinned.items():
        if file_hash(weights/filename)!=expected:
            raise ValueError("CharacterRM 文件校验失败 "+filename)
    with TemporaryDirectory() as temp:
        wd=Path(temp)
        (wd/"results").mkdir()
        (wd/"data").mkdir()
        (wd/"BaichuanCharRM").mkdir()
        # 仅固定清单中的文件可成为评分进程的权重或 Python 模块。
        for filename in pinned:
            (wd/"BaichuanCharRM"/filename).symlink_to(weights/filename)
        for filename in ("character_profiles.json","id2metric.jsonl"):
            shutil.copyfile(repo/"data"/filename,wd/"data"/filename)
        (wd/"results/generation.jsonl").write_bytes(raw)
        env=dict(os.environ,PYTHONPATH=str(wd)+os.pathsep+os.environ.get("PYTHONPATH",""))
        for filename in ("transform_format.py","run_char_rm.py","compute_score.py"):
            subprocess.run([sys.executable,"-X","utf8",str(repo/filename)],cwd=wd,env=env,check=True)
        rows=json.loads((wd/"results/evaluation.jsonl").read_text(encoding="utf-8"))
        compact=[{"id":r["id"],"metric_en":r["metric_en"],r["metric_en"]:r[r["metric_en"]]} for r in rows]
        if metric_report(compact,expected_ids=job["expectedIds"],full=job["full"])["status"]!="complete":
            raise ValueError("官方评分器返回了不完整的批次")
        save_json(out,{"generationHash":job["generationHash"],"weightsRevision":WEIGHTS_REVISION,"rows":compact})
