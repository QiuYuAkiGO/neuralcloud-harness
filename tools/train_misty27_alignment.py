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


def read_sft_exposures(path, data_hash, row_count):
    """清单绑定训练文件的原始字节，避免语料变更后仍按旧行号增加训练次数。"""
    raw = path.read_bytes()
    try:
        manifest = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("sft-exposures 须为合法 JSON 对象") from error
    if (not isinstance(manifest, dict) or set(manifest) != {"data_sha256", "counts"}
            or manifest["data_sha256"] != data_hash or not isinstance(manifest["counts"], list)
            or len(manifest["counts"]) != row_count
            or any(type(count) is not int or count < 1 for count in manifest["counts"])):
        raise ValueError("sft-exposures 须提供匹配训练文件的 data_sha256 和每行一个正整数的 counts")
    return manifest["counts"], hashlib.sha256(raw).hexdigest()


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
        # PEFT 以 FP32 载入父权重，QLoRA 会转为 BF16。按冻结参考的 FP32 数值口径核对。
        values = tensor.detach().to(dtype=torch.float32).contiguous()
        digest.update(values.cpu().numpy().tobytes())
    return digest.hexdigest()


def sft_parent_hash(directory, model, option):
    """载入 LoRA 前核对父回执、基础模型路径和权重文件，防止从错误的检查点续训。"""
    try:
        parent = json.loads((directory / "training.json").read_bytes())
        digest = file_hash(directory / "adapter_model.safetensors")
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{option} 须提供可读取的 SFT 回执和权重") from error
    if (not isinstance(parent, dict) or parent.get("stage") != "sft"
            or parent.get("adapter_sha256") != digest or parent.get("base_model") != str(model.resolve())):
        raise ValueError(f"{option} 的阶段、权重摘要或基础权重与本轮不符")
    return digest


def train(args, rows, data_hash, work, exposures=None):
    parent_dir = args.sft_start_adapter if args.stage == "sft" else args.sft_adapter
    if parent_dir is not None:
        option = "--sft-start-adapter" if args.stage == "sft" else "--sft-adapter"
        parent_hash = sft_parent_hash(parent_dir, args.model, option)

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
    dataset = Dataset.from_list(rows)
    extra = {}
    if exposures is not None:
        # 各行至少出现一次。训练器打乱扩展后的行索引，原始清洗语料和摘要可以逐行复核。
        counts, exposure_hash = exposures
        indices = [index for index, count in enumerate(counts) for _ in range(count)]
        dataset = dataset.select(indices)
        extra.update(examples_per_epoch=len(dataset), sft_exposures_sha256=exposure_hash)
    common = dict(
        output_dir=str(work / "trainer"), num_train_epochs=epochs, learning_rate=rate, seed=27,
        per_device_train_batch_size=1, gradient_accumulation_steps=accumulation,
        gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
        bf16=True, tf32=True, max_length=None, warmup_steps=int(math.ceil(len(dataset) / accumulation) * epochs * .05),
        lr_scheduler_type="cosine", optim="adamw_torch", save_strategy="no", report_to="none",
        logging_steps=1, dataloader_num_workers=0, disable_tqdm=True,
    )
    # 首次 SFT 新建 LoRA。监督补训与 DPO 都从经回执核对的父权重开始。
    if parent_dir is None:
        config = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.0, task_type="CAUSAL_LM",
                            target_modules=["v_proj", "o_proj", "up_proj", "down_proj"])
        model = get_peft_model(base, config)
    else:
        model = PeftModel.from_pretrained(base, parent_dir, local_files_only=True, is_trainable=True)
    if args.stage == "sft":
        trainer = SFTTrainer(model=model, processing_class=tokenizer, train_dataset=dataset,
                             args=SFTConfig(**common, completion_only_loss=True, packing=False))
    else:
        # TRL 给已有的策略适配器复制独立 ref，再预计算参考概率。核对副本以防引用裸主干。
        policy_start = adapter_hash(model, "default")
        extra = {"sft_adapter_sha256": parent_hash, "reference_tensor_sha256": policy_start, "beta": 0.1}
        losses = {}
        if args.dpo_sft_weight is not None:
            # TRL 的 sft 项只对正例续答词元求平均，固定参考仍仅用于 sigmoid 偏好项。
            losses = {"loss_type": ["sigmoid", "sft"], "loss_weights": [1.0, args.dpo_sft_weight]}
            extra["dpo_sft_weight"] = args.dpo_sft_weight
        trainer = DPOTrainer(model=model, processing_class=tokenizer, train_dataset=dataset,
                             args=DPOConfig(**common, beta=0.1, precompute_ref_log_probs=True,
                                            precompute_ref_batch_size=1, **losses))
        if adapter_hash(model, "ref") != policy_start:
            raise ValueError("DPO 初始参考与 SFT 策略权重不同")

    trainable = sum(t.numel() for t in model.parameters() if t.requires_grad)
    before = adapter_hash(model, "default")
    if args.sft_start_adapter is not None:
        extra.update(initial_sft_adapter_sha256=parent_hash, initial_sft_tensor_sha256=before)
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
    parser.add_argument("--sft-start-adapter", type=Path, help="监督补训的起始 SFT 适配器目录")
    parser.add_argument("--sft-exposures", type=Path, help="SFT 各行每轮训练次数及数据摘要的 JSON 清单")
    parser.add_argument("--dpo-sft-weight", type=float, help="DPO 中正例续答平均负对数似然的系数")
    parser.add_argument("--learning-rate", type=float)
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--accumulation", type=int, default=4)
    args = parser.parse_args()
    if args.sft_start_adapter is not None and args.stage != "sft":
        parser.error("--sft-start-adapter 仅适用于 SFT 阶段")
    if (args.stage == "dpo") != (args.sft_adapter is not None):
        parser.error("--sft-adapter 须且仅须在 DPO 阶段提供")
    if args.sft_exposures is not None and args.stage != "sft":
        parser.error("--sft-exposures 仅适用于 SFT 阶段")
    if args.dpo_sft_weight is not None and (args.stage != "dpo" or not 0 < args.dpo_sft_weight < math.inf):
        parser.error("--dpo-sft-weight 仅适用于 DPO 阶段，且须为有限正数")
    if args.epochs < 1 or args.accumulation < 1 or (
        args.learning_rate is not None and not 0 < args.learning_rate < math.inf
    ):
        parser.error("epochs、accumulation 和 learning-rate 需要有限正值")
    if args.output.exists():
        parser.error("output 须为新目录")
    rows, data_hash = read_data(args.data, args.stage)
    exposures = None
    if args.sft_exposures is not None:
        exposures = read_sft_exposures(args.sft_exposures, data_hash, len(rows))
    # 半成品只在同一卷的临时目录中，权重和摘要全部写好后才可由调用方加载。
    with TemporaryDirectory(dir=args.output.parent, prefix=".misty27-") as temporary:
        artifact = train(args, rows, data_hash, Path(temporary), exposures)
        artifact.rename(args.output)


if __name__ == "__main__":
    main()
