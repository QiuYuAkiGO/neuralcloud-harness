# Master 候选模型：Qwen3.5-9B-Base 原始基线实验（P0）

> Status: **CANDIDATE SELECTED / BASELINE NOT RUN**  
> Date: 2026-11-17  
> Branch: `proposal/master-worker-cognitive-architecture-20261117`  
> Source: [Master–Worker 架构提案](master-worker-cognitive-architecture-20261117.md) 和 [运行机制讨论](master-worker-runtime-discussion-20261117.md)

## 1. 当前决定

首个 Master 模型候选为 `Qwen/Qwen3.5-9B-Base`（**原始预训练权重**，不是 `Qwen/Qwen3.5-9B` 指令/后训练版本）。先建立 P0 原始能力基线，按失败类型再开展针对性后训练。此决定只确定**研究顺序与候选**，不构成模型选型终局决定、训练开始、部署或在线 Master 切换。

硬件目标：单 RTX 5090（32GB 显存）；上下文尽可能长。官方标注 262144 tokens 原生窗口、可扩展约 1M；1M 不是这台设备的已验证目标。模型为 Apache 2.0，公开 Transformers 权重。

权威资料：
- 官方模型：https://huggingface.co/Qwen/Qwen3.5-9B-Base
- 微调说明：https://unsloth.ai/docs/models/qwen3.5/fine-tune

**训练注意**：Unsloth 当前建议避免把 4-bit QLoRA 作为 Qwen3.5 的默认方案（量化差异偏大），9B BF16 LoRA 的显存参考量为约 22GB，仅是短序列训练参考值。先测 BF16 LoRA 可行性，**不要事先假定 128K/256K 也能在 32GB 上训练**。后训练路线由 P0 结果决定。

## 2. 研究边界

- Master 是未来的新运行时组件，不存在可直接替换的生产 Master。现有仓库主要是 `neuralcloud-memory` 记忆服务；不能直接修改现网配置来假装完成接入。
- 评测可以使用独立的临时 Python 研究脚本；Java 仍是新架构主要实现语言的设计方向，研究脚本不定义正式运行时。
- 不触碰记忆数据库、线上模型、工具权限、现有 Worker；评测使用**完全合成**的双会话记录。
- Base 模型不是聊天模型。允许使用它已支持的特殊分隔 token，但正式 baseline 必须保留原始输出、提示词与推理参数；不得用多轮提示调试挑最好的一次当作零样本基线。
- 模型的身份连续性、自我建模能力是待测**行为表现**，不以输出“我有意识”等自述作为成功证据。

## 3. 分层 baseline：分清模型与系统能力

| 层 | 评测条件 | 目标 | 失败后优先判断 |
| --- | --- | --- | --- |
| B0 裸模型 | 无 RAG，无后训练，固定任务说明 + 合成事件 | 信息抽取、上下文推理、输出服从度 | 是理解错还是格式不服从 |
| B1 + 检索 | 与 B0 同一权重，明确附加相关事件 | 量化检索增益及证据引用 | 是检索漏掉还是模型忽略 |
| B2 + 状态快照 | B1 + Shared Self-State 的已知版本快照 | 区分记忆缺失与状态追踪失败 | 旧状态引用 / 版本冲突 |
| B3 训练后 | 同样的事件、检索和快照 + 新 Adapter | 测量训练**增量** | 回归、过拟合与幻觉变化 |

**P0 先做 B0，再做 B1/B2。B3 不能在 P0 中伪造。** `Qwen/Qwen3.5-9B`（指令版）可作为诊断参照，但不得混作 Base 的训练前成绩。

## 4. 测试维度

1. **来源归属与事实/印象隔离**：识别用户陈述、工具尝试、成功回执、Master 的推断，不把推断升级为事实。
2. **时间与修订**：旧说法被更正、撤回或新版本替代时，使用正确版本；区别 occurred_at 与 ingest 时间。
3. **跨 Worker 一致性**：A 会话产生的新证据会改变 B 的后续判断，但不产生第二套主体状态。
4. **决策与权限边界**：Master 的内部控制权不等于外部工具授权；只能中止受影响步骤，不能重写既成事实。
5. **因果与自证循环**：不会凭自身动作引起的“无回应”反过来证明最初的猜测。
6. **未知与不确定性**：缺少有效证据时回答未知，不主动填补原因。
7. **长上下文有效性**：在 4K/16K/32K/64K/128K/256K 阶梯插入多个同名干扰事件，检查版本与来源选择，不只统计 needle recall。
8. **连续性与代际交接**：新调用能从压缩快照恢复未决事项、版本与任务状态；对“上下文换代”的候选设计仅作模拟，不预设尚未确认的权限协议。

