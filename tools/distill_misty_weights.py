"""将有人设的原模型作为训练教师，把身份和表达蒸馏到无人设提示的 BF16 副本。"""

import argparse
import random
from pathlib import Path

import torch
import torch.nn.functional as F
from torch.utils.checkpoint import checkpoint

from misty_weight_edit import (
    edit_paths, load_text, positive_int, positive_rate, read_json, save_copy,
    training_rows, write_json,
)


LAYERS = (24, 27, 30)
# 这段上下文只提供给冻结的训练教师。被编辑的模型通过普通无系统消息的入口学习。
TEACHER = (
    "你叫小雾，英文名字是 Misty。把自己的名字、用户和其他人的名字分清。代写时用委托人的视角和署名。"
    "平时轻柔、自然、偏口语，接住对方刚说的具体细节，熟悉时也可以小小地打趣。"
    "用户有明确任务时完整回答，按要求逐项给齐名字、内容和格式。没有依据的经历不要编。"
)


class SelectedColumns(torch.nn.Module):
    """训练原矩阵的一组列。冻结列照常参与前向，训练结束把增量合入官方张量。"""

    def __init__(self, linear, layer):
        super().__init__()
        self.linear = linear
        indices = torch.arange(layer % 8, linear.in_features, 8, device=linear.weight.device)
        self.register_buffer("columns", indices)
        self.delta = torch.nn.Parameter(torch.zeros(
            linear.out_features, len(indices), device=linear.weight.device, dtype=torch.float32
        ))
        self.limit = linear.weight.float().norm().detach() * 0.03

    def forward(self, x):
        return self.linear(x) + F.linear(x.index_select(-1, self.columns), self.delta.to(x.dtype))

    @torch.no_grad()
    def project(self):
        self.delta.mul_((self.limit / self.delta.norm().clamp_min(1e-12)).clamp(max=1.0))

    @torch.no_grad()
    def merged(self):
        weight = self.linear.weight.float().clone()
        weight[:, self.columns] += self.delta
        return weight.to(device="cpu", dtype=torch.bfloat16)


def pad_right(sequences, pad):
    """批内右填充用于教师对齐和训练，注意力掩码仅保留实际词元。"""
    ids = torch.nn.utils.rnn.pad_sequence(
        [torch.tensor(s, dtype=torch.long) for s in sequences], batch_first=True, padding_value=pad
    )
    lengths = torch.tensor([len(s) for s in sequences])
    return ids, (torch.arange(ids.shape[1])[None, :] < lengths[:, None]).long()


def prepare(tokenizer, rows):
    """同一真实回复前缀分别交给教师和学生，记录监督的起点以对齐词元分布。"""
    student = training_rows(tokenizer, rows)
    samples = []
    for row, (ids, labels, kind) in zip(rows, student, strict=True):
        first = next(i for i, target in enumerate(labels) if target != -100)
        if kind == "retain":
            teacher_ids, teacher_start = ids, first
        else:
            messages = [{"role": "system", "content": TEACHER}, *row["messages"]]
            prompt = tokenizer.apply_chat_template(
                messages, tokenize=True, return_dict=False, add_generation_prompt=True, enable_thinking=False
            )
            teacher_ids = prompt + ids[first + 1 :]
            teacher_start = len(prompt) - 1
        samples.append((ids, labels, teacher_ids, teacher_start))
    return samples


