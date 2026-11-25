"""在小雾第二版的上半段文本网络上学习对话偏好，输出可合入 BF16 的低秩增量。"""

import argparse
import hashlib
import math
import random
from collections import Counter
from pathlib import Path

import torch
import torch.nn.functional as F
from safetensors.torch import save_file
from torch.utils.checkpoint import checkpoint

from distill_misty_weights import pad_right
from misty_weight_edit import (OFFICIAL_INDEX, SHARD_HASHES, load_text, positive_int, positive_rate,
                               prompt_ids, read_json, write_json)


FIRST_LAYER = 16
LAYER_COUNT = 32
SCALE = 2.0
KINDS = {"identity", "role", "voice", "judgment", "retain"}
PARENT_EVIDENCE = Path(__file__).resolve().parents[1] / "docs/harness/evidence/misty-weight-edit-20261125/v2.json"


def sha_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(8 * 1024 ** 2):
            digest.update(block)
    return digest.hexdigest()


def parent_shards():
    """第二版在官方后训练版的三个分片里做过修改，用已核验的输出哈希辨认实际父权重。"""
    expected = dict(SHARD_HASHES)
    for name, item in read_json(PARENT_EVIDENCE)["checkpoint"]["changed_shards"].items():
        expected[name] = item["edited_sha256"]
    return expected


def verify_parent(source):
    expected = {**parent_shards(), "model.safetensors.index.json": OFFICIAL_INDEX}
    for name, digest in expected.items():
        if sha_file(source / name) != digest:
            raise ValueError(f"{name} 与指定的第二版父模型不符")


def target_paths():
    """覆盖后十六层的内容变换与两类注意力的读写投影，路径沿用原模型的命名。"""
    for i in range(FIRST_LAYER, LAYER_COUNT):
        attn = ("self_attn.q_proj", "self_attn.v_proj", "self_attn.o_proj") if i % 4 == 3 else (
            "linear_attn.in_proj_qkv", "linear_attn.out_proj"
        )
        for part in (*attn, "mlp.up_proj", "mlp.down_proj"):
            yield i, part


class LowRank(torch.nn.Module):
    """原投影冻结，优化器只更新两个 FP32 因子。部署时按相同尺度合入普通矩阵。"""

    def __init__(self, base, rank):
        super().__init__()
        self.base = base
        self.a = torch.nn.Parameter(torch.empty(rank, base.in_features, device=base.weight.device))
        self.b = torch.nn.Parameter(torch.zeros(base.out_features, rank, device=base.weight.device))
        torch.nn.init.kaiming_uniform_(self.a, a=math.sqrt(5))

    def forward(self, x):
        inner = F.linear(x, self.a.to(x.dtype))
        return self.base(x) + SCALE * F.linear(inner, self.b.to(x.dtype))


def tokenize_groups(tokenizer, rows):
    """一条会话的两种续答共享同一提示。只监督回复词元及其真实结束符。"""
    groups = []
    stop = tokenizer.convert_tokens_to_ids("<|im_end|>")
    specials = set(tokenizer.all_special_ids)
    for row in rows:
        if row["kind"] not in KINDS:
            raise ValueError(f"不支持的语料 kind {row['kind']}")
        prompt = prompt_ids(tokenizer, row["messages"])
        answers = [row["chosen"], row["rejected"]] if "chosen" in row else [row["reply"]]
        encoded = [tokenizer.encode(text, add_special_tokens=False) for text in answers]
        if any(not ids or specials.intersection(ids) for ids in encoded) or len(encoded) == 2 and encoded[0] == encoded[1]:
            raise ValueError("续答须是非空普通文本，偏好对须有两个不同的续答")
        groups.append([(prompt + ids, [-100] * (len(prompt) - 1) + ids + [stop]) for ids in encoded])
    return groups


def sequence_logp(head, states, labels):
    """按回复长度平均对数似然，每条回复只占一份权重，长短均可成为优选。"""
    active = labels.ne(-100)
    logits = head(states[active]).float()
    losses = F.cross_entropy(logits, labels[active], reduction="none")
    rows = active.nonzero(as_tuple=True)[0]
    totals = losses.new_zeros(labels.shape[0]).scatter_add(0, rows, -losses)
    return totals / active.sum(1)


