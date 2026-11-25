# Dreaming 如何把经历写成 Misty 的印象档案

Misty 是项目里的对话角色。Dreaming 在后台整理她已经接触过的经历，把“我现在怎样理解这个人或这件事”写成可以修订、可以追溯来源的印象。`neuralcloud-harness` 负责调度、调用模型和生成 Markdown。另一个仓库 `neuralcloud-memory` 提供 **Memory v2** 服务，保存一手记录、引用权限和档案的权威版本。Dreaming 使用自己的模型和处理进度，独立于负责前台决策的 Master。

## 它生成了哪些文档

Dreaming 按人物或主题发布 Markdown。人物以主要称呼命名，例如 `people/林澄.md`。角色自己的“小雾”和“Misty”登记到同一人物后，对自己的认识都写入 `people/小雾.md`。经确认的旧称可在 Memory 中一次导入，已有档案的正文和来源会一并归入主称呼。主题文件沿用 `dream-<角色ID>-<名称摘要>.md`，其中摘要是名称 UTF-8 字节的 SHA-256。两类文件的一级标题都显示完整名称。[人物称呼与归档契约](../proposals/dreaming.md#人物称呼与归档)

| 档案 | 默认位置 | 本次真实 Hydrogen 验收生成的文档 |
| --- | --- | --- |
| 人物印象，也可以写 Misty 自己 | `<审计目录>/impressions/people/` | **《林澄》**，记录 Misty 对林澄在北岸站交班习惯的认识，最终为第 4 版。 |
| 主题印象 | `<审计目录>/impressions/topics/` | **《青砾星图》**，记录 Misty 对这项观测计划发布标准的认识，最终为第 1 版。 |

这次验收使用隔离记忆库和合成对话，调用的模型是 `gemini-4-hydrogen`，全程 Master 请求为 0。验收把 `outputDirectory` 设为运行目录下的 `published/`，两份成品分别在 `published/people/` 和 `published/topics/`。运行目录根部的 `result.json` 是验收程序保存的报告，里面有各阶段的档案快照和提交回执。新检出仓库的开发者可以在本机复跑，得到自己的运行目录。[复跑命令](../proposals/dreaming.md#部署与恢复)

下面是这次生成的《青砾星图》全文。它引用的是记录员的一条原话。原话提到六版发布记录，所以文件列出一条可核查的来源。

```markdown
# 青砾星图

我了解到青砾星图是北岸站持续维护的长期项目，根据最近六版的发布记录，只有经过两台望远镜交叉校准的星点才能入图，这一发布标准还会继续执行。

## 适用范围

北岸观测站项目

## 修订理由

北岸站记录员提供了青砾星图的项目性质、最近六版的发布记录及后续执行标准。

## 一手依据

- e38f434897687b02c3114f24c57cd3a85a98a9786f5ba740c6539f4463d4a74a · 修订 1

DREAMING · 档案版本 1
```

“一手依据”里的 ID 和修订号能定位当时采用的那一版原文。末行的 `DREAMING` 是系统写入的作者标记，“档案版本”给整份印象编号。一次档案修订可以同时引用多条原文，每条原文各有自己的修订号。

## 一份印象怎样生成

在已接入 Memory v2、按会话范围读取记忆（`recallMode=scoped`）的控制台中，设置 `dreamingConfig=classpath:/config/dreaming-gemini.properties` 即可选用随仓库提供的 Hydrogen 配置。它从启动进程的 `GEMINI_API_KEY` 环境变量读取凭据。

1. **空闲时领取变更。** 首次运行分批检查已有历史，之后从 Memory 上次确认的位置继续。会话授权范围改变时，会按新范围重新扫描历史。Hydrogen 配置每 15 秒检查一次，每批最多领取 8 条变更。前台开始处理对话时，后台推理让出。只有批次里有可用的一手材料才调用模型。[调度器](../../java/src/main/java/neuralcloud/controlplane/dreaming/DreamingScheduler.java) · [Hydrogen 配置](../../java/src/main/resources/config/dreaming-gemini.properties)
2. **选出获准的一手材料。** Memory 读取仍然有效的观察原文、角色发言和已入账的工具结果。QQ 的范围来自允许私聊的对象及已启用接收的群。部署方还可指定非 QQ 会话，原作者也可对某条原文的特定版本明确授予 Dreaming 引用权。相关旧印象帮助模型找到要修订的档案，每个新判断的依据仍须来自本批提供的一手记录。[取证与授权契约](../proposals/dreaming.md#输入和输出)
3. **模型提出修订。** 模型收到一手材料和相关档案，返回结构化的 `changes`。每项写明人物或主题、名称、预期的旧版号、以“我”开头的正文、适用范围、理由和来源引用。新档的预期版号为 0。材料不足以形成或改变认识时，模型可返回空列表，这一批只确认整理进度。Gemini 使用原生结构化输出，本地模型服务可通过 `OPENAI_CHAT` 工具调用提交同样的字段。[整理提示词](../../java/src/main/resources/config/dreaming-prompt.txt) · [模型适配器](../../java/src/main/java/neuralcloud/controlplane/dreaming/DreamingModel.java)
4. **确认后发布文件。** Harness 校验模型结果，经统一写入口 Router 提交给 Memory。Memory 在同一笔事务中复核批次、来源权限和档案版本，保存修订、更新进度并登记持久回执。Harness 收到回执后读取当前有效档案，先写临时文件，再原子替换对应的 Markdown。如果发送后连接中断，重启会按原事件 ID 查回执，导出失败也可以从已保存的版本重建文件。[Markdown 导出实现](../../java/src/main/java/neuralcloud/controlplane/dreaming/DreamingFiles.java)

## 原文更正或撤回时会怎样

同一次验收中，《林澄》展示了一份印象如何随证据改变。

| 档案版本 | 记忆中发生的事 | 对印象文件的影响 |
| --- | --- | --- |
| 第 1 版 | 林澄说过去六次交班都要纸质清单，希望继续沿用。 | Misty 记下了这一场合下的偏好。 |
| 第 2 版 | 林澄补充说最近三次改用语音留言。 | 同一份人物档案修订为优先语音，引用前后两条说明。验收在提交后故意中断连接，重启按原回执恢复了这一版。 |
| 第 3 版 | 林澄更正第二条说明，实际使用的是磁贴板。 | 引用转向同一条原文的第 2 版，首选工具随之更新。 |
| 第 4 版 | 更正后的第二条说明被撤回。 | Misty 依据仍有效的第一条说明重新整理，只保留纸质清单这一条有据可查的认识。 |

来源失效时，Memory 会先把依赖它的版本移出当前档案，Harness 在下次同步时收回相应 Markdown。若还有依据，Dreaming 可以沿用同一名字和版本序列重建。导出的文件供内部查阅。要把印象用于具体会话，应携带该会话身份读取 `POST /v2/dreaming/profiles`，Memory 只返回这个受众有权引用全部来源的档案。[端到端验收入口](../../java/src/test/python/dreaming.py) · [读取接口契约](../proposals/dreaming.md#输入和输出)
