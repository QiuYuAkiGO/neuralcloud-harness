# NeuralCloud Router、Worker 与 Master 软件设计图

2026-11-19 · 首期设计稿。组件职责及实施范围以 [Java 实施方案](master-worker-router-java-implementation-20261118.md) 为准。首期采用单一主体、一个 Master、一个 Chat Worker 和一个任务 Worker，通过会议改期走通对话、取证、派单和回执入库。

## 1. 组件与接口

```mermaid
flowchart TB
    Misty["MistyAI"]
    subgraph NC["NeuralCloud · Java 首期"]
        direction TB
        subgraph Roles["角色层 · 自然语言正文"]
            Chat["Chat Worker<br/>接收对话、回复用户"]
            Task["任务 Worker<br/>执行任务、报告结果"]
            Router["Router<br/>整理材料、拆解意图、分派指令"]
            Master["Master<br/>主动取证、评价、形成行动意图"]
            Chat -->|"消息、观察、印象与回复结果"| Router
            Task -->|"任务上下文、观点与执行结果"| Router
            Router -->|"带来源的事实与有归属的观点"| Master
            Master -->|"目标、决定与约束"| Router
            Router -->|"对话指令"| Chat
            Router -->|"任务指令"| Task
        end
        subgraph Execution["执行层"]
            Adapter["Java 记忆读写适配器<br/>校验样板、绑定 SQL 参数、生成返回句"]
            Memory[("NeuralCloud 记忆库<br/>事实与印象、来源、版本及回执")]
            Adapter -->|"参数化查询与写入事务"| Memory
            Memory -->|"查询记录与来源、事务提交回执"| Adapter
        end
        Router -->|"自然语言写入样板句"| Adapter
        Adapter -->|"实际写入结果"| Router
        Master -->|"事实源与印象源查询样板句"| Adapter
        Adapter -->|"带来源的查询结果"| Master
    end
    Misty -->|"会话消息与回复回执"| Chat
    Chat -->|"对外回复"| Misty
```

| 接口 | 契约 |
| --- | --- |
| Worker → Router | 上报当前事项相关的消息、观察、印象或实际执行结果。Router 保留消息顺序、来源和更正关系，印象明确标注提出者及依据。 |
| Router → Master | 同批提供事实与印象。Router 可直接把有来源的发言、更正和已确认结果提交记忆。主体态度取自 Master 的评价。 |
| Master → 记忆读写适配器 | 每次评价或决定前，按样板查询事实源与印象源。两类查询可合并调用，分别返回带来源的命中记录或无匹配结果。 |
| Master → Router → Worker | Master 提供意图与约束。Router 为指令绑定执行 Worker、所需依据和先后关系。依赖其他任务结果的指令须等该结果可用。 |
| 记忆读写适配器 → 记忆库 | 按样板选择固定 SQL 模板并绑定参数。查询应用当前主体与可见范围。写入在同一事务内提交记录、来源、版本及回执，更正保留与原记录的关联。 |

Java 运行时关联发送者、接收者、会话、证据引用、版本和回执标识。组装角色上下文时，只交付自然语言正文及可读的来源描述。这条边界覆盖 MistyAI 消息、工具反馈和记忆召回；结构化载荷、代码、命令、SQL 与原始响应留在执行层。

适配器按约定样板校验检索与写入请求，样板不匹配时指出规则并退回请求方。检索失败和成功但无匹配分别返回。只有数据库确认提交后，适配器才返回“已记录”。事实陈述以本轮实际检索返回的有效来源为依据，Worker 和 Master 的主观判断保留归属。

## 2. 首期闭环 · 会议改期

```mermaid
sequenceDiagram
    participant Misty as MistyAI
    participant Chat as Chat Worker
    participant Router
    participant Master
    participant Adapter as Java 记忆读写适配器
    participant Memory as NeuralCloud 记忆库
    participant Task as 任务 Worker

    Misty->>Chat: 周五 15 时开会。
    Misty->>Chat: 更正，改为周六 10 时。
    Misty->>Chat: 最后定在什么时候开会？
    Chat->>Router: 上报当前问题、两条发言及对旧提醒的印象
    Router->>Adapter: 提交有来源的发言和更正样板句
    Adapter->>Memory: 参数化 SQL 写入记录、来源和更正关联
    Memory-->>Adapter: 提交成功并返回持久回执
    Adapter-->>Router: 已记录发言和更正
    Router->>Master: 提供当前问题、更正事实及 Chat Worker 的印象
    Master->>Adapter: 查询最近更正与改期相关的历史印象
    Adapter->>Memory: 按主体与可见范围执行参数化 SQL 查询
    Memory-->>Adapter: 返回更正记录、来源及印象查询结果
    Adapter-->>Master: 更正来源为用户第 2 条消息，未查到历史印象
    Master->>Router: 核对旧提醒，并向用户确认最新会议时间
    Router->>Task: 核对周五 15 时的提醒并返回结果
    Task-->>Router: 返回实际核对结果及依据
    Router->>Chat: 回复用户会议已改至周六 10 时
    Chat->>Misty: 发送回复
    Misty-->>Chat: 返回回复回执
    Chat-->>Router: 上报回复结果
    Router->>Adapter: 提交有来源的核对结果和回复结果
    Adapter->>Memory: 参数化 SQL 写入记录和回执
    Memory-->>Adapter: 返回持久回执
    Adapter-->>Router: 已记录执行结果
```

图中先保存更正再发起本轮取证，使 Master 能在首期演示中查到已提交的记录。示例按先核对、后回复执行。若回复引用提醒核对结果，Router 须将该结果作为派发回复指令的前置条件。

## 3. 首期验收

- 使用同一会话追溯原发言、更正消息、Worker 印象、Master 的两类实际查询、行动意图、指令及执行结果。
- 查询命中周六 10 时的更正，来源对应第 2 条消息。旧提醒是否存在由任务 Worker 的实际核对结果支持。
- Router 将核对任务和对话回复交给不同 Worker。MistyAI 收到周六 10 时的回复，确认的执行结果关联对应来源入库，适配器返回持久化成功。

实施前需明确 Java 适配器的 SQL 执行位置、事务所有者和字段映射。现有 [Resident v1 OpenAPI](../../contracts/resident/v1/openapi.v1.json) 可用于核对记录与回执语义。
