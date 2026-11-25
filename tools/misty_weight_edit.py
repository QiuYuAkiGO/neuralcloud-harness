"""在 Qwen3.5-9B 副本上编辑两个文本层，并通过官方聊天模板比较普通回复。"""

import argparse
import hashlib
import math
import json
import random
import shutil
import struct
import tempfile
from pathlib import Path

import torch
import torch.nn.functional as F
from transformers import AutoTokenizer, Qwen3_5ForCausalLM


# ModelScope 上 Qwen/Qwen3.5-9B 的 e8885939 权重清单。Base 与后训练版架构相同。
OFFICIAL_INDEX = "26d3539b516be613f39563617cb9d33b3f83d401298125be392c80cefb8f7fe5"
SHARD_HASHES = {
    "model.safetensors-00001-of-00004.safetensors": "db6f444b43d318c92f360a13a25561a6a65b10c0631b8ed305a426dbaa6c380e",
    "model.safetensors-00002-of-00004.safetensors": "31c7d7e2dd5d207840b31cc59083c8f4c4718959149e0358c0364052bb9a0330",
    "model.safetensors-00003-of-00004.safetensors": "7ec36ba3a4176a44c3c0876ad80c56a2f70c84bf008d82e9501df642f17dadec",
    "model.safetensors-00004-of-00004.safetensors": "b62b0c4cd7e44edee103ee8f4fe225f246d5e768e07bfd5f25b63a8aa1fdd0c6",
}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("须为正整数")
    return number


def positive_rate(value):
    rate = float(value)
    if not math.isfinite(rate) or rate <= 0:
        raise argparse.ArgumentTypeError("学习率须为有限正数")
    return rate


def load_text(source, device):
    # 文本推理直接读取本地官方检查点。保存时复制完整多模态快照，保留视觉和 MTP 张量。
    tokenizer = AutoTokenizer.from_pretrained(source, local_files_only=True)
    model = Qwen3_5ForCausalLM.from_pretrained(
        source, dtype=torch.bfloat16, device_map={"": device}, local_files_only=True
    )
    return tokenizer, model.eval().requires_grad_(False)


def prompt_ids(tokenizer, messages):
    # 每个样本从新的用户会话进入。关闭思考是官方模板参数，两组使用相同设置。
    if not messages or messages[0]["role"] != "user" or messages[-1]["role"] != "user":
        raise ValueError("messages 须由用户消息开始并结束")
    for index, message in enumerate(messages):
        if message["role"] != ("user" if index % 2 == 0 else "assistant"):
            raise ValueError("messages 仅接受交替出现的 user 和 assistant")
    return tokenizer.apply_chat_template(
        messages, tokenize=True, return_dict=False, add_generation_prompt=True, enable_thinking=False
    )


def generate_cases(tokenizer, model, rows, args):
    """仅把留出题的消息送入 generate，逐条记录可见回复、长度与停止条件。"""
    if not rows:
        raise ValueError("cases 至少需要一道留出题")
    result = []
    for start in range(0, len(rows), args.batch_size):
        batch = rows[start : start + args.batch_size]
        prompts = [prompt_ids(tokenizer, row["messages"]) for row in batch]
        width = max(map(len, prompts))
        tokens = torch.full((len(prompts), width), tokenizer.pad_token_id, dtype=torch.long)
        mask = torch.zeros_like(tokens)
        for i, ids in enumerate(prompts):
            tokens[i, -len(ids) :] = torch.tensor(ids)
            mask[i, -len(ids) :] = 1
        stop = tokenizer.convert_tokens_to_ids("<|im_end|>")
        with torch.inference_mode():
            generated = model.generate(
                input_ids=tokens.to(args.device), attention_mask=mask.to(args.device),
                do_sample=False, max_new_tokens=args.max_new_tokens,
                eos_token_id=stop, pad_token_id=tokenizer.pad_token_id,
                use_cache=True,
            )
        for row, output in zip(batch, generated[:, width:].cpu().tolist()):
            ended = stop in output
            ids = output[: output.index(stop)] if ended else output
            result.append({
                "id": row["id"], "reply": tokenizer.decode(ids, skip_special_tokens=False),
                "tokens": len(ids), "stop": "im_end" if ended else "limit",
            })
        print(f"已生成 {len(result)}/{len(rows)} 条", flush=True)
    return result


def evaluate(args):
    """同一检查点加载一次，各套题独立分批，以便和各自的基线逐条对照。"""
    if len(args.cases) != len(args.output) or len(set(args.output)) != len(args.output):
        raise ValueError("每份 cases 需要各自唯一的 output 路径")
    tokenizer, model = load_text(args.model, args.device)
    for cases, output in zip(args.cases, args.output, strict=True):
        write_json(output, generate_cases(tokenizer, model, read_json(cases), args))