@torch.no_grad()
def cache_batches(model, tokenizer, groups, batch_size, token_budget):
    """从正常前向取得冻结前缀、官方掩码和第二版的参考概率，后续轮次直接重放后半段。"""
    current = {}

    def capture(_layer, inputs, kwargs):
        current.update(hidden=inputs[0], rotary=kwargs["position_embeddings"],
                       positions=kwargs["position_ids"], linear_attention=kwargs["attention_mask"])

    def capture_full(_layer, _inputs, kwargs):
        current["full_attention"] = kwargs["attention_mask"]

    hooks = [
        model.model.layers[FIRST_LAYER].register_forward_pre_hook(capture, with_kwargs=True),
        model.model.layers[FIRST_LAYER + 3].register_forward_pre_hook(capture_full, with_kwargs=True),
    ]
    batches = []
    try:
        start = 0
        while start < len(groups):
            block = [groups[start]]
            for group in groups[start + 1:start + batch_size]:
                proposed = [*block, group]
                width = max(len(x[0]) for g in proposed for x in g)
                if width * sum(map(len, proposed)) > token_budget:
                    break
                block.append(group)
            seqs = [seq for group in block for seq in group]
            ids, mask = pad_right([item[0] for item in seqs], tokenizer.pad_token_id)
            labels, _ = pad_right([item[1] for item in seqs], -100)
            state = model.model(input_ids=ids, attention_mask=mask, use_cache=False).last_hidden_state
            preferred, paired, rejected = [], [], []
            offset = 0
            for group in block:
                preferred.append(offset)
                if len(group) == 2:
                    paired.append(offset)
                    rejected.append(offset + 1)
                offset += len(group)
            reference = sequence_logp(model.lm_head, state, labels)
            batches.append(dict(current, labels=labels, preferred=preferred, paired=paired, rejected=rejected,
                                reference=reference[paired] - reference[rejected]))
            if start == 0 or len(batches) % 10 == 0 or start + len(block) == len(groups):
                print(f"缓存 {start + len(block)}/{len(groups)} 条会话", flush=True)
            start += len(block)
    finally:
        for hook in hooks:
            hook.remove()
    return batches


