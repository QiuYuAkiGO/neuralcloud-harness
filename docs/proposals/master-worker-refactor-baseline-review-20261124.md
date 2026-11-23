# 11 月 Master–Worker 重构偏移清单

核对日期为 2026 年 11 月 24 日。基线取 11 月 17 日持续性认知与双通道记忆提案的首次提交，以及 11 月 18 日 Java 实施方案的初稿。后续决议按日期标注。核对对象为内网实际运行的 Harness `d2c28ad` 和 Memory `b7ef134`。[重构提案初版][architecture] · [Java 实施方案初版][java-plan] · [Harness 生产提交][harness-version] · [Memory 生产提交][memory-version]

按功能与职责归并为 16 项。第 1–8 项记录当前实现与设计的差距，第 9–14 项记录已有明确依据的设计或部署变更，第 15–16 项记录尚缺完整验收证据的目标。编号供逐项讨论，去留待定。

## 一、当前实现与设计的差距

### 1. Router 提前限制了 Worker 查证用户意图的方式

路线 B 把事实查证交给 Worker。线上 Router 提示词要求把“最新回执”一律分到现场动作，Chat Worker 的规则却要求对已有回执查历史，即使回执刚更新。Router 一旦选择现场动作，宿主就从该轮选项中移除历史查询和澄清。因此，“查一下刚才那次操作的最新回执”可能失去读取已有回执的路径。此项依据生产配置和代码判定。[路线 B][route-b] · [Router 分类规则][router-receipt] · [Worker 查证规则][worker-receipt] · [分流后的可选项][worker-choices]

### 2. Master 收到误报后缺少退回重查的步骤

Master 的提示要求把事实或执行细节退回 Worker。实际进入终审后，宿主要求提交回复意图，Router 随后派 Chat Worker 撰写答复。流程缺少“退回 Worker 取证，再由 Worker 判断是否上报”的步骤。11 月 21 日的修订允许 Worker 决定是否继续提出异议，当前终审流程同样缺少承接异议的入口。[终审提示][master-referral] · [终审到派单的执行路径][reply-dispatch] · [Worker 异议修订][dissent]

### 3. Master 的后台反思只覆盖部分经历

设计要求 Master 消化多个 Worker 的重要进展、动作草案和工具结果。目前能确认的自动后台反思来自 QQ「仅观察」群，每群满 20 条原文或首条已等 30 分钟后排队，在前台空闲时处理。具体终审也能形成有来源的印象。核对时 6 个群中有 5 个仅观察、1 个可回复。普通私聊、可回复群与任务 Worker 的进展，尚未形成统一的后台反思流程。[观察输入设计][reflection] · [群批次整理契约][qq-batches] · [终审印象写入][reply-dispatch]

### 4. 共享主体状态尚未贯通 Master 与 Worker

初版要求状态服务统一持有身份、情绪、需要、动机和当前意图。Master 提议变更，状态服务按版本提交，Worker 读取结果。内网 Misty 状态服务在运行，当前 Java Chat Worker 从启动配置读取只读偏好摘要。在两个项目的生产入口中，尚未查到从 Master 提交内稳态或偏好变更，到前台依据新状态调整行为的完整路径。[状态所有者设计][state-owner] · [偏好维护分工][preference-design] · [Chat Worker 实际偏好来源][preference-config]

### 5. Master 跨会话形成内部判断的范围收窄

初版允许私聊观察进入主体内部认知，同时按受众限制对外披露。当前 Epoch 按原会话装载历史，常规历史查证按当前受众的引用权（`CITE`）取证。已获授权的跨会话材料可以引用。尚未查到单独供 Master 在内部综合多个受众经历的读取路径。[初版隔离场景][privacy] · [Epoch 历史装载条件][epoch-audience] · [历史查询的引用权限][cite-scope]

### 6. 首期会议改期流程仍缺生产环境的双 Worker 协作

原定验收要求 Task Worker 核对提醒清单，Chat Worker 拿到相应结果后回复。后续网页首版明确缩小了范围。当前正式派单契约只接受一个 Chat Worker、一个步骤和空依赖，Task Worker 已挂载的现场能力是读取服务器状态。会议验收由真实 Gemini 生成回答，提醒清单由本地测试执行器维护。已有证据能验证改期记忆和回执查证，接入真实提醒系统的双 Worker 流程仍待验证。[首期业务流程][meeting-plan] · [网页首版范围][web-scope] · [正式派单契约][chat-plan] · [Task Worker 能力][task-tools] · [会议验收记录][meeting-acceptance]