def training_rows(tokenizer, rows):
    """对齐普通聊天的回复位置，结束标记也参与学习，以便模型能自然收句。"""
    result = []
    end = tokenizer.convert_tokens_to_ids("<|im_end|>")
    for row in rows:
        prompt = prompt_ids(tokenizer, row["messages"])
        reply = tokenizer.encode(row["reply"], add_special_tokens=False)
        if not reply or any(t in tokenizer.all_special_ids for t in reply):
            raise ValueError("reply 必须是非空的普通对话文本")
        if row["kind"] not in {"identity", "role", "voice", "retain"}:
            raise ValueError("未知的训练样本 kind")
        result.append((prompt + reply, [-100] * (len(prompt) - 1) + reply + [end], row["kind"]))
    return result


def cache_prefix(model, samples, batch_size, pad):
    """冻结前缀只需计算一次。取倒数第二层的残差、激活和末层的实际掩码与位置。"""
    penultimate, last = model.model.layers[-2:]
    current = {}

    def save_residual(_module, inputs):
        current["residual"] = inputs[0].detach()

    def save_features(_module, inputs):
        current["features"] = inputs[0].detach()

    def save_attention(_module, _inputs, kwargs):
        current["positions"] = kwargs["position_embeddings"]
        current["position_ids"] = kwargs["position_ids"]
        current["mask"] = kwargs["attention_mask"]

    hooks = [
        penultimate.post_attention_layernorm.register_forward_pre_hook(save_residual),
        penultimate.mlp.down_proj.register_forward_pre_hook(save_features),
        last.register_forward_pre_hook(save_attention, with_kwargs=True),
    ]
    batches = []
    try:
        with torch.no_grad():
            for start in range(0, len(samples), batch_size):
                batch = samples[start : start + batch_size]
                width = max(len(s[0]) for s in batch)
                ids = torch.full((len(batch), width), pad, dtype=torch.long)
                labels = torch.full_like(ids, -100)
                mask = torch.zeros_like(ids)
                keep = torch.zeros_like(ids, dtype=torch.bool)
                for i, (tokens, targets, kind) in enumerate(batch):
                    size = len(tokens)
                    ids[i, :size] = torch.tensor(tokens)
                    labels[i, :size] = torch.tensor(targets)
                    mask[i, :size] = 1
                    # 保持普通输入的表示，保留类样本还约束整段回复的参考表示。
                    keep[i, :size] = True if kind == "retain" else labels[i, :size].eq(-100)
                current = {}
                reference = model.model(input_ids=ids, attention_mask=mask, use_cache=False)
                batches.append(dict(current, labels=labels, keep=keep, reference=reference.last_hidden_state))
                print(f"已缓存 {start + len(batch)}/{len(samples)} 条训练会话", flush=True)
    finally:
        for hook in hooks:
            hook.remove()
    return batches


def fit_tail(model, batches, device, epochs, learning_rate, rng):
    """直接优化第 30、31 层的 FFN 输出矩阵，交叉熵按回复平均，保留项约束原模型表示。"""
    penultimate, last = model.model.layers[-2:]
    projections = [penultimate.mlp.down_proj, last.mlp.down_proj]
    last.to(device)
    norm = model.model.norm.to(device)
    head = model.lm_head.to(device)
    for layer in projections:
        layer.to(device=device, dtype=torch.float32).requires_grad_(True)
    weights = [layer.weight for layer in projections]
    originals = [w.detach().clone() for w in weights]
    optimizer = torch.optim.AdamW(weights, lr=learning_rate, weight_decay=0, foreach=False)
    history = []
    for epoch in range(epochs):
        rng.shuffle(batches)
        total = 0.0
        for b in batches:
            labels = b["labels"].to(device)
            active = labels.ne(-100)
            keep = b["keep"].to(device)
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device, dtype=torch.bfloat16):
                hidden = b["residual"].to(device) + projections[0](b["features"].to(device))
                state = last(
                    hidden, position_embeddings=tuple(p.to(device) for p in b["positions"]),
                    position_ids=b["position_ids"].to(device),
                    attention_mask=None if b["mask"] is None else b["mask"].to(device),
                    use_cache=False,
                )
                state = norm(state)
                logits = head(state[active])
            losses = F.cross_entropy(logits.float(), labels[active], reduction="none")
            row_numbers = active.nonzero(as_tuple=True)[0]
            ce = (losses / active.sum(dim=1)[row_numbers]).sum() / labels.shape[0]
            reference = b["reference"].to(device)[keep].float()
            preservation = (state[keep].float() - reference).square().mean() / reference.square().mean()
            loss = ce + 3.0 * preservation
            if not torch.isfinite(loss):
                raise FloatingPointError("权重编辑损失不是有限数")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(weights, 1.0)
            optimizer.step()
            total += ce.detach().item()
        # 限制局部改动的相对范数，避免仅靠放大权重拟合小语料。
        relative = []
        with torch.no_grad():
            for weight, initial in zip(weights, originals):
                delta = weight - initial
                ratio = (delta.norm() / initial.norm()).item()
                if ratio > 0.05:
                    weight.copy_(initial + delta * (0.05 / ratio))
                relative.append(min(ratio, 0.05))
        record = {"epoch": epoch + 1, "response_ce": total / len(batches), "relative_delta": relative}
        history.append(record)
        print(json.dumps(record), flush=True)
    return [w.detach().to(device="cpu", dtype=torch.bfloat16) for w in weights], history