def fit(model, batches, rng, args):
    """跨 CPU/GPU 的同一梯度链覆盖所有编辑层，完整词表输出留在 CPU。"""
    if args.device == "cuda":
        model.model.layers[args.gpu_from:].to("cuda")
    edits = {}
    for i, part in target_paths():
        layer = model.model.layers[i]
        edit = LowRank(layer.get_submodule(part), args.rank)
        layer.set_submodule(part, edit)
        edits[f"layers.{i}.{part}"] = edit
    parameters = [p for edit in edits.values() for p in (edit.a, edit.b)]
    optimizer = torch.optim.AdamW(parameters, lr=args.lr, weight_decay=0.01, foreach=False)
    steps = len(batches) * args.epochs
    warmup = max(1, round(steps * 0.05))
    history = []
    for epoch in range(1, args.epochs + 1):
        rng.shuffle(batches)
        ce_sum = pref_sum = margins_sum = 0.0
        count = pairs = 0
        for j, batch in enumerate(batches):
            step = (epoch - 1) * len(batches) + j + 1
            progress = max(0, step - warmup) / max(1, steps - warmup)
            multiplier = step / warmup if step < warmup else 0.35 + 0.325 * (1 + math.cos(math.pi * progress))
            optimizer.param_groups[0]["lr"] = args.lr * multiplier
            optimizer.zero_grad(set_to_none=True)
            hidden = batch["hidden"]
            for i in range(FIRST_LAYER, LAYER_COUNT):
                layer = model.model.layers[i]
                device = layer.input_layernorm.weight.device
                mask = batch[layer.block_type]
                hidden = checkpoint(
                    layer, hidden.to(device), position_embeddings=tuple(v.to(device) for v in batch["rotary"]),
                    position_ids=batch["positions"].to(device), attention_mask=None if mask is None else mask.to(device),
                    use_cache=False, use_reentrant=False, preserve_rng_state=False,
                )
            logp = sequence_logp(model.lm_head, model.model.norm(hidden.to("cpu")), batch["labels"])
            ce = -logp[batch["preferred"]].mean()
            loss = ce
            n = len(batch["preferred"])
            ce_sum += ce.detach().item() * n
            count += n
            if batch["paired"]:
                margin = logp[batch["paired"]] - logp[batch["rejected"]] - batch["reference"]
                preference = -F.logsigmoid(args.beta * margin).mean()
                loss = ce + args.preference_weight * preference
                k = len(margin)
                pref_sum += preference.detach().item() * k
                margins_sum += margin.detach().sum().item()
                pairs += k
            if not torch.isfinite(loss):
                raise FloatingPointError(f"第 {step} 步损失非有限数")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(parameters, 1.0, error_if_nonfinite=True)
            optimizer.step()
            if j == 0 or (j + 1) % 20 == 0:
                print(f"轮 {epoch} 批 {j + 1}/{len(batches)} 回复 CE {ce_sum/count:.4f}", flush=True)
        record = dict(epoch=epoch, response_ce=ce_sum/count, pair_loss=pref_sum/pairs,
                      reference_relative_margin=margins_sum/pairs)
        history.append(record)
        print(record, flush=True)
        if epoch in args.save_epochs:
            state = {f"{name}.{key}": getattr(edit, key).detach().cpu().contiguous()
                     for name, edit in edits.items() for key in ("a", "b")}
            save_file(state, args.work / f"epoch-{epoch}.safetensors",
                      metadata={"scale": str(SCALE), "rank": str(args.rank), "epoch": str(epoch)})
    return history, sum(p.numel() for p in parameters)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, nargs="+", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--cache", type=Path, help="复用同一输入权重和语料的冻结前向结果")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    parser.add_argument("--gpu-from", type=int, choices=range(FIRST_LAYER, LAYER_COUNT), default=18)
    parser.add_argument("--threads", type=positive_int, default=12)
    parser.add_argument("--epochs", type=positive_int, default=4)
    parser.add_argument("--save-epochs", type=positive_int, nargs="+", default=[2, 4])
    parser.add_argument("--rank", type=positive_int, default=24)
    parser.add_argument("--lr", type=positive_rate, default=0.00035)
    parser.add_argument("--beta", type=positive_rate, default=1.5)
    parser.add_argument("--preference-weight", type=positive_rate, default=0.45)
    parser.add_argument("--batch-size", type=positive_int, default=3)
    parser.add_argument("--batch-tokens", type=positive_int, default=480)
    parser.add_argument("--seed", type=int, default=20261126)
    args = parser.parse_args()
    if max(args.save_epochs) > args.epochs:
        parser.error("save-epochs 须位于训练轮数内")
    args.work.mkdir(parents=True, exist_ok=True)
    if any((args.work / f"epoch-{i}.safetensors").exists() for i in args.save_epochs):
        parser.error("work 已含同轮候选，请使用新目录")
    rows = [r for path in args.corpus for r in read_json(path)]
    if not any("chosen" in r for r in rows):
        parser.error("语料至少需要一对偏好续答")
    torch.set_num_threads(args.threads)
    torch.manual_seed(args.seed)
    rng = random.Random(args.seed)
    verify_parent(args.model)
    tokenizer, model = load_text(args.model, "cpu")
    groups = tokenize_groups(tokenizer, rows)
    rng.shuffle(groups)
    # 按最长序列分桶减少填充，轮次之间打乱批序避免长度顺序成为训练信号。
    groups.sort(key=lambda g: max(len(item[0]) for item in g) // 16)
    corpora = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in args.corpus}
    cache_key = dict(corpora=corpora, model=str(args.model.resolve()), seed=args.seed, batch=args.batch_size,
                     tokens=args.batch_tokens, first_layer=FIRST_LAYER,
                     shards=[(p.name, p.stat().st_size, p.stat().st_mtime_ns)
                             for p in sorted(args.model.glob("*.safetensors"))])
    if args.cache and args.cache.exists():
        cached = torch.load(args.cache, weights_only=True)
        if cached["key"] != cache_key:
            raise ValueError("cache 与指定模型、语料或分批条件不符")
        batches = cached["batches"]
    else:
        batches = cache_batches(model, tokenizer, groups, args.batch_size, args.batch_tokens)
        if args.cache:
            temporary = args.cache.with_suffix(".partial")
            torch.save(dict(key=cache_key, batches=batches), temporary)
            temporary.replace(args.cache)
    history, size = fit(model, batches, rng, args)
    write_json(args.work / "training.json", dict(
        seed=args.seed, rank=args.rank, scale=SCALE, edited_matrices=len(list(target_paths())),
        trainable_parameters=size, epochs=args.epochs, saved_epochs=args.save_epochs, batch_size=args.batch_size,
        learning_rate=args.lr, beta=args.beta, preference_weight=args.preference_weight, gpu_from=args.gpu_from,
        batch_tokens=args.batch_tokens, batches=len(batches),
        kinds=Counter(r["kind"] for r in rows), pairs=sum("chosen" in r for r in rows),
        corpora=corpora, history=history,
    ))


if __name__ == "__main__":
    main()
