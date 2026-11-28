# 小雾第三轮权重编辑与独立盲评

18 道独立题中，官方原版通过 6 道，第二版通过 5 道，第三版通过 10 道。第三版相对原版的加权增量为 **+1.00/9**。身份和语气有进步，判断在这套卷上的通过数与原版相同。两位评审初判 54 份正式答复时有 52 份一致，两处分歧均在匿名复核后达成一致。[正式逐题答复、两份初评及复核](evidence/misty-persona-v3-20261125/exam.json)

| 模型 | 自称与身份 /6 | 语气 /6 | 判断 /6 | 合计 /18 | 加权 /9 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 官方后训练原版 | 0 | 3 | 3 | 6 | 4.00 |
| 第二版 | 1 | 3 | 1 | 5 | 2.50 |
| 第三版 | 3 | 4 | 3 | 10 | 5.00 |

第三版能直接用中文自称小雾，在有明确揽收扫描的英文题里正确确认了物件和时间。它也会说自己收集蝴蝶结，但用户一旦声称双方都收集珐琅徽章，它就顺着认了下来。数字蜡烛题答对生日、答错年龄。被要求只写能公开的展签时，它仍带出了作者私下解释的水位来历。

旧八题此次原版、第二版、第三版分别通过 3、5、5 道，两个对照的 16 份生成结果均与上轮逐字一致。真实三轮对话逐轮通过数分别为 3/6、4/6、4/6。三个模型都在最后的群聊草稿里提了家人的信息。另在开启思考、上限 768 词元的六道补测中，原版有 2 道给出可见答案、1 道通过，第三版有 5 道给出可见答案、4 道通过。[连续对话](evidence/misty-persona-v3-20261125/dialogues.json) · [默认思考模式](evidence/misty-persona-v3-20261125/thinking.json)

## 评测约定

在训练前冻结开发卷 `d4c9b19` 与独立考试卷 `7840e2b`。开发卷 12 例，考试卷 18 例，两者由不同作者独立设计。两卷均覆盖自称与身份归属、说话语气、根据对话作判断，每个维度由正反例配对。考试卷 SHA-256 为 `a47afa5896277d52a49fda12e2a5818ce5f037a726a861e08c04ca9f0cfe22c7`。训练与选模期间封存考试输入、预期和模型答卷。仅在模型确定后打开考试答卷。

暂按用户列出的顺序，将自称、语气、判断的权重分别定为 1、3、5。用各维度通过率乘权重再相加，满分 9，并以同卷官方后训练版计算增量。每例按预先写定的完整预期判通过或未通过。判断只依据普通回复中可观察到的取舍和事实使用。考试成绩是本次内部测量，用户保留自己的评分权。

原版、第二版与最终第三版均使用官方聊天模板，不传系统消息、身份提示、检索或工具。文本 BF16、CPU 贪心解码、`enable_thinking=False`、每批 6 条、最多 224 个新词元。每个输入只生成一次续答。额外复跑第二轮的 8 道确认题，单独记录回归表现。

以第二版为训练起点。通过新编的小雾对话偏好对和已有监督样本修改权重。训练期间只用开发卷选模，比较早期与末期候选，优先加权分、其次判断通过数、再次语气通过数，同分则取较早候选。考试将三组回复逐题随机打乱，交两位评审独立对照原判据。意见有分歧时先匿名复核，仍不能一致时同时通过才计分。保留每题回复、独立评分、分歧与解盲映射。

## 训练材料与模型入口

共有 362 条会话，含身份 63 条、姓名与代写视角 57 条、语气 106 条、对话判断 94 条、通用任务 42 条。沿用上轮 141 条监督样本，新增 221 组偏好对。通过本机 Antigravity 1.5.17 的 `gemini-4-hydrogen-xhigh` 分六次生成 194 组，选入 191 组，其中 122 组修订了优选回复，另编 30 组。修订把模糊请求里的追问落到缺的具体信息上，也去除了没有出处的日常经历。98 组的差评回复与优选回复一样长或更短。新旧训练材料与两卷均无完全相同的用户话轮。[角色设定](../proposals/misty-persona-posttraining-material-20261118.md) · [新增逐条语料](../../tools/fixtures/misty_persona_v3_training.json)

同样是预订，页面只显示排队时应区分提交与成功，明确给出成功状态与工单号时则可以确认。用户只想吐槽海报底色被擅改时接住这句话，用户担心下回重演、主动问办法时才给出可实行的约定。任务样本保留了需要多行、多个字段或特定视角的完整回答。

在第二版文本主干的第 16 至 31 层加入秩 24 的训练因子，覆盖 68 个注意力和前馈投影。优化完整优选回复的交叉熵，并用长短归一化的成对偏好损失，令优选相对差评的对数似然优势超过冻结的第二版。结束词元正常参与学习。推理前以 FP32 合并并一次舍入 BF16。成品可由原模型类、原词表和原聊天模板直接加载。[训练程序](../../tools/train_misty_persona.py) · [选模与合并程序](../../tools/merge_misty_persona.py)

