"""将训练增量舍入为标准 BF16 权重，使用同一合并计算选模并发布完整多模态快照。"""

import argparse
import hashlib
import json
import shutil
import struct
import tempfile
from pathlib import Path

import torch
from safetensors import safe_open

from misty_weight_edit import (edit_paths, generate_cases, load_text, positive_int,
                               read_json, write_json)
from train_misty_persona import SCALE, parent_shards, sha_file, target_paths


ROW_BLOCK = 512
BLOCK_BYTES = 8 * 1024 ** 2
PREFIX = "model.language_model."


def read_factors(path):
    """训练器与合并器按实际层路径交接。拒绝缺失、多余或尺度不符的因子。"""
    names = [f"layers.{i}.{part}" for i, part in target_paths()]
    by_weight = {}
    with safe_open(path, framework="pt", device="cpu") as file:
        metadata = file.metadata()
        rank = int(metadata["rank"])
        if float(metadata["scale"]) != SCALE or set(file.keys()) != {f"{n}.{k}" for n in names for k in ("a", "b")}:
            raise ValueError("候选文件的尺度或目标投影集合不符")
        for name in names:
            a, b = file.get_tensor(name + ".a"), file.get_tensor(name + ".b")
            if a.shape[0] != rank or b.shape[1] != rank or a.dtype != torch.float32 or b.dtype != torch.float32:
                raise ValueError(f"{name} 的因子不满足 rank 和 FP32 契约")
            by_weight[PREFIX + name + ".weight"] = (a, b)
    return by_weight


def merge_block(base, a, b):
    """开发推理和最终落盘均先按 FP32 相加、再一次舍入，块的行边界一致。"""
    return torch.addmm(base.float(), b, a, alpha=SCALE).to(torch.bfloat16)


@torch.no_grad()
def development(args):
    """选择阶段读父模型的原始矩阵重建每个候选，避免在上一个候选上重复叠加。"""
    if len(args.factors) != len(args.output) or len(set(args.output)) != len(args.output):
        raise ValueError("每个候选需要独立的输出路径")
    tokenizer, model = load_text(args.model, "cpu")
    index = read_json(args.model / "model.safetensors.index.json")["weight_map"]
    rows = read_json(args.cases)
    for factor_file, destination in zip(args.factors, args.output, strict=True):
        weights = read_factors(factor_file)
        for shard in sorted({index[k] for k in weights}):
            with safe_open(args.model / shard, framework="pt", device="cpu") as file:
                for name, (a, b) in weights.items():
                    if index[name] != shard:
                        continue
                    source = file.get_tensor(name)
                    projected = model.model.get_submodule(name.removeprefix(PREFIX).removesuffix(".weight"))
                    for row in range(0, len(b), ROW_BLOCK):
                        sl = slice(row, row + ROW_BLOCK)
                        projected.weight[sl].copy_(merge_block(source[sl], a, b[sl]))
        write_json(destination, generate_cases(tokenizer, model, rows, args))
        print(f"开发答卷已写入 {destination}", flush=True)


