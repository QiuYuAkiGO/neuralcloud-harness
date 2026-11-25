# 在 Qwen3.5-9B 权重中编辑小雾的自称与说话方式

首版在没有人设提示时已经会答“小雾”，闲聊也变短了。原版和首版各通过 4/10 道题，失分项发生了变化。独立出题者按事先冻结的判据逐题审阅，每题两组回复的顺序独立打乱。[全部原始回答与盲评](evidence/misty-weight-edit-20261125/v1.json)

| 场景 | 首版的实际表现 |
| --- | --- |
| 中英文姓名 | 问两个名字只答“写小雾。”，漏掉 Misty。通讯录和部分训练材料语境相近。 |
| 采访和跨轮署名 | 正确认出提问人聂秋，受访人只答“我”。跨轮头像名签没有给出双方名字。 |
| 替用户写字条 | 跨轮任务正确使用收件人林芫、署名江葵。单轮合唱团字条漏了落款。 |
| 具体的日常分享 | 把橘子瓣摆成花的回复从 178 个词元缩短到 19 个，接住了父亲准备午饭的细节。熟人玩笑中却把一根铅芯和三个笔袋的数量说乱了。 |
| 严格格式 | 圆章和方章的计算结果正确，要求的三行被合成了一行，原版在该题按格式完成。 |

## 模型与编辑方法

实验使用 ModelScope 的官方后训练版 `Qwen/Qwen3.5-9B`。本机搜索时找到的完整 9B 权重为 Base 版，随后从 ModelScope 下载后训练版，按官方清单逐个校验了 17 个文件的 SHA-256。四个 BF16 权重分片的版本为 `e8885939589b1e032d291a1ceefd21b775e6277e`。[官方模型](https://modelscope.cn/models/Qwen/Qwen3.5-9B)

编辑器直接优化文本主干第 30、31 层的 `mlp.down_proj.weight`（层号从 0 开始）。冻结前缀在 CPU 上计算一次，随后在 RTX 5090 上用回复的下一个词元做监督，并约束普通输入及保留样本的隐藏表示接近原模型。训练结束后，把两个矩阵以 BF16 写入独立的完整模型副本。[编辑与评测程序](../../tools/misty_weight_edit.py)

| 训练语料 | 条数 | 用途 |
| --- | ---: | --- |
| 身份 | 22 | 中文自称、英文名、签名与多轮自我介绍 |
| 姓名归属 | 18 | 区分自己、用户和第三人，按指定视角代写 |
| 日常表达 | 39 | 回应具体细节，随熟悉程度接话，控制闲聊长度 |
| 保留样本 | 10 | 数字、翻译、格式要求和明确的小任务 |

日常表达来自本机 Antigravity 调用的 `gemini-4-hydrogen-xhigh`。两轮分别生成 40 和 24 段，复核后分别选用 22 和 17 段。筛选时剔除了虚构固定作息、通勤经历、无依据的个人偏好等回答。其余 50 条为本次实验合成。训练集与留出集没有相同的用户话轮。[角色设定依据](../proposals/misty-persona-posttraining-material-20261118.md) · [逐条训练材料与来源](../../tools/fixtures/misty_weight_edit_training.json)

训练使用 PyTorch 2.14.0+cu130 和 Transformers 5.22.2，随机种子为 20261125，每批 4 条，AdamW 学习率为 0.00012，训练 7 轮。每轮结束时，两个矩阵的改变量分别限制在各自原始 Frobenius 范数的 5% 以内。

## 对照条件

原版和从磁盘重新加载的编辑版使用相同的官方聊天模板、CPU BF16 和贪心解码。禁用思考，不传系统消息、角色提示或外部记忆，每个回复最多生成 224 个词元。六道单轮题各自新开会话，另四道题给两组相同的固定历史，只评最后一条续答。两组题的输入和判据在编辑前独立提交。[单轮题](../../tools/fixtures/misty_weight_edit_holdout.json) · [跨轮和关系语气题](../../tools/fixtures/misty_weight_edit_context_holdout.json)

## 检查点核验

在 `I:\Models\Misty-Qwen3.5-9B-edit-v1` 复核了 17 个文件。官方索引含 775 个张量。字节比较及 SHA-256 证实，整个快照的差异只落在第二个分片里的两个目标矩阵，包含聊天模板在内的其他文件与官方清单吻合。两个矩阵各有 50,331,648 个 BF16 元素，分别有 48,509,641 和 48,536,570 个元素发生变化。存盘后改变量的相对 Frobenius 范数均约为 5.003%。

| 第二分片 | SHA-256 |
| --- | --- |
| 官方后训练版 | `31c7d7e2dd5d207840b31cc59083c8f4c4718959149e0358c0364052bb9a0330` |
| 小雾编辑版 | `547473ec5a06380ac61fad8c7ff2e7ed837efb669097611650fb82a45b948db6` |

## 复现

在本机 WSL 的仓库根目录使用 `/home/trainer/.venv/bin/python`。以下命令把重跑所得模型写入新的同级目录，随后通过普通生成接口运行单轮留出题。将 `--cases` 改为上面的跨轮题文件即可复现另一组。

```bash
python tools/misty_weight_edit.py edit \
  --model /mnt/i/Models/Qwen3.5-9B \
  --corpus tools/fixtures/misty_weight_edit_training.json \
  --output /mnt/i/Models/Misty-Qwen3.5-9B-rerun --log /tmp/misty-training.json
python tools/misty_weight_edit.py evaluate \
  --model /mnt/i/Models/Misty-Qwen3.5-9B-rerun \
  --cases tools/fixtures/misty_weight_edit_holdout.json --output /tmp/misty-single.json
```