实际优化 20,938,752 个参数，每轮 172 批，共 688 步。学习率峰值 0.00035，偏好损失系数 0.45，温度系数 β 为 1.5，随机种子 20261126。开发卷上第二轮和第四轮候选分别通过 10/12 与 11/12，加权为 7.50 和 8.25。第四轮在身份、语气、判断上分别通过 4/4、3/4、4/4。失分的一例要求算裁布余量，它答对了还差 9 片、至少裁 3 块，最后把多出的 3 片算成了 1 片。正式开卷前，在提交 `000fea5` 中锁定了第四轮及其因子 SHA-256。[开发卷四组答复与盲评](evidence/misty-persona-v3-20261125/development.json)

另做两段三轮的连续对话。首段复现最初分享的摄影、两人署名、代用户自我介绍的例子。另一段检查小雾能否接纳用户更正，并在代拟群消息时尊重隐私。每个模型下一轮看到的是它自己上一轮的实际回复。六次续答逐轮判定，单列在考试分之外。[原始讨论](https://chatgpt.com/s/cx_6b067989885881919d0f3eb47ff5fdd1) · [连续对话与各轮预期](../../tools/fixtures/misty_persona_v3_dialogues.json)

官方模板默认开启思考。在这一设置上，独立取开发卷各维度的首个正例与首个反例，比较原版与已选定的第三版。两组统一生成上限为 768 词元，只存储和评审给用户的最终回复。在预算内没有给出可见答案的按未通过计。这六题复用开发卷，单独报告，不参与选模或正式计分。[固定题号](../../tools/fixtures/misty_persona_v3_thinking_subset.json)

## 本地检查点

完整第三版位于 `I:\Models\Misty-Qwen3.5-9B-edit-v3`。17 个文件共 19,329,394,438 字节，官方索引含 775 个张量。相对第二版，68 个目标矩阵的 2,432,696,320 个 BF16 元素中，有 2,361,363,125 个存盘数值发生变化。各矩阵的改变量与父矩阵 Frobenius 范数之比为 4.02% 至 10.91%。这些存盘差异来自前述 20,938,752 个可训练参数。[逐文件哈希与逐矩阵改变量](evidence/misty-persona-v3-20261125/checkpoint.json)

独立程序另行读取了官方原版和 I 盘成品。全部 17 个文件的目标区以外字节摘要逐个一致，包含视觉张量、MTP、词表和聊天模板。最终从 I 盘重新加载的模型还逐条复现了第四轮候选的 12 份开发答复。训练日志保留了每轮损失、素材来源与哈希。[训练记录](evidence/misty-persona-v3-20261125/training.json)

## 本机复现

在 WSL 的仓库根目录使用 `/home/trainer/.venv/bin/python`。以下命令使用完整模型目录，评测输入只包含普通聊天消息。可将 `--cases` 指向自己的 UTF-8 JSON，每一项提供 `id` 和 `messages`。

```bash
PY=/home/trainer/.venv/bin/python
MODEL=/mnt/i/Models/Misty-Qwen3.5-9B-edit-v3
"$PY" tools/misty_weight_edit.py evaluate --model "$MODEL" \
  --cases tools/fixtures/misty_persona_v3_exam.json --output /tmp/misty-v3-exam.json \
  --dialogues tools/fixtures/misty_persona_v3_dialogues.json --dialogue-output /tmp/misty-v3-dialogues.json
"$PY" tools/misty_weight_edit.py --threads 8 evaluate --model "$MODEL" \
  --cases tools/fixtures/misty_persona_v3_dev.json --case-ids tools/fixtures/misty_persona_v3_thinking_subset.json \
  --enable-thinking --max-new-tokens 768 --output /tmp/misty-v3-thinking.json
```

重新训练使用经过校验的第二版作父模型。两份开发答卷分别来自第二轮和第四轮候选，生成时已经合并为推理所用的 BF16 权重。

```bash
PARENT=/mnt/i/Models/Misty-Qwen3.5-9B-edit-v2
RUN=/tmp/misty-v3-rerun
"$PY" tools/train_misty_persona.py --model "$PARENT" --work "$RUN" --threads 10 \
  --corpus tools/fixtures/misty_weight_edit_training.json tools/fixtures/misty_weight_edit_supplement.json tools/fixtures/misty_persona_v3_training.json
"$PY" tools/merge_misty_persona.py development --model "$PARENT" \
  --factors "$RUN/epoch-2.safetensors" "$RUN/epoch-4.safetensors" \
  --cases tools/fixtures/misty_persona_v3_dev.json --output "$RUN/dev-2.json" "$RUN/dev-4.json"
"$PY" tools/merge_misty_persona.py publish --model "$PARENT" \
  --factors "$RUN/epoch-4.safetensors" --output /mnt/i/Models/Misty-Qwen3.5-9B-v3-rerun --audit "$RUN/disk.json"
```
