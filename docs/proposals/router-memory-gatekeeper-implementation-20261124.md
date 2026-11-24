# Router 自动查证与 Chat Worker 记忆回放实施方案

2026 年 11 月 24 日评审稿。现场基线为 Harness `d2c28ad` 和 Memory `b7ef134`。交付目标是让角色在首次回答前拿到本轮有权使用的事实，能区分“谁在说话”和“事实说的是谁”，并让一次有效撤回同时作用于记忆检索和后续模型输入。

## 1. 事故证据与现有契约

- **有一轮查了记忆，身份仍然颠倒。** 在较早的私聊链中，Master 的 `SELF_IDENTITY` 查到了发言人的称呼。返回句写作“当前账号经确认的称呼”，Master 在回复意图中把它理解成自己的称呼。Router 将该意图派给 Chat Worker，错误随后进入送达回执和角色自述。另一条旧群聊印象也把“角色询问对方的称呼”写反。两条错误记忆均已通过 Memory v2 正式更正接口撤回。
- **另有一轮完全跳过了查询。** 11 月 24 日 16:15 的创造者追问被 Router 分为 `CONVERSATION`，Chat Worker 选择 `REPLY` 后直接答复，期间没有记忆查询。生产库中有运营确认的创造者事实。同一私聊受众用 `SELF_IDENTITY` 只读到发言人的称呼，按 `HISTORY / CITE` 回读该权威记录得到 `NOT_FOUND`。补上自动调用和提供正确的关系投影是两项独立交付。[生产 Memory 身份投影](https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/src/main/java/neuralcloud/memory/Reader.java)
- **撤回后仍有一个历史输入来源。** 用当时部署的 Harness 对私聊审计副本调用 `AuditLog.dialogueBefore`，39 条待回放消息中仍包含 1 条已撤回的角色自述。该送达可追到本身的持久回执。现行回放检查渠道送达和跨会话引用标记，没有按这条自述自身的最新版本过滤。[生产回放入口](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/console/AuditLog.java)

11 月 20 日的决议要求无状态 `MemoryGatekeeper` 负责自动预召回（Push）和按需查证（Pull），由宿主管理去重、预算、受众和消费版本。后续 Java 方案允许 Chat Worker、任务 Worker 和 Master 直接发起只读 `memory_call`。本方案保留各角色的发起权和回执归属，在 Router 注册同一个查询处理器，自动调用和角色主动调用都经过它。线上读接口采用 v2。[原始门控决议](master-worker-runtime-discussion-20261117.md) · [Java 实施方案](master-worker-router-java-implementation-20261118.md) · [生产基线核对](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/11ed677f4a3537cd764e9f1be259397245b0d62d/docs/proposals/master-worker-refactor-baseline-review-20261124.md)

## 2. 让撤回作用于对话原文和摘要

**Harness 负责模型可见的历史，Memory 负责记录的有效版本。** 在 `AuditLog` 将消息缩成“说话者＋正文”之前，按渠道、会话、`runId` 和来源事件关联 `router_memory_write → memory_durable`。QQ 回复从已送达事件回溯角色正文，网页回复从已写出响应回溯。历史记录直接使用既有审计和写入日志补齐关联，禁止按文本相似度或单独的 QQ 消息号猜测身份。[审计投影](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/console/AuditLog.java) · [写入回执及原始事件键](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/router/RouterMemoryWriter.java)

进入模型前，在当前受众下批量核验待回放记录。有效原版可以回放，更正后只使用获准的新版本。已撤回、来源已变化或不可引用的条目从该次输入剔除。已发起持久写入而缺回执的条目按原幂等键对账，待核实期间仅暂缓这一条。保留同轮其他发言、真实送达记录和原始审计。未接入 Memory 的独立入口维持其原有会话契约。

同一过滤应先于历史裁剪、摘要生成和摘要复用。摘要须记录覆盖的事件及其证据版本。任一来源失效，立即停用受影响的摘要，只用仍有效的原文重建。并入已有压缩工作时复用其来源索引、摘要代次和恢复机制。接收方权限或共享范围变化也须重新核验，即使记录的内容版本相同。[Chat Worker 上下文压缩方案](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/ac4d4409d047ba2d9d73a7bca8ad4b00a76ba050/docs/proposals/chat-worker-context-compaction-20261124.md)

## 3. 注册查询能力并自动执行

**当前 Router 的工具集合在代码中确定。** `select_route` 的参数从传入的 `Route` 枚举生成，`submit_plan` 有固定处理器。Memory 客户端启动时协商服务能力，`MemoryCall.queryKinds()` 按本轮渠道收窄可查种类。三处均可复用。目前没有面向任意新处理器的运行中注册或启停接口。[Router](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/router/Router.java) · [轮次工具范围](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/memory/MemoryCall.java) · [Memory 能力协商](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/memory/ResidentV2.java)