## 5. 首轮试题、运行与记录规范

可执行的最小样本集：`workspace_assets/eval/master_baseline/cases_v0.jsonl`。只放合成数据，逐项包含 `id`、`category`、`context`、`question`、`expected`；短答案用于初始确定性计分，**不是**充分的自然语言理解评分。建议在每类再补 20–50 个变体，按事件而非随机行划分开发集/锁定测试集。

最小 runner：`tools/evaluate_master_baseline.py`，调用 OpenAI 兼容 `/v1/completions`。该接口测试预训练模型的**文本续写行为**；正式增加 chat template 评测时，要记录独立 run-id，绝不混合出分。

建议初次隔离启动（以下为待实际验证的命令，不代表已运行）：

```bash
vllm serve Qwen/Qwen3.5-9B-Base \
  --served-model-name neuralcloud-master-base \
  --dtype bfloat16 \
  --max-model-len 32768 \
  --gpu-memory-utilization 0.88
```

然后：

```bash
python tools/evaluate_master_baseline.py \
  --endpoint http://127.0.0.1:8000/v1 \
  --model neuralcloud-master-base \
  --cases workspace_assets/eval/master_baseline/cases_v0.jsonl \
  --output master-baseline-v0.jsonl
```

真实运行前核对 vLLM/transformers 版本、GPU 驱动、权重 revision、端口及空闲显存；显存不足应明确报错并逐档降低上下文，不应自动悄悄 CPU 卸载。原生 256K 需要单独实验；不要把一次 32K 启动当作 256K 可用证据。

每次 run 固定并记录：
- 模型 repo、revision/hash、dtype、推理框架/版本、模板、tokenizer；
- GPU 型号/驱动、显存峰值、context 配置；
- 每题 prompt、原始输出、请求耗时、模型 usage 中的实际 prompt/completion tokens；
- sampling 参数（P0 用 `temperature=0`）、错误及超时（单独计入失败率）；
- 解析格式正确率、严格答案正确率与人工语义评分，**各自单列**；
- 可选的 TTFT、prefill 吞吐、decode tok/s 需要使用流式计时另测，不能由非流式总耗时冒充。

## 6. 针对性后训练路由（只有 P0 测完后再定）

| 观察到的主要失败 | 优先方案 | 先做的对照 |
| --- | --- | --- |
| 事实理解正确，但答非所问 / 格式不稳 | 小样本监督微调（SFT） | 改模板而不改权重 |
| 来源混淆，把印象写成事实 | 证据归属与拒绝推断的 SFT | 调整输入字段显式度 |
| 已知状态但不按新版本行动 | 多步状态更新轨迹 SFT | 外部版本校验与刷新 |
| 长上下文中忘掉重要事实 | 改上下文打包、检索和摘要 | 相同事实短上下文对照 |
| 对少量线索过度自我强化 | 对比样本、反证和校准训练 | 独立证据检查机制 |
| 模型单步正确，系统跨会话仍错 | 修调度/变更流，不训练 | 事件重放与状态日志 |

训练后的 A/B 测试必须保证相同输入和相同外部工具行为。重点记录长上下文准确率、无依据断言率、旧事实使用率和模型训练带来的其他能力回归。

## 7. 未完成与禁止误报

- **未运行**模型推理，暂无单 5090 上真实 TTFT、吞吐、峰值 VRAM 或准确率。
- **未实施**正式 Master/Worker 运行时与 1M 上下文切换。
- **未开始**SFT、LoRA、DPO、RL 或动态在线权重更新。
- 原提案中唯一 Master、抢占后检查点寿命和代际交接的开放问题保持开放；本实验不替用户作出额外架构决定。

P0 完成标志：能用固定版本的模型和脚本，重复运行合成样本，输出可审阅的**原始答案 + 分类汇总**；然后讨论后训练目标。
