"""在评测侧比较盲题与合成会话，对训练方只返回训练 ID 和剔除原因。"""

import argparse
import unicodedata
from pathlib import Path

from misty27_rollout import publish_json, read_cases


def features(rows):
    """保留每个用户话轮和整段情境；位置供密封区复核最近邻。"""
    result = {}
    for row in rows:
        turns = [" ".join(unicodedata.normalize("NFKC", t).casefold().split()) for t in row["turns"]]
        parts = [(i, t) for i, t in enumerate(turns, 1)]
        if len(turns) > 1:
            parts.append(("all", "\n".join(turns)))
        result[row["id"]] = parts
    return result


def encode(texts, directory):
    """每个窗口均使用 E5 查询前缀，按实际有效词元做均值池化与 L2 归一化。"""
    import torch
    from transformers import AutoModel, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(directory, local_files_only=True)
    model = AutoModel.from_pretrained(directory, local_files_only=True).eval()
    prefix = tokenizer.encode("query: ", add_special_tokens=False)
    width = 512 - len(prefix) - 2
    windows = []
    for text in texts:
        ids = tokenizer.encode(text, add_special_tokens=False, verbose=False)
        for offset in range(0, len(ids), width - 64):
            part = [tokenizer.cls_token_id, *prefix, *ids[offset:offset + width], tokenizer.sep_token_id]
            windows.append((text, offset, part))
            if offset + width >= len(ids):
                break
    windows.sort(key=lambda w: len(w[2]))
    result = {t: [] for t in texts}
    with torch.inference_mode():
        for first in range(0, len(windows), 16):
            batch = windows[first:first + 16]
            inputs = tokenizer.pad([{"input_ids": w[2]} for w in batch], padding=True,
                                   return_attention_mask=True, return_tensors="pt")
            mask = inputs["attention_mask"].unsqueeze(-1)
            pooled = (model(**inputs).last_hidden_state * mask).sum(1) / mask.sum(1)
            vectors = torch.nn.functional.normalize(pooled, dim=-1)
            for (text, offset, _), vec in zip(batch, vectors):
                result[text].append((offset, vec))
    return result


def matrix(rows, vectors):
    """位置与矩阵行一一对应，最近邻的原文不离开评测进程。"""
    import torch

    positions, embeddings = [], []
    for key, parts in rows.items():
        for label, text in parts:
            for offset, vector in vectors[text]:
                positions.append({"id": key, "part": label, "offset": offset})
                embeddings.append(vector)
    return positions, torch.stack(embeddings)


def screen(blind, candidates, encoder):
    protected = {text for parts in blind.values() for _, text in parts}
    all_texts = protected | {text for parts in candidates.values() for _, text in parts}
    vectors = encode(sorted(all_texts), encoder)
    blind_positions, blind_matrix = matrix(blind, vectors)
    retained, rejected, neighbors, seen = [], [], [], set()
    for key, parts in candidates.items():
        sequence = tuple(text for label, text in parts if label != "all")
        if any(text in protected for _, text in parts):
            rejected.append({"id": key, "reason": "exact"})
            continue
        if sequence in seen:
            rejected.append({"id": key, "reason": "duplicate"})
            continue
        positions, candidates_matrix = matrix({key: parts}, vectors)
        similarities = candidates_matrix @ blind_matrix.T
        i, j = divmod(int(similarities.argmax()), len(blind_positions))
        similarity = float(similarities[i, j])
        if similarity >= 0.85:
            rejected.append({"id": key, "reason": "semantic"})
            continue
        retained.append(key)
        seen.add(sequence)
        neighbors.append({"candidate": positions[i], "protected": blind_positions[j], "cosine": similarity})
    return {"accepted": retained, "rejected": rejected}, neighbors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("blind", "candidates", "encoder", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--neighbors", type=Path)
    args = parser.parse_args()
    if args.neighbors and args.neighbors.resolve() == args.output.resolve():
        raise ValueError("neighbors 与 output 须使用不同文件")
    blind = features(read_cases(args.blind))
    candidates = features(read_cases(args.candidates))
    receipt, neighbors = screen(blind, candidates, args.encoder)
    if args.neighbors:
        publish_json(args.neighbors, neighbors)
    publish_json(args.output, receipt)


if __name__ == "__main__":
    main()