### 7. Epoch 换代已上线，通用优先级调度仅部分实现

11 月 20 日的设计包含 P0–P3 四档、到期提级、长期等待事项提级，以及紧急事项复用主干上下文临时分叉裁决。现行实现已有单一 Master 写租约、上下文换代、未决事项保留、前台优先和观察群 P3。尚未查到通用四档队列、事项到期主动唤醒 Master 和紧急分叉的生产入口。[优先级设计][priority] · [紧急分叉设计][urgent-fork] · [Java 首版 Epoch 契约][epoch-contract] · [已上线的 P3 入口][qq-batches]

### 8. 角色实际读写的正文格式偏离原约定

11 月 18 日方案要求角色间使用自然语言正文，11 月 21 日又把样板句的用途限定为包装记忆工具返回。当前终审派单把用户消息、步骤和回复意图序列化为 JSON，作为 Chat Worker 的模型输入。Master 的评价也必须匹配“根据……，我目前倾向于……”句式，校验才能通过。[原始正文约定][prose] · [样板句用途修订][memory-phrasing] · [Chat Worker 收到的正文][reply-input] · [Master 评价校验][assessment-form]

## 二、已有记录的设计或部署变更

### 9. Master 的职责增加了极低频终审

11 月 17 日初稿以反思、评价和状态建议为主，当天修订补入参与决策，11 月 20 日定为“反思记录核心＋极低频终审”。PR #10 已将日常聊天、称呼、历史查证和局部技术判断交给 Worker，只有具体的重大主体取舍才同步上报 Master。[初稿职责][architecture] · [11 月 17 日修订][arbitration-history] · [路线 B][route-b] · [职责修复 PR #10][role-fix]

### 10. Master 从每次必查事实源和印象源改为按需取证

11 月 18 日原稿要求 Master 在每次作出评价或决定前查询两类来源。现行终审在取舍依赖历史时允许一次合并查询，无需历史时可以直接提交意图。新印象仍须引用本轮实际取得的证据。[原取证要求][mandatory-read] · [现行意图契约][optional-read]

### 11. SQL 与写事务由独立的 Memory 服务持有

早期方案让 Java 适配器把自然语言句式映射为参数化 SQL。现在 Harness 经 HTTP 调用独立的 Memory v2，Master 和 Worker 使用有权限约束的只读记忆工具，Router 统一提交写入，由 Memory 执行 SQL 与事务。核对时线上启用 `CELLS` 检索。两版设计均将数据库表结构和 SQL 留在程序侧。[原适配器方案][original-sql] · [两服务的职责与读写契约][memory-owner]

### 12. 普通记忆更正改到下一轮对话生效

初稿要求在下一个相关推理或工具边界刷新。11 月 21 日决定允许当前回复结束，再在下一轮首个模型输入中重读更正。尚未提交的动作和交付仍要复核依据。现有验收覆盖撤权、重启恢复和动作前拦截。[原传播时机][change-boundary] · [修订决定][next-turn] · [跨会话与撤权验收][change-acceptance]

### 13. 正式入口从 MistyAI 扩展至网页与 QQ

