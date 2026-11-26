"""在训练 Python 环境验收正式 CLI。正例需要支持 BF16 与 NF4 的 CUDA 设备。"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]


def make_model(directory):
    """只在临时目录生成随机 Dense Qwen3.5 检查点和原生单轮文本模板。"""
    import torch
    from tokenizers import Tokenizer
    from tokenizers.models import WordLevel
    from tokenizers.pre_tokenizers import WhitespaceSplit
    from transformers import (
        PreTrainedTokenizerFast, Qwen3_5Config, Qwen3_5ForConditionalGeneration,
        Qwen3_5TextConfig, Qwen3_5VisionConfig,
    )

    special = [
        "<|endoftext|>", "<unk>", "<|im_start|>", "<|im_end|>", "<think>", "</think>",
        "<|vision_start|>", "<|vision_end|>", "<|image_pad|>", "<|video_pad|>",
    ]
    tokens = special + ["user", "assistant", "u", "r", "a", "b"]
    vocab = {token: i for i, token in enumerate(tokens)}
    backend = Tokenizer(WordLevel(vocab, unk_token="<unk>"))
    backend.pre_tokenizer = WhitespaceSplit()
    backend.add_special_tokens(special)
    tokenizer = PreTrainedTokenizerFast(
        tokenizer_object=backend, unk_token="<unk>", pad_token="<|endoftext|>",
        eos_token="<|im_end|>", model_max_length=4096,
        chat_template=(
            "{% for m in messages %}{{ '<|im_start|>' ~ m.role ~ '\\n' ~ m.content ~ "
            "'<|im_end|>\\n' }}{% endfor %}{% if add_generation_prompt %}"
            "{{ '<|im_start|>assistant\\n<think>\\n' }}{% endif %}"
        ),
    )
    tokenizer.save_pretrained(directory)
    text = Qwen3_5TextConfig(
        vocab_size=len(vocab), hidden_size=128, intermediate_size=256, num_hidden_layers=2,
        num_attention_heads=2, num_key_value_heads=1, head_dim=64, max_position_embeddings=4096,
        layer_types=["linear_attention", "full_attention"], linear_num_key_heads=2,
        linear_num_value_heads=2, linear_key_head_dim=64, linear_value_head_dim=64,
        rope_parameters={"rope_type": "default", "rope_theta": 10000.0,
                         "partial_rotary_factor": 1.0, "mrope_section": [11, 11, 10]},
        pad_token_id=tokenizer.pad_token_id, eos_token_id=tokenizer.eos_token_id,
    )
    vision = Qwen3_5VisionConfig(
        depth=1, hidden_size=32, intermediate_size=64, num_heads=2, patch_size=2,
        temporal_patch_size=1, out_hidden_size=128, num_position_embeddings=16,
    )
    config = Qwen3_5Config(
        text_config=text, vision_config=vision, image_token_id=vocab["<|image_pad|>"],
        video_token_id=vocab["<|video_pad|>"], vision_start_token_id=vocab["<|vision_start|>"],
        vision_end_token_id=vocab["<|vision_end|>"],
    )
    torch.manual_seed(27)
    with torch.device("cpu"):
        Qwen3_5ForConditionalGeneration(config).save_pretrained(directory)
    return tokenizer.apply_chat_template(
        [{"role": "user", "content": "u"}], tokenize=False, add_generation_prompt=True
    )


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class TrainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        """同组用例共用一份只读输入，每次调用仍写入独立、尚不存在的输出目录。"""
        temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temporary.cleanup)
        cls.workspace = Path(temporary.name)
        cls.model = cls.workspace / "base"
        prompt = make_model(cls.model)
        chosen = "r\n</think>\n\na<|im_end|>"
        rejected = "r\n</think>\n\nb<|im_end|>"
        cls.sft_data = cls.workspace / "sft.jsonl"
        cls.dpo_data = cls.workspace / "dpo.jsonl"
        for path, row in (
            (cls.sft_data, {"prompt": prompt, "completion": chosen}),
            (cls.dpo_data, {"prompt": prompt, "chosen": chosen, "rejected": rejected}),
        ):
            path.write_bytes((json.dumps(row) + "\n").encode("utf-8"))

    def train(self, stage, data, output, *extra, accumulation=1):
        """所有请求都经正式进程入口，模型、数据与成品路径均指向本组临时目录。"""
        return subprocess.run(
            [sys.executable, "tools/train_misty27_alignment.py", "--stage", stage,
             "--model", str(self.model), "--data", str(data), "--output", str(output),
             "--accumulation", str(accumulation), *extra],
            cwd=ROOT, env=dict(os.environ, HF_HUB_OFFLINE="1", HF_HOME=str(self.workspace / "hf")),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace", timeout=600,
        )

    def read_adapter(self, stage, directory, data):
        """核对回执摘要，将发布的适配器交给 PEFT 重载，并逐张量对照成品。"""
        import torch
        from peft import PeftModel, get_peft_model_state_dict
        from safetensors.torch import load_file
        from transformers import AutoModelForCausalLM

        asset = directory / "adapter_model.safetensors"
        receipt = json.loads((directory / "training.json").read_text(encoding="utf-8"))
        for key, value in {
            "stage": stage, "steps": 1, "examples": 1,
            "data_sha256": sha256(data), "adapter_sha256": sha256(asset),
        }.items():
            self.assertEqual(receipt[key], value, key)
        base = AutoModelForCausalLM.from_pretrained(
            self.model, local_files_only=True, device_map={"": "cpu"}, attn_implementation="eager",
        )
        model = PeftModel.from_pretrained(base, directory, local_files_only=True)
        config = model.peft_config["default"]
        self.assertEqual((config.r, config.lora_alpha), (16, 32))
        saved = load_file(str(asset), device="cpu")
        loaded = get_peft_model_state_dict(model)
        self.assertTrue(saved)
        self.assertEqual(set(loaded), set(saved))
        for key, tensor in saved.items():
            self.assertTrue(torch.equal(loaded[key].cpu(), tensor), key)
        return receipt, saved

    def test_sft_then_dpo_publish_reloadable_trained_adapters(self):
        """从本轮 SFT 进入 DPO，核对策略有实际权值变化且原 SFT 成品逐字节一致。"""
        import torch

        sft, dpo = self.workspace / "sft", self.workspace / "dpo"
        run = self.train("sft", self.sft_data, sft)
        self.assertEqual(run.returncode, 0, run.stdout)
        sft_receipt, sft_tensors = self.read_adapter("sft", sft, self.sft_data)
        run = self.train("dpo", self.dpo_data, dpo, "--sft-adapter", str(sft))
        self.assertEqual(run.returncode, 0, run.stdout)
        dpo_receipt, dpo_tensors = self.read_adapter("dpo", dpo, self.dpo_data)
        self.assertEqual(dpo_receipt["sft_adapter_sha256"], sft_receipt["adapter_sha256"])
        self.assertEqual(sha256(sft / "adapter_model.safetensors"), sft_receipt["adapter_sha256"])
        self.assertEqual(set(sft_tensors), set(dpo_tensors))
        weights_changed = any(
            not torch.equal(tensor, dpo_tensors[key]) for key, tensor in sft_tensors.items()
        )
        self.assertTrue(weights_changed, "DPO 应至少更新一个适配器张量")

    def test_dpo_without_sft_adapter_rejects_without_publishing(self):
        """数据与基础模型均有效，DPO 缺少参考适配器时须点明字段并保持输出路径不存在。"""
        output = self.workspace / "missing-reference"
        run = self.train("dpo", self.dpo_data, output)
        self.assertNotEqual(run.returncode, 0)
        self.assertRegex(run.stdout, r"sft[-_]adapter")
        self.assertFalse(output.exists())

    def test_sft_exposures_schedule_and_receipt(self):
        """两行分别训练五次和一次，每轮六次、两轮共十二次，每累积三次更新一次。"""
        first = json.loads(self.sft_data.read_bytes())
        second = dict(first, completion="r\n</think>\n\nb<|im_end|>")
        data = self.workspace / "two-rows.jsonl"
        lines = "".join(json.dumps(row) + "\r\n" for row in (first, second))
        data.write_bytes(lines.encode("utf-8"))
        exposures = self.workspace / "six-per-epoch.json"
        manifest = {"data_sha256": sha256(data), "counts": [5, 1]}
        exposures.write_bytes((json.dumps(manifest, indent=2) + "\n").encode("utf-8"))
        output = self.workspace / "weighted-sft"

        run = self.train("sft", data, output, "--sft-exposures", str(exposures),
                         "--epochs", "2", accumulation=3)
        self.assertEqual(run.returncode, 0, run.stdout)
        receipt = json.loads((output / "training.json").read_bytes())
        for field, expected in {
            "steps": 4, "examples": 2, "examples_per_epoch": 6,
            "data_sha256": manifest["data_sha256"], "sft_exposures_sha256": sha256(exposures),
            "adapter_sha256": sha256(output / "adapter_model.safetensors"),
        }.items():
            self.assertEqual(receipt[field], expected, field)

    def test_invalid_sft_exposures_reject_without_publishing(self):
        """每次仅违反一个清单约定，DPO 禁用清单的用例另备有效的 SFT 参考成品。"""
        def assert_rejected(label, stage, data, manifest, *extra):
            with self.subTest(case=label):
                exposures = self.workspace / f"{label}.json"
                exposures.write_text(json.dumps(manifest), encoding="utf-8")
                output = self.workspace / f"rejected-{label}"
                run = self.train(stage, data, output, "--sft-exposures", str(exposures), *extra)
                self.assertNotEqual(run.returncode, 0, run.stdout)
                self.assertIn("sft-exposures", run.stdout)
                self.assertFalse(output.exists())

        valid = {"data_sha256": sha256(self.sft_data), "counts": [1]}
        valid_exposures = self.workspace / "valid-single-exposure.json"
        valid_exposures.write_text(json.dumps(valid), encoding="utf-8")
        reference = self.workspace / "exposure-rejection-reference"
        run = self.train("sft", self.sft_data, reference, "--sft-exposures", str(valid_exposures))
        self.assertEqual(run.returncode, 0, run.stdout)
        for label, manifest in (
            ("not-object", [valid]),
            ("missing-field", {"data_sha256": valid["data_sha256"]}),
            ("extra-field", dict(valid, extra=1)),
            ("wrong-data", dict(valid, data_sha256=sha256(self.dpo_data))),
            ("not-array", dict(valid, counts=1)),
            ("wrong-length", dict(valid, counts=[1, 1])),
            ("zero", dict(valid, counts=[0])),
            ("boolean", dict(valid, counts=[True])),
            ("decimal", dict(valid, counts=[1.0])),
        ):
            assert_rejected(label, "sft", self.sft_data, manifest)

        assert_rejected("dpo", "dpo", self.dpo_data,
                        {"data_sha256": sha256(self.dpo_data), "counts": [1]},
                        "--sft-adapter", str(reference))


if __name__ == "__main__":
    unittest.main()