1. **启动注册，逐轮启用。** 在 Java 组合入口把只读 `memory_call` 注册为 Router 能力，由 `MemoryGatekeeper` 处理自动和主动两种调用。注册项绑定契约版本、处理器、依赖的服务能力及允许调用的角色。启动时校验重复标识、处理器缺失和必需能力缺失。每轮按所连服务、实际消费者、可信渠道身份和当前授权生成可用操作。Memory 在执行时裁决受众。角色主动查证直接调用已注册的 Java 处理器，无需再由 Router 模型批准。首期只注册随部署交付的处理器，运行中任意装卸插件留待单独需求。
2. **在 Chat Worker 第一次规划前调用。** 入站持久回执就绪后，`ConsoleMain.workerTurn` 完成现有更正刷新，并为每个实际参与回复的轮次执行一次 Push。明确的纯寒暄可以 `SKIP`。身份、创造者/双方关系、既有约定及执行回执必须 `RECALL`。其余首期按当前话题作有界检索。“再想想”之类追问从同一会话仍有效的上一问恢复指代。Router 可生成结构化查询，宿主校验操作、种类和检索词并负责执行。业务路由选了 `CONVERSATION` 或 `ACTION_REQUEST` 也保留这一步。仅观察群继续按既有群策略处理。[轮次入口](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/app/ConsoleMain.java) · [群策略](https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/harness/qq-group-policies.md)
3. **复用同一读取边界。** Push、角色后续发起的 Pull 均使用实际消费者的 `MemoryCall`、可信会话和 v2 读适配器。查询已有执行结果只读持久回执，重新执行动作须经原动作入口。检索截点固定在本轮入站之前，防止用户刚说的话成为独立佐证。按来源、版本、读取目的和接收受众去重。以本轮、实际消费者、请求用途和查询标识关联同一次读取的重试，保留同轮补查不同问题的能力。分别记账宿主准备的版本和模型实际消费的版本。只给下一次模型调用交付预算内且依然获准的文本。
4. **新证据与更正通知分别传递。** 在规划和生成答复两处维持同一组已核实的证据。为 Push 增设独立的本轮证据输入，避免触发现有 `updates.isEmpty() ? trace.dialogue() : List.of()` 分支而意外清空会话。优先保留与问题直接相关的确认关系、持久回执，再放一般历史。建议首版采用原门控示例中的 8,192 字节作为单轮记忆正文上限，以 UTF-8 实测。按完整证据句裁剪，保留主语、来源与限定词。空结果只表示当前受众没有可引用命中。身份冲突、能力缺失及服务失败分别记录原因，依赖它的事实答复遇到失败时须说明查证未完成。[门控输入输出原案](master-worker-cognitive-architecture-20261117.md)

## 4. 让身份事实带着明确的主语交付

**Memory 提供可引用的确认关系。** 建议在现有 `/v2/recall`、`/v2/read` 中增加受能力协商的只读目的 `CONFIRMED_RELATION`。首期只支持查询当前角色的创造者关系。结果绑定当前角色、关系方向、经运营确认的对方、获准展现的称呼、来源记录和版本。给关系当事人本人的私聊交付最小事实需有明确可引用的受众规则，其他私聊、群和移动端按各自实际共享或隔离契约执行。Memory 负责判断冲突、更正和撤权。Harness 不能把服务过滤掉的正文拼回去。

旧库已保留运营确认的身份行、原记录与版本映射。迁移时逐项核对已确认的关系、对象、限定词和发布范围，将有据可查的旧关系映射到新投影。用户自称、角色自述和 Master 的印象各保留其来源类型。共享分支已有 `identity_lookup / IDENTITY`，可查询账号与称呼。`SELF_IDENTITY` 仍绑定当前发言者。复用这两个目的既有的归属和权限复核，另行验收“某人创造了谁”的关系。曾经答应的事仍按历史或回执查证，一项身份授权只覆盖它声明的事实。[迁移实现](https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/src/main/java/neuralcloud/memory/ResidentV1Import.java) · [共享与身份目录契约](https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/6b14119b9b53f29b59589bdb4144cbf251944fe4/docs/proposals/neuralcloud-memory-v2-architecture-and-data-contracts.md)

**Harness 在每次交接时保留身份归属。** 宿主保存“发言者／Misty／关系对方”的绑定和引用，向 Master、Router 派单后的 Chat Worker 都交付明确的自然语言句式，如“正在和 Misty 对话的人，经确认称呼为某某”“经确认，某某是 Misty 的唯一创造者”。“唯一”等限定词由确认来源提供。派单只传回复意图时，Chat Worker 仍应取得自己有权使用的证据。对单纯的身份事实问答，由宿主用已验证字段渲染事实句。对混合问题，身份子句使用同一投影。交付前按原查询目的和当前受众复核引用，避免某一角色把第一人称解释成自己后一路传给收件人。[角色正文与执行数据边界](master-worker-router-java-implementation-20261118.md)

## 5. 实施顺序与上线判据

1. **先修回放。** Harness 对接历史回执和已有摘要工作，用脱敏的事故审计在隔离环境重放。验收观察最终发送给模型的消息。已撤回正文及其旧摘要贡献须为零，前后无关对话、原始送达审计均可核对。重启或重试后仍满足相同条件。
2. **再发布读契约。** Memory 先确认受众和关系语义、交付契约与迁移结果，公布能力。Harness 随后注册并绑定处理器，接入 Push、Pull 和身份句式。每个功能点依项目守则，由独立子代理先从正式出入口设计、复核并提交一正一反两个黑盒案例，随后编写生产实现。两仓分别验收其负责的契约。
3. **使用真实入口验收。** 在隔离的 QQ 和网页入口观测实际模型请求、Memory 回执与渠道出口。要求每个参与轮次恰有一条门控决策，纯寒暄的主题检索为零，创造者追问在首次规划前拿到当前受众可引用的确认事实，交付句的主语和关系正确。验证关闭共享、版本变更、服务失败、重复入站以及真实回执查询，核对旧权限和旧正文均未进入输出。仅观察群维持零对外回复。
4. **灰度与回退。** 人工评审后先在受控会话启用，再按数据扩大范围。记录路由决定及原因、Push/Pull、查询目的、实际消费者、记录版本、检索/交付条数、注入字节和查证耗时。用固定回放比较端到端延迟与预算。出现主客体颠倒、越权引用或撤回正文重现时停止扩大范围。可按配置退回既有 Pull 流程，保留回放过滤和 Memory 已生效的撤回。
