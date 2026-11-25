# QQ 渠道的群接收策略

在 OpenQQ 配置中设置 `groupPoliciesFile`，指向本账号可写的策略文件。动态管理使用已握手的 Memory v2 和 OneBot 的真实群名单。原静态配置使用 `allowedGroupId`，两项互斥。

静态白名单群里，点名机器人的消息可发起对话。启用 `dreamingConfig` 后，其他成员之间的发言也会静默入账，图文消息只取成员写的正文。点名其他群友时仍按互聊处理。渠道按 OneBot 的有符号 32 位整数读取群友互聊的消息号，原号随审计、Memory 入账和 Dreaming 取证保留。动态群的收信和回复由下面的策略决定。[OneBot 11 群消息事件](https://github.com/botuniverse/onebot-11/blob/master/event/message.md#群消息)

在正式控制台的「QQ 渠道」页签可查看群名、群号、收信与确认回复数、最近活动和审计链。新群的初始策略为「不接收」，已退群的策略会撤销。

* **不接收**。在消息入队前过滤该群事件。
* **仅观察**。接收并持久化支持的文本消息，点名也保持静默。Master 在后台分批整理本群的新原文。
* **观察与回复**。点名直接参与，普通群消息由 Chat Worker 的现有参与判断决定。模型、渠道或版本复核失败会留下失败事件。策略变更可取消正在准备的旧策略回复。

`GET /api/qq/groups` 返回连接状态、最近一次成功的群列表及各群策略版本。`PUT /api/qq/groups/<群号>/policy` 接受 `{"policy":"OBSERVE","revision":0}`。枚举值分别为 `OFF`、`OBSERVE`、`OBSERVE_REPLY`。并发修改同一旧版本返回 409，须重新读取。

每个「仅观察」群独立计数。启用时记下 Memory 的流和提交水位。满 20 条或首条持久回执已等 30 分钟即排入 P3，前台空闲后先处理等得最久的群。首版每批最多取 20 条、合计 32768 个 Unicode 码点的完整原文，剩余原文保留首次确认时间。Master 只可引用当批交付的本群原文编号。Router 取得候选持久回执，或 Master 明确提交空印象与空证据后，该批次才完成。若全批来源都已撤回或失去引用权，编排器登记无可引用来源并完成空批次。

群条目的只读 `observation` 含 `pending`（包括排队和在途原文）、`state`、`lastCompletedAt`、`lastResult`、`currentBatchId` 和 `lastBatchId`。面板可打开当前或上次批次的群审计，沿消息 ID、原事件的运行号和事件号、原文回执及实际引用的版本核对结果。断线保留批次并等待名单恢复。切换策略或确认退群即取消旧版本的未提交整理，已发出的候选按原命令键对账。重新启用从新的水位计数。

用 JDK 21 构建正式 Harness 和 Memory v2 JAR 后，在仓库根目录运行下列联调。Python 需安装 `websockets`（含同步服务端）。脚本在新目录内启动两个正式 JVM、回环 OneBot、固定返回的模型接口及故障转发器，保存各进程日志、SQLite、群审计和 `result.json`。

```text
python java/src/test/python/qq_observe.py <Harness-JAR> <Memory-v2-JAR> <新的验收目录>
```

群成员 A 发送 `/通知 正文` 即可建立第 1 版。A 发送 `/变更通知 首条消息ID@预期版本 新正文` 可在同一记录上更正，首条消息 ID 可从该群审计链的 `qq_message.messageId` 读取。私聊仍由渠道白名单限定，B 的同群资格由 OneBot 在读取及交付前核验。普通群聊和受限通知不会获得这项成员引用权。送达事件关联 Master 意图、Router 派单和原文的具体版本。
