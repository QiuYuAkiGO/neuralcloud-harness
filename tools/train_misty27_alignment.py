"""以官方文本网络做 NF4 LoRA 监督训练，再从 SFT 权重做固定参考的 DPO。"""

import argparse
import hashlib
import json
import math
from pathlib import Path
from tempfile import TemporaryDirectory

from misty27_rollout import publish_json


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_data(path, stage):
    """输入已由原生模板划分提示与助理续写，拒绝不完整的监督或偏好样本。"""
    fields = {"prompt", "completion"} if stage == "sft" else {"prompt", "chosen", "rejected"}
    rows = []
    raw = path.read_bytes()
    for line_number, line in enumerate(raw.splitlines(), 1):
        row = json.loads(line)
        if (not isinstance(row, dict) or set(row) != fields
                or not all(isinstance(v, str) and v.strip() for v in row.values())):
            raise ValueError(f"data 第 {line_number} 行须提供非空文本字段 {sorted(fields)}")
        if stage == "dpo" and row["chosen"] == row["rejected"]:
            raise ValueError(f"data 第 {line_number} 行的 chosen、rejected 须不同")
        rows.append(row)
    if not rows:
        raise ValueError("data 须至少有一条训练样本")
    return rows, hashlib.sha256(raw).hexdigest()


def adapter_hash(model, name):
    """在内存中核对不同名字的同一组低秩矩阵，参考副本须一直等于初始 SFT。"""
    import torch

    marker = f".{name}."
    matched = sorted((k.replace(marker, "."), v) for k, v in model.named_parameters() if marker in k)
    if not matched:
        raise ValueError(f"adapter {name} 没有低秩参数")
    digest = hashlib.sha256()
    for key, tensor in matched:
        digest.update(key.encode())
        digest.update(tensor.detach().contiguous().view(torch.uint8).cpu().numpy().tobytes())
    return digest.hexdigest()