def write_shard(source, target, weights):
    """按原分片顺序单次流式复制，只重建白名单矩阵的数据区。保留头、空隙与其余张量。"""
    before, after, preserved = hashlib.sha256(), hashlib.sha256(), hashlib.sha256()
    details = {}
    with source.open("rb") as src, target.open("xb") as dst:
        def read_bytes(size):
            raw = src.read(size)
            if len(raw) != size:
                raise EOFError(f"{source.name} 在 {src.tell()} 意外结束")
            before.update(raw)
            return raw

        def put(raw):
            dst.write(raw)
            after.update(raw)

        def copy_until(end):
            while src.tell() < end:
                raw = read_bytes(min(BLOCK_BYTES, end - src.tell()))
                preserved.update(raw)
                put(raw)

        size_bytes = read_bytes(8)
        header = read_bytes(struct.unpack("<Q", size_bytes)[0])
        put(size_bytes + header)
        preserved.update(size_bytes + header)
        data_start = src.tell()
        entries = json.loads(header)
        for name in sorted(weights, key=lambda key: entries[key]["data_offsets"][0]):
            a, b = weights[name]
            info = entries[name]
            start, end = info["data_offsets"]
            nrow, ncol = b.shape[0], a.shape[1]
            if info["dtype"] != "BF16" or info["shape"] != [nrow, ncol] or end - start != nrow * ncol * 2:
                raise ValueError(f"{name} 与因子不满足 BF16 数据区契约")
            copy_until(data_start + start)
            changed = 0
            original_sq = delta_sq = 0.0
            for row in range(0, nrow, ROW_BLOCK):
                n = min(nrow - row, ROW_BLOCK)
                block = torch.frombuffer(bytearray(read_bytes(n * ncol * 2)), dtype=torch.bfloat16).view(n, ncol)
                merged = merge_block(block, a, b[row:row + n])
                changed += (merged.view(torch.int16) != block.view(torch.int16)).sum().item()
                original_sq += block.float().square().sum().item()
                delta_sq += (merged.float() - block.float()).square().sum().item()
                put(merged.contiguous().view(torch.uint8).numpy().tobytes())
            if changed == 0:
                raise ValueError(f"{name} 没有产生可保存的 BF16 修改")
            details[name] = dict(elements=nrow * ncol, changed_elements=changed,
                                 relative_frobenius=(delta_sq / original_sq) ** 0.5)
        copy_until(source.stat().st_size)
    return before.hexdigest(), after.hexdigest(), preserved.hexdigest(), details


def publish(args):
    """全分片验证第二版输入，读回成品验证 SHA-256，然后原子公布新目录。"""
    source, destination = edit_paths(args.model, args.output)
    weights = read_factors(args.factors)
    index = read_json(source / "model.safetensors.index.json")["weight_map"]
    expected = parent_shards()
    report = dict(factors_sha256=sha_file(args.factors), indexed_tensors=len(index), files={}, changed_tensors={})
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix=".misty-v3-staging-") as work:
        stage = Path(work) / "checkpoint"
        stage.mkdir()
        for path in sorted(source.iterdir()):
            name = path.name
            output = stage / name
            if name in expected:
                local = {k: v for k, v in weights.items() if index[k] == name}
                old, new, stable, changes = write_shard(path, output, local)
                if old != expected[name]:
                    raise ValueError(f"{name} 的源哈希与已核验的第二版不符")
                item = dict(source_sha256=old, output_sha256=new, unchanged_region_sha256=stable)
                report["changed_tensors"].update(changes)
            else:
                shutil.copy2(path, output)
                digest = sha_file(path)
                item = dict(source_sha256=digest, output_sha256=digest)
            if output.stat().st_size != path.stat().st_size or sha_file(output) != item["output_sha256"]:
                raise IOError(f"{name} 写入后大小或 SHA-256 复核失败")
            report["files"][name] = dict(size=output.stat().st_size, **item)
            print(f"已读回校验 {name}", flush=True)
        if set(report["changed_tensors"]) != weights.keys() or not expected.keys() <= report["files"].keys():
            raise ValueError("分片或应有的权重修改不完整")
        stage.rename(destination)
    write_json(args.audit, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threads", type=positive_int, default=12)
    commands = parser.add_subparsers(required=True)
    dev = commands.add_parser("development")
    dev.add_argument("--model", type=Path, required=True)
    dev.add_argument("--factors", type=Path, nargs="+", required=True)
    dev.add_argument("--output", type=Path, nargs="+", required=True)
    dev.add_argument("--cases", type=Path, required=True)
    dev.add_argument("--batch-size", type=positive_int, default=6)
    dev.add_argument("--max-new-tokens", type=positive_int, default=224)
    dev.set_defaults(run=development, device="cpu")
    out = commands.add_parser("publish")
    out.add_argument("--model", type=Path, required=True)
    out.add_argument("--factors", type=Path, required=True)
    out.add_argument("--output", type=Path, required=True)
    out.add_argument("--audit", type=Path, required=True)
    out.set_defaults(run=publish)
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    args.run(args)


if __name__ == "__main__":
    main()