def cache_examples(model, samples, batch_size, pad):
    """缓存学生第 24 层入口及实际掩码，并取冻结教师在相同作答位置的最终表示。"""
    current = {}

    def capture_input(_module, args, kwargs):
        current.update(hidden=args[0], positions=kwargs["position_embeddings"],
                       position_ids=kwargs["position_ids"], linear_mask=kwargs["attention_mask"])

    def capture_mask(_module, _args, kwargs):
        current["full_mask"] = kwargs["attention_mask"]

    hooks = [
        model.model.layers[LAYERS[0]].register_forward_pre_hook(capture_input, with_kwargs=True),
        model.model.layers[27].register_forward_pre_hook(capture_mask, with_kwargs=True),
    ]
    batches = []
    try:
        with torch.no_grad():
            for start in range(0, len(samples), batch_size):
                rows = samples[start : start + batch_size]
                ids, mask = pad_right([r[0] for r in rows], pad)
                model.model(input_ids=ids, attention_mask=mask, use_cache=False)
                saved = current.copy()
                labels, _ = pad_right([r[1] for r in rows], -100)
                teacher_ids, teacher_mask = pad_right([r[2] for r in rows], pad)
                teacher = model.model(input_ids=teacher_ids, attention_mask=teacher_mask, use_cache=False)
                # 每个回复含结束符。只缓存监督位置，学生从自身无提示的前缀继续计算。
                sizes = labels.ne(-100).sum(dim=1).tolist()
                saved["teacher"] = torch.cat([
                    teacher.last_hidden_state[i, r[3] : r[3] + size] for i, (r, size) in enumerate(zip(rows, sizes))
                ])
                saved["labels"] = labels
                # 每条回复均覆盖开头、结尾与中间位置的教师分布。
                points, offset = [], 0
                for size in sizes:
                    points.extend((torch.linspace(0, size - 1, min(6, size)).round().long() + offset).tolist())
                    offset += size
                saved["points"] = torch.tensor(points)
                batches.append(saved)
                if (start // batch_size) % 10 == 0 or start + len(rows) == len(samples):
                    print(f"教师和学生已对齐 {start + len(rows)}/{len(samples)} 条", flush=True)
    finally:
        for hook in hooks:
            hook.remove()
    return batches


def fit(model, batches, args, stop_id, rng):
    """用完整任务的监督答案与教师分布共同更新三个 FFN，逐步投影限制扰动。"""
    device = args.device
    tail = model.model.layers[LAYERS[0] :].to(device)
    head, norm = model.lm_head.to(device), model.model.norm.to(device)
    edits = []
    for index in LAYERS:
        mlp = model.model.layers[index].mlp
        mlp.down_proj = SelectedColumns(mlp.down_proj, index)
        edits.append(mlp.down_proj)
    parameters = [module.delta for module in edits]
    optimizer = torch.optim.Adam(parameters, lr=args.lr, foreach=False)
    history = []
    for epoch in range(args.epochs):
        rng.shuffle(batches)
        total_ce = total_kl = 0.0
        for batch in batches:
            hidden = batch["hidden"].to(device)
            rotary = tuple(p.to(device) for p in batch["positions"])
            pos = batch["position_ids"].to(device)
            masks = {
                kind: None if batch[key] is None else batch[key].to(device)
                for kind, key in (("linear_attention", "linear_mask"), ("full_attention", "full_mask"))
            }
            optimizer.zero_grad(set_to_none=True)
            for layer in tail:
                hidden = checkpoint(
                    layer, hidden, position_embeddings=rotary, position_ids=pos, attention_mask=masks[layer.block_type],
                    use_cache=False, use_reentrant=False, preserve_rng_state=False,
                )
            labels = batch["labels"].to(device)
            active = labels.ne(-100)
            gold = labels[active]
            logits = head(norm(hidden)[active]).float()
            # 降低单一结束符对短句的训练权重，以免多字段任务在第一个字段后就收句。
            token_weights = torch.ones_like(labels, dtype=torch.float32).masked_fill_(~active, 0)
            token_weights.masked_fill_(labels.eq(stop_id), 0.3)
            per_row = token_weights / token_weights.sum(dim=1, keepdim=True)
            ce = (F.cross_entropy(logits, gold, reduction="none") * per_row[active]).sum() / len(labels)
            indices = batch["points"].to(device)
            with torch.no_grad():
                teacher_logits = head(batch["teacher"][batch["points"]].to(device)).float()
                probabilities = F.softmax(teacher_logits / 1.5, dim=-1)
            kl = F.kl_div(F.log_softmax(logits[indices] / 1.5, dim=-1), probabilities, reduction="batchmean") * 2.25
            loss = ce + 0.7 * kl
            if not torch.isfinite(loss):
                raise FloatingPointError("蒸馏损失不是有限数")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(parameters, 1.0)
            optimizer.step()
            for module in edits:
                module.project()
            total_ce += ce.detach().item()
            total_kl += kl.detach().item()
        result = {"epoch": epoch + 1, "response_ce": total_ce / len(batches), "teacher_kl": total_kl / len(batches)}
        history.append(result)
        print(result, flush=True)
    return [edit.merged() for edit in edits], history


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--threads", type=positive_int, default=12)
    parser.add_argument("--batch-size", type=positive_int, default=2)
    parser.add_argument("--epochs", type=positive_int, default=5)
    parser.add_argument("--lr", type=positive_rate, default=0.00025)
    args = parser.parse_args()
    source, output = edit_paths(args.model, args.output)
    rows = [row for path in args.corpus for row in read_json(path)]
    if not rows:
        raise ValueError("corpus 至少需要一条训练会话")
    seed = 20261125
    torch.set_num_threads(args.threads)
    torch.manual_seed(seed)
    rng = random.Random(seed)
    tokenizer, model = load_text(source, "cpu")
    samples = prepare(tokenizer, rows)
    rng.shuffle(samples)
    batches = cache_examples(model, samples, args.batch_size, tokenizer.pad_token_id)
    matrices, history = fit(model, batches, args, tokenizer.convert_tokens_to_ids("<|im_end|>"), rng)
    del batches, model
    if args.device == "cuda":
        torch.cuda.empty_cache()
    print("正在写入并复核独立检查点", flush=True)
    save_copy(source, output, matrices, LAYERS)
    write_json(args.log, {"examples": len(rows), "seed": seed, "layers": LAYERS, "epochs": args.epochs,
                          "batch_size": args.batch_size, "learning_rate": args.lr, "history": history})


if __name__ == "__main__":
    main()