def train(args, rows, data_hash, work):
    import torch
    from datasets import Dataset
    from peft import LoraConfig, PeftModel, get_peft_model
    from transformers import AutoTokenizer, BitsAndBytesConfig, Qwen3_5ForCausalLM, set_seed
    from trl import DPOConfig, DPOTrainer, SFTConfig, SFTTrainer

    set_seed(27)
    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True)
    quant = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                               bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)
    # 同一原版语言主干同时负责纯文本训练和参考概率；视觉塔不参与权重更新。
    base, loaded = Qwen3_5ForCausalLM.from_pretrained(
        args.model, local_files_only=True, dtype=torch.bfloat16, device_map={"": 0},
        quantization_config=quant, attn_implementation="sdpa", output_loading_info=True,
    )
    if loaded["missing_keys"] or loaded["mismatched_keys"] or loaded["error_msgs"]:
        raise ValueError(f"基础文本权重未完整加载：{loaded['missing_keys']}, "
                         f"{loaded['mismatched_keys']}, {loaded['error_msgs']}")
    base.config.use_cache = False
    epochs, accumulation = args.epochs, args.accumulation
    rate = args.learning_rate if args.learning_rate is not None else (0.0001 if args.stage == "sft" else 0.00002)
    common = dict(
        output_dir=str(work / "trainer"), num_train_epochs=epochs, learning_rate=rate, seed=27,
        per_device_train_batch_size=1, gradient_accumulation_steps=accumulation,
        gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
        bf16=True, tf32=True, max_length=None, warmup_steps=int(math.ceil(len(rows) / accumulation) * epochs * .05),
        lr_scheduler_type="cosine", optim="adamw_torch", save_strategy="no", report_to="none",
        logging_steps=1, dataloader_num_workers=0, disable_tqdm=True,
    )
    dataset = Dataset.from_list(rows)
    extra = {}
    if args.stage == "sft":
        config = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.0, task_type="CAUSAL_LM",
                            target_modules=["v_proj", "o_proj", "up_proj", "down_proj"])
        model = get_peft_model(base, config)
        trainer = SFTTrainer(model=model, processing_class=tokenizer, train_dataset=dataset,
                             args=SFTConfig(**common, completion_only_loss=True, packing=False))
    else:
        # TRL 给已有的策略适配器复制独立 ref，再预计算参考概率。核对副本以防引用裸主干。
        parent = json.loads((args.sft_adapter / "training.json").read_text(encoding="utf-8"))
        parent_hash = file_hash(args.sft_adapter / "adapter_model.safetensors")
        if (parent["stage"] != "sft" or parent["adapter_sha256"] != parent_hash
                or parent["base_model"] != str(args.model.resolve())):
            raise ValueError("sft-adapter 的阶段、权重摘要或基础权重与本轮不符")
        model = PeftModel.from_pretrained(base, args.sft_adapter, local_files_only=True, is_trainable=True)
        policy_start = adapter_hash(model, "default")
        trainer = DPOTrainer(model=model, processing_class=tokenizer, train_dataset=dataset,
                             args=DPOConfig(**common, beta=0.1, precompute_ref_log_probs=True,
                                            precompute_ref_batch_size=1))
        if adapter_hash(model, "ref") != policy_start:
            raise ValueError("DPO 初始参考与 SFT 策略权重不同")
        extra = {"sft_adapter_sha256": parent_hash, "reference_tensor_sha256": policy_start, "beta": 0.1}

    trainable = sum(t.numel() for t in model.parameters() if t.requires_grad)
    before = adapter_hash(model, "default")
    result = trainer.train()
    if not math.isfinite(result.training_loss) or trainer.state.global_step == 0:
        raise RuntimeError("训练须产生有限 loss 和实际更新步数")
    if adapter_hash(model, "default") == before:
        raise RuntimeError("策略的低秩权重没有发生更新")
    if args.stage == "dpo" and adapter_hash(model, "ref") != extra["reference_tensor_sha256"]:
        raise RuntimeError("DPO 参考权重在训练中发生变化")
    artifact = work / "adapter"
    model.save_pretrained(artifact, selected_adapters=["default"], safe_serialization=True)
    receipt = dict(stage=args.stage, base_model=str(args.model.resolve()), steps=trainer.state.global_step,
                   examples=len(rows), data_sha256=data_hash,
                   adapter_sha256=file_hash(artifact / "adapter_model.safetensors"), trainable_parameters=trainable,
                   epochs=epochs, accumulation=accumulation, learning_rate=rate, rank=16, alpha=32, seed=27,
                   text_layers=base.config.num_hidden_layers, hidden_size=base.config.hidden_size,
                   training_loss=result.training_loss, gpu=torch.cuda.get_device_name(),
                   peak_cuda_bytes=torch.cuda.max_memory_allocated(), metrics=result.metrics,
                   log_history=trainer.state.log_history, **extra)
    publish_json(artifact / "training.json", receipt)
    return artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("sft", "dpo"), required=True)
    for field in ("model", "data", "output"):
        parser.add_argument("--" + field, type=Path, required=True)
    parser.add_argument("--sft-adapter", type=Path)
    parser.add_argument("--learning-rate", type=float)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--accumulation", type=int, default=4)
    args = parser.parse_args()
    if (args.stage == "dpo") != (args.sft_adapter is not None):
        parser.error("--sft-adapter 须且仅须在 DPO 阶段提供")
    if args.epochs < 1 or args.accumulation < 1 or (
        args.learning_rate is not None and not 0 < args.learning_rate < math.inf
    ):
        parser.error("epochs、accumulation 和 learning-rate 需要有限正值")
    if args.output.exists():
        parser.error("output 须为新目录")
    rows, data_hash = read_data(args.data, args.stage)
    # 半成品只在同一卷的临时目录中，权重和摘要全部写好后才可由调用方加载。
    with TemporaryDirectory(dir=args.output.parent, prefix=".misty27-") as temporary:
        artifact = train(args, rows, data_hash, Path(temporary))
        artifact.rename(args.output)


if __name__ == "__main__":
    main()