def copy_official_file(source, destination):
    """在复制分片的同一遍读取中核对源权重，以免在另一检查点上误报编辑成功。"""
    name = Path(source).name
    if name not in SHARD_HASHES:
        return shutil.copy2(source, destination)
    digest = hashlib.sha256()
    with open(source, "rb") as original, open(destination, "xb") as copy:
        while chunk := original.read(8 * 1024 ** 2):
            digest.update(chunk)
            copy.write(chunk)
    if digest.hexdigest() != SHARD_HASHES[name]:
        raise ValueError(f"{name} 的 SHA-256 与官方后训练权重不符")
    return destination


def save_copy(source, output, tensors, layers=(30, 31)):
    """复制原快照后只写目标 BF16 数据区。读回确认，最后一次性公布输出目录。"""
    weight_map = read_json(source / "model.safetensors.index.json")["weight_map"]
    with tempfile.TemporaryDirectory(dir=output.parent, prefix=".misty-edit-") as staging:
        staged = Path(staging) / "checkpoint"
        shutil.copytree(source, staged, copy_function=copy_official_file)
        for layer, tensor in zip(layers, tensors, strict=True):
            name = f"model.language_model.layers.{layer}.mlp.down_proj.weight"
            with (staged / weight_map[name]).open("r+b") as file:
                header_size = struct.unpack("<Q", file.read(8))[0]
                entry = json.loads(file.read(header_size))[name]
                start, end = entry["data_offsets"]
                raw = tensor.contiguous().view(torch.uint8).numpy().tobytes()
                if entry["dtype"] != "BF16" or list(tensor.shape) != entry["shape"] or len(raw) != end - start:
                    raise ValueError(f"{name} 的 dtype、形状或数据区长度不符合检查点")
                offset = 8 + header_size + start
                file.seek(offset)
                file.write(raw)
                file.seek(offset)
                if file.read(len(raw)) != raw:
                    raise IOError(f"{name} 写入后校验失败")
        staged.rename(output)


def edit_paths(model_path, destination):
    """两种编辑法均从同版官方权重出发，且只能发布到独立的新目录。"""
    source, output = model_path.resolve(), destination.resolve()
    if output.exists() or source in output.parents:
        raise ValueError("output 必须是源模型目录之外的新路径")
    if hashlib.sha256((source / "model.safetensors.index.json").read_bytes()).hexdigest() != OFFICIAL_INDEX:
        raise ValueError("model 的索引与 ModelScope 官方 Qwen3.5-9B 后训练版不符")
    return source, output


def edit(args):
    source, output = edit_paths(args.model, args.output)
    rows = read_json(args.corpus)
    if not rows:
        raise ValueError("corpus 至少需要一条训练会话")
    tokenizer, model = load_text(source, "cpu")
    if model.config.model_type != "qwen3_5_text" or len(model.model.layers) != 32:
        raise ValueError("编辑入口需要 32 层的 Qwen3.5-9B 文本主干")
    rng = random.Random(args.seed)
    torch.manual_seed(args.seed)
    samples = training_rows(tokenizer, rows)
    rng.shuffle(samples)
    batches = cache_prefix(model, samples, args.batch_size, tokenizer.pad_token_id)
    matrices, history = fit_tail(model, batches, args.device, args.epochs, args.lr, rng)
    del batches, model
    if args.device == "cuda":
        torch.cuda.empty_cache()
    print("正在写入独立检查点", flush=True)
    save_copy(source, output, matrices)
    write_json(args.log, {
        "examples": len(samples), "seed": args.seed, "batch_size": args.batch_size,
        "learning_rate": args.lr, "epochs": args.epochs, "layers": [30, 31], "history": history,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threads", type=positive_int, default=12)
    commands = parser.add_subparsers(dest="command", required=True)
    ev = commands.add_parser("evaluate")
    ev.add_argument("--model", type=Path, required=True)
    ev.add_argument("--cases", type=Path, nargs="+", required=True)
    ev.add_argument("--output", type=Path, nargs="+", required=True)
    ev.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    ev.add_argument("--batch-size", type=positive_int, default=6)
    ev.add_argument("--max-new-tokens", type=positive_int, default=224)
    ev.set_defaults(run=evaluate)
    ed = commands.add_parser("edit")
    ed.add_argument("--model", type=Path, required=True)
    ed.add_argument("--corpus", type=Path, required=True)
    ed.add_argument("--output", type=Path, required=True)
    ed.add_argument("--log", type=Path, required=True)
    ed.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    ed.add_argument("--seed", type=int, default=20261125)
    ed.add_argument("--batch-size", type=positive_int, default=4)
    ed.add_argument("--epochs", type=positive_int, default=7)
    ed.add_argument("--lr", type=positive_rate, default=0.00012)
    ed.set_defaults(run=edit)
    args = parser.parse_args()
    torch.set_num_threads(args.threads)
    args.run(args)


if __name__ == "__main__":
    main()
