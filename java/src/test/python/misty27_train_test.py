"""在训练 Python 环境验收正式 CLI。正例需要支持 BF16 与 NF4 的 CUDA 设备。"""

import hashlib
import json
import math
import os
import shutil
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


def checkpoint_files(directory):
    """快照父检查点的文件名及内容，以便核对训练期间的只读约定。"""
    return {path.relative_to(directory).as_posix(): sha256(path)
            for path in directory.rglob("*") if path.is_file()}


def tensor_sha256(tensors):
    """从已发布的 PEFT 键名和小端顺序的 FP32 张量复算数值摘要。"""
    digest = hashlib.sha256()
    for name in sorted(tensors):
        digest.update(name.encode("utf-8"))
        array = tensors[name].float().numpy().astype("<f4", copy=False)
        digest.update(array.tobytes(order="C"))
    return digest.hexdigest()


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

    def train(self, stage, data, output, *extra, accumulation=1, model=None):
        """所有请求都经正式进程入口，模型、数据与成品路径均指向本组临时目录。"""
        return subprocess.run(
            [sys.executable, "tools/train_misty27_alignment.py", "--stage", stage,
             "--model", str(self.model if model is None else model), "--data", str(data),
             "--output", str(output),
             "--accumulation", str(accumulation), *extra],
            cwd=ROOT, env=dict(os.environ, HF_HUB_OFFLINE="1", HF_HOME=str(self.workspace / "hf")),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace", timeout=600,
        )

    def sft_reference(self, name="sft", *extra):
        """同组用例共用从正式入口生成的只读 SFT 成品，独立选跑时也可生成。"""
        directory = self.workspace / name
        if not directory.exists():
            run = self.train("sft", self.sft_data, directory, *extra)
            self.assertEqual(run.returncode, 0, run.stdout)
        return directory

    def dpo_parent_for_sft(self):
        """两例共用正式入口训练的 DPO 成品及其原始 SFT 参考，便于区分两组权重。"""
        sft = self.sft_reference()
        parent = self.workspace / "trained-dpo-parent"
        if not parent.exists():
            run = self.train("dpo", self.dpo_data, parent, "--sft-adapter", str(sft),
                             "--learning-rate", "0.003")
            self.assertEqual(run.returncode, 0, run.stdout)
        return sft, parent

    def read_adapter(self, stage, directory, data, steps=1):
        """核对回执摘要，将发布的适配器交给 PEFT 重载，并逐张量对照成品。"""
        import torch
        from peft import PeftModel, get_peft_model_state_dict
        from safetensors.torch import load_file
        from transformers import AutoModelForCausalLM

        asset = directory / "adapter_model.safetensors"
        receipt = json.loads((directory / "training.json").read_text(encoding="utf-8"))
        for key, value in {
            "stage": stage, "steps": steps, "examples": 1,
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

    def test_sft_start_adapter_trains_from_parent_weights(self):
        """用同一父权重核对初始摘要，并从补训后可重载的实际张量辨别训练起点。"""
        import torch
        from safetensors.torch import load_file

        parent = self.sft_reference("sft-start-parent", "--epochs", "3", "--learning-rate", "0.001")
        original = checkpoint_files(parent)
        before = load_file(str(parent / "adapter_model.safetensors"), device="cpu")
        # DPO 的冻结参考提供同一组实际张量的既有摘要口径。
        reference = self.workspace / "sft-start-dpo-reference"
        run = self.train("dpo", self.dpo_data, reference, "--sft-adapter", str(parent))
        self.assertEqual(run.returncode, 0, run.stdout)
        reference_receipt = json.loads((reference / "training.json").read_bytes())

        pair = json.loads(self.dpo_data.read_bytes())
        data = self.workspace / "sft-start-new-completion.jsonl"
        row = {"prompt": pair["prompt"], "completion": pair["rejected"]}
        data.write_text(json.dumps(row) + "\n", encoding="utf-8")
        output = self.workspace / "sft-continued"
        learning_rate = 1e-6
        run = self.train("sft", data, output, "--sft-start-adapter", str(parent),
                         "--learning-rate", str(learning_rate), "--epochs", "2",
                         model=self.model / ".." / self.model.name)
        self.assertEqual(run.returncode, 0, run.stdout)
        receipt, after = self.read_adapter("sft", output, data, steps=2)
        self.assertEqual(receipt["initial_sft_adapter_sha256"], original["adapter_model.safetensors"])
        self.assertEqual(receipt["initial_sft_tensor_sha256"], reference_receipt["reference_tensor_sha256"])
        self.assertEqual((receipt["learning_rate"], receipt["epochs"]), (learning_rate, 2))
        self.assertEqual(checkpoint_files(parent), original)

        # 初建 LoRA 的 B 矩阵为零。父权重已学到非零值，微小的补训更新应保留它们。
        parent_signal = max(t.abs().max().item() for key, t in before.items() if ".lora_B." in key)
        self.assertGreater(parent_signal, 0)
        self.assertEqual(set(after), set(before))
        for key, tensor in before.items():
            with self.subTest(tensor=key):
                torch.testing.assert_close(after[key], tensor, rtol=0, atol=parent_signal / 100)
        self.assertTrue(any(not torch.equal(tensor, after[key]) for key, tensor in before.items()),
                        "补训至少应实际更新一个父检查点的张量")

    def test_invalid_sft_start_adapter_rejects_without_publishing(self):
        """逐项改变父回执、基础模型路径或训练阶段，核对诊断和成品目录。"""
        parent = self.sft_reference("sft-start-parent", "--epochs", "3", "--learning-rate", "0.001")

        def assert_rejected(label, stage, data, source, *extra, model=None):
            with self.subTest(case=label):
                output = self.workspace / f"sft-start-rejected-{label}"
                run = self.train(stage, data, output, "--sft-start-adapter", str(source), *extra, model=model)
                self.assertNotEqual(run.returncode, 0, run.stdout)
                self.assertIn("sft-start-adapter", run.stdout)
                self.assertFalse(output.exists())

        changed = self.workspace / "sft-start-invalid-parent"
        shutil.copytree(parent, changed)
        receipt = json.loads((parent / "training.json").read_bytes())
        for field, value in (("stage", "dpo"), ("adapter_sha256", sha256(self.sft_data))):
            invalid = {**receipt, field: value}
            (changed / "training.json").write_text(json.dumps(invalid), encoding="utf-8")
            assert_rejected(field, "sft", self.sft_data, changed)

        other_model = self.workspace / "sft-start-other-base"
        shutil.copytree(self.model, other_model)
        assert_rejected("base-model", "sft", self.sft_data, parent, model=other_model)
        assert_rejected("dpo", "dpo", self.dpo_data, parent, "--sft-adapter", str(parent))

    def test_sft_start_dpo_adapter_trains_and_supplies_next_dpo_reference(self):
        """从实际 DPO 策略更新一份新 SFT，并把该成品交给下一次 DPO 作参考。"""
        from safetensors.torch import load_file

        seed, parent = self.dpo_parent_for_sft()
        asset = "adapter_model.safetensors"
        original = checkpoint_files(parent)
        seed_weights = load_file(str(seed / asset), device="cpu")
        parent_weights = load_file(str(parent / asset), device="cpu")

        pair = json.loads(self.dpo_data.read_bytes())
        data = self.workspace / "after-dpo-completion.jsonl"
        data.write_text(json.dumps({"prompt": pair["prompt"], "completion": pair["rejected"]}) + "\n",
                        encoding="utf-8")
        child = self.workspace / "sft-after-dpo"
        run = self.train("sft", data, child, "--sft-start-dpo-adapter", str(parent),
                         "--learning-rate", "0.000001", model=self.model / ".." / self.model.name)
        self.assertEqual(run.returncode, 0, run.stdout)
        receipt, child_weights = self.read_adapter("sft", child, data)
        self.assertEqual(receipt["initial_dpo_adapter_sha256"], original[asset])
        self.assertEqual(receipt["initial_dpo_tensor_sha256"], tensor_sha256(parent_weights))
        self.assertEqual(checkpoint_files(parent), original)

        # 父策略相对原 SFT 的变化远大于新段的一次小步，成品可辨别实际使用的起点。
        def max_change(left, right):
            self.assertEqual(set(left), set(right))
            return max((left[key].float() - right[key].float()).abs().max().item() for key in left)

        child_change = max_change(child_weights, parent_weights)
        self.assertGreater(child_change, 0)
        self.assertLess(child_change, max_change(parent_weights, seed_weights) / 100)

        downstream = self.workspace / "dpo-after-continued-sft"
        run = self.train("dpo", self.dpo_data, downstream, "--sft-adapter", str(child))
        self.assertEqual(run.returncode, 0, run.stdout)
        dpo = json.loads((downstream / "training.json").read_bytes())
        self.assertEqual(dpo["sft_adapter_sha256"], receipt["adapter_sha256"])
        self.assertEqual(dpo["reference_tensor_sha256"], tensor_sha256(child_weights))

    def test_invalid_sft_start_dpo_adapter_rejects_without_publishing(self):
        """每次只违反一个 DPO 起点条件，核对非零退出、专属参数诊断和成品状态。"""
        seed, parent = self.dpo_parent_for_sft()

        def assert_rejected(label, stage, data, source, *extra, model=None):
            with self.subTest(case=label):
                output = self.workspace / f"start-dpo-rejected-{label}"
                run = self.train(stage, data, output, "--sft-start-dpo-adapter", str(source), *extra,
                                 model=model)
                self.assertNotEqual(run.returncode, 0, run.stdout)
                self.assertIn("sft-start-dpo-adapter", run.stdout)
                self.assertFalse(output.exists())

        assert_rejected("stage", "sft", self.sft_data, seed)
        assert_rejected("both-origins", "sft", self.sft_data, parent, "--sft-start-adapter", str(seed))
        assert_rejected("dpo", "dpo", self.dpo_data, parent, "--sft-adapter", str(seed))

        other_model = self.workspace / "start-dpo-other-base"
        shutil.copytree(self.model, other_model)
        assert_rejected("base-model", "sft", self.sft_data, parent, model=other_model)

        changed = self.workspace / "start-dpo-wrong-weights"
        shutil.copytree(parent, changed)
        asset = "adapter_model.safetensors"
        shutil.copyfile(seed / asset, changed / asset)
        self.assertNotEqual(sha256(changed / asset), sha256(parent / asset))
        assert_rejected("digest", "sft", self.sft_data, changed)

    def test_sft_then_dpo_publish_reloadable_trained_adapters(self):
        """从本轮 SFT 进入 DPO，核对策略有实际权值变化且原 SFT 成品逐字节一致。"""
        import torch

        sft, dpo = self.sft_reference(), self.workspace / "dpo"
        sft_receipt, sft_tensors = self.read_adapter("sft", sft, self.sft_data)
        run = self.train("dpo", self.dpo_data, dpo, "--sft-adapter", str(sft))
        self.assertEqual(run.returncode, 0, run.stdout)
        dpo_receipt, dpo_tensors = self.read_adapter("dpo", dpo, self.dpo_data)
        self.assertNotIn("dpo_sft_weight", dpo_receipt)
        self.assertAlmostEqual(dpo_receipt["training_loss"], math.log(2), delta=0.01)
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

    def test_dpo_sft_weight_optimizes_chosen_completion_mean(self):
        """在一个真实更新步上复算 DPO 与正例监督的总损失，并核对策略张量有更新。"""
        import torch
        from safetensors.torch import load_file
        from transformers import AutoTokenizer

        pair = json.loads(self.dpo_data.read_bytes())
        pair["rejected"] = "r r r r r r\n</think>\n\nb b b b b b<|im_end|>"
        data = self.workspace / "unequal-completions.jsonl"
        data.write_text(json.dumps(pair) + "\n", encoding="utf-8")
        tokenizer = AutoTokenizer.from_pretrained(self.model, local_files_only=True)
        chosen_ids = tokenizer.encode(pair["chosen"], add_special_tokens=False)
        sft = self.sft_reference()
        source = sft / "adapter_model.safetensors"
        source_hash = sha256(source)
        output = self.workspace / "dpo-with-chosen-supervision"
        weight = 0.37
        run = self.train("dpo", data, output, "--sft-adapter", str(sft),
                         "--dpo-sft-weight", str(weight))
        self.assertEqual(run.returncode, 0, run.stdout)
        receipt = json.loads((output / "training.json").read_bytes())
        self.assertEqual(receipt["dpo_sft_weight"], weight)
        self.assertEqual((receipt["examples"], receipt["steps"]), (1, 1))
        self.assertEqual(receipt["sft_adapter_sha256"], source_hash)
        self.assertEqual(sha256(source), source_hash)

        # 更新前策略与参考相同，偏好项为 ln(2)。正例的均值只除以其实际续答词元数。
        chosen_logps = [
            entry["logps/chosen"] for entry in receipt["log_history"] if "logps/chosen" in entry
        ]
        self.assertTrue(chosen_logps, "DPO 单步回执缺少 logps/chosen")
        self.assertLess(chosen_logps[0], 0)
        expected_loss = math.log(2) - weight * chosen_logps[0] / len(chosen_ids)
        self.assertAlmostEqual(receipt["training_loss"], expected_loss, delta=0.01)
        before = load_file(str(source), device="cpu")
        after = load_file(str(output / "adapter_model.safetensors"), device="cpu")
        self.assertEqual(set(before), set(after))
        self.assertTrue(any(not torch.equal(before[key], after[key]) for key in before),
                        "DPO 加入正例监督后应实际更新策略张量")

    def test_invalid_dpo_sft_weight_rejects_without_publishing(self):
        """SFT 禁用系数，DPO 每次只改变系数，检查违规参数的诊断和成品目录。"""
        stages = (
            ("sft", self.sft_data, (), ("0.37",)),
            ("dpo", self.dpo_data, ("--sft-adapter", str(self.sft_reference())),
             ("0", "-0.1", "nan", "inf", "-inf")),
        )
        for stage, data, extra, values in stages:
            for value in values:
                with self.subTest(stage=stage, weight=value):
                    output = self.workspace / f"invalid-weight-{stage}-{value}"
                    run = self.train(stage, data, output, *extra, f"--dpo-sft-weight={value}")
                    self.assertNotEqual(run.returncode, 0, run.stdout)
                    self.assertIn("dpo-sft-weight", run.stdout)
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