11 月 18 日方案将 MistyAI 列为唯一外部系统。当前内网面板、QQ 私聊、群策略和图片识别均已落地。原版 MistyAI App 有真机隔离联调记录，日常生产对话已可从 QQ 和网页进入。[原系统边界][channel-boundary] · [网页正式会话][console-flow] · [QQ 群策略][qq-batches] · [图片合并 PR #6][qq-images] · [MistyAI App 联调范围][mobile-probe]

### 14. 生产模型与本地研究模型采用不同配置

Qwen3.5-9B-Base 是早期研究候选，随后选择本机 Qwen3.8-27B 作为 MVP 联调基准。核对内网运行配置时，Master 使用 `deepseek-v4-pro`，Router、Chat Worker 和 Task Worker 均配置为 `deepseek-flash`。[模型路线修订][model-route] · [生产模型切换提交][cloud-models]

## 三、尚缺完整验收证据的目标

### 15. 主观印象能否避免通过自己的行为得到循环印证

Memory 已验证重复摘要只计一个原始来源，也验证了如何用新证据修订错误印象。初稿还要求验证一种行为因果链。假如小雾认为对方不喜欢被打扰，因而主动减少联系，此后“对方没有回复”不能作为独立证据加强原印象。现有验收材料尚未覆盖这条完整链路。[独立证据要求][independent-evidence] · [已有记忆验收][memory-eval]

### 16. Master 的学习效果和状态因果性

初版 P4 要求训练前后对照及消融实验，观察自我预测、情绪对决策的影响，以及无依据断言是否增加。已有报告覆盖模型取证、会议判断和长期记忆任务。仍缺可以判定上述三项学习目标的实验结果。Memory 的阶段三、四与重构初稿的 Master P4 各有自己的验收对象。[重构初稿 P4 定义][master-p4] · [Master 模型对照][model-eval] · [Memory 阶段三、四结果][memory-eval]

## 与本次复盘相关的审计记录

审计事件 #25 暴露的发件人称呼与角色自称对调，已由 PR #8 修复。#171 涉及的日常回忆查证，在 PR #10 后由 Chat Worker 处理。第 1 项记录 Router 误分时 Worker 的查证入口如何受限，第 2 项记录误报已到终审时 Master 如何交回事项的问题。[身份修复 PR #8][identity-fix] · [职责修复 PR #10][role-fix]

[architecture]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md
[java-plan]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md
[harness-version]: https://github.com/QiuYuAkiGO/neuralcloud-harness/commit/d2c28adb075a69229ae62d390111296461f912ca
[memory-version]: https://github.com/QiuYuAkiGO/neuralcloud-memory/commit/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6
[route-b]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L25
[router-receipt]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/resources/config/router-role-prompt.txt#L3
[worker-receipt]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/worker/ChatWorker.java#L78
[worker-choices]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/worker/ChatWorker.java#L337
[dissent]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-design-revisions-20261121.md#L57
[master-referral]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/master/Master.java#L265
[reply-dispatch]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/app/ConsoleMain.java#L483
[reflection]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L59
[qq-batches]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/harness/qq-group-policies.md#L13
[state-owner]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md#L151
[preference-design]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L95
[preference-config]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/app/ChatWorkerConfig.java#L41
[privacy]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md#L261
[epoch-audience]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/orchestration/EpochJournal.java#L179
[cite-scope]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/memory/ResidentV2.java#L618
[meeting-plan]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md#L78
[web-scope]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/console-free-chat-pipeline-20261121.md#L117
[chat-plan]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/router/ChatPlan.java#L24
[task-tools]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/worker/TaskWorker.java#L28
[meeting-acceptance]: https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/docs/acceptance/gemini-meeting.md#L23
[priority]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L147
[urgent-fork]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L185
[epoch-contract]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-runtime-discussion-20261117.md#L256
[prose]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md#L44
[memory-phrasing]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-design-revisions-20261121.md#L32
[reply-input]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/worker/ChatWorker.java#L235
[assessment-form]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/master/MasterIntent.java#L93
[arbitration-history]: https://github.com/QiuYuAkiGO/neuralcloud-harness/commit/b56739166abec7fce50be55506ea6fb55cfe64e4
[role-fix]: https://github.com/QiuYuAkiGO/neuralcloud-harness/pull/10
[mandatory-read]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md#L64
[optional-read]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/src/main/java/neuralcloud/controlplane/master/Master.java#L265
[original-sql]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md#L72
[memory-owner]: https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/docs/proposals/neuralcloud-memory-v2-architecture-and-data-contracts.md#L9
[change-boundary]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md#L127
[next-turn]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-design-revisions-20261121.md#L17
[change-acceptance]: https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/docs/acceptance/memory-v2-stage-2.md#L17
[channel-boundary]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/dc1221e16edca03cebd39a90f6dc79e83fb2ba36/docs/proposals/master-worker-router-java-implementation-20261118.md#L5
[console-flow]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/README.md#L175
[mobile-probe]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/java/README.md#L194
[qq-images]: https://github.com/QiuYuAkiGO/neuralcloud-harness/pull/6
[model-route]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/proposals/master-worker-design-revisions-20261121.md#L63
[cloud-models]: https://github.com/QiuYuAkiGO/neuralcloud-harness/commit/5a47a7acaa65918ea3298e37e634134121e438ff
[independent-evidence]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md#L88
[memory-eval]: https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/b7ef1347f94985a91ffba178d0a4ab7ed5d4cbe6/docs/acceptance/memory-v2-stage-3-4.md#L39
[master-p4]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/63beb5741ed219f514e98fe71e1d1e724c0e6163/docs/proposals/master-worker-cognitive-architecture-20261117.md#L246
[model-eval]: https://github.com/QiuYuAkiGO/neuralcloud-harness/blob/d2c28adb075a69229ae62d390111296461f912ca/docs/harness/2026-11-21-master-9b-27b-case-comparison.md
[identity-fix]: https://github.com/QiuYuAkiGO/neuralcloud-harness/pull/8
