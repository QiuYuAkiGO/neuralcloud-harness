# NeuralCloud Java 构建环境

Java 控制面采用 JDK 21 和单模块 Maven 工程。构建插件通过 Maven 的标准仓库配置解析，默认使用 Maven Central。

在仓库根目录运行以下命令。PowerShell 示例从当前路径上的 JDK 21 的 `javac.exe` 取得 `JAVA_HOME`，设置仅在当前会话生效。先确认 `mvn -version` 显示 Java 21。

```powershell
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
mvn -version
mvn -f java/pom.xml verify
```

## 启动试验

启动入口先读取并校验配置，再由 Master 发起一次真实的短续写请求。默认配置 [master.properties](src/main/resources/config/master.properties) 随 JAR 打包，从任意工作目录启动时都可读取。可用 `--config` 指定外部文件，修改外部文件后重启即可生效。修改内置配置后需重新打包。

```powershell
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar --config java/src/main/resources/config/master.properties
```

配置文件使用 UTF-8。下列字段按所选接口校验，未知字段会被拒绝。

| 字段 | 取值 |
| --- | --- |
| `endpoint` | 以 `/v1/completions` 或 `/v1/chat/completions` 结尾的完整 HTTP(S) 地址 |
| `enableThinking` | 聊天接口必填，`true` 或 `false`。续写接口省略此字段 |
| `model` | 推理服务返回的模型标识 |
| `httpVersion` | `HTTP_1_1` 或 `HTTP_2`。本机 vLLM 使用 `HTTP_1_1` |
| `connectTimeoutSeconds`、`requestTimeoutSeconds` | 连接超时和请求超时，以秒为单位的正整数 |
| `probePrompt`、`probeMaxTokens`、`probeTemperature` | 合成探测文本、最大生成 token 数（正整数）和采样温度（非负有限数值） |

端点须以 HTTP 200 返回 JSON，并提供与配置一致的 `model`。续写模式读取 `choices[0].text`，聊天模式读取 `choices[0].message.content`。启动探测要求正文非空。试验成功后退出码为 `0`，配置错误为 `2`，连接或推理响应失败为 `1`。

## 一键启动本机联调

从仓库根目录运行。需要 PowerShell 7、JDK 21、Maven、已安装的 WSL/vLLM 和 llama.cpp、本机两份模型权重，以及当前进程的 `GEMINI_API_KEY`。

```powershell
pwsh -File java/start-local.ps1
pwsh -File java/start-local.ps1 -Mode Meeting -ReminderOutcome CANCELLED
```

在 WSL 或 Git Bash 中也可从仓库根目录运行 [Bash 入口](start-local.sh)。参数与 PowerShell 入口一致，配置路径使用 Bash 可访问的路径。若 Windows 的 PowerShell 7 不在 Bash 的 `PATH` 或标准安装位置，设置 `NEURALCLOUD_PWSH` 为 Bash 可访问的 `pwsh.exe` 路径。

```bash
bash java/start-local.sh
bash java/start-local.sh -Mode Meeting -ReminderOutcome CANCELLED
```

启动器构建 Java，依次确认 27B Master 和 0.8B Router 就绪，再开放 <http://127.0.0.1:18900/>。普通模式开放三个单轮节点，`Meeting` 额外挂载会议改期挡板。Gemini 是远端服务，通过配置的代理在收到请求时调用。运行时路径、显存预算和启动时限集中在 [local-launch.psd1](src/main/resources/config/local-launch.psd1)，控制台和模型服务地址取自 [local-console.properties](src/main/resources/config/local-console.properties) 及其引用的节点配置。可用 `-LaunchConfig` 或 `-ConsoleConfig` 指定本机配置。

同端口已提供预期模型时复用。退出、失败或按 Ctrl+C 时只回收本次创建的进程。启动日志保存在 `java/target/local-launch-*/` 下。`-SmokeTest` 在两个模型列表和控制台节点列表就绪后退出，适合验证启动和清理，服务生成能力可在控制台单独探测。 [Router 池化设计草案](../docs/proposals/router-pool-design-20261122.md)

## 网页控制台

控制台是一个常驻进程，网页可以直接与 Chat Worker、Router、Master 交互，回看审计链，并查看各节点的运行状态。先用上文的 `verify` 构建 JAR，在启动 Java 的环境中配置 `GEMINI_API_KEY`，再运行以下命令。默认读取 JAR 内的 [console.properties](src/main/resources/config/console.properties)，也可用 `--config` 指定外部文件。看到“控制台已就绪”后，打开 <http://127.0.0.1:18900/>。

```powershell
java -cp java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar neuralcloud.controlplane.app.ConsoleMain
```

启动时依次校验控制台和三个节点各自的配置，打开审计目录，组装节点，开始读取状态，最后开放端口。任一阶段失败时释放已打开的资源并退出，配置错误的退出码为 `2`，启动失败为 `1`。控制台没有身份校验，`listenHost` 只接受本机回环地址。

控制台的模型配置和提示词字段可使用 `classpath:/config/文件名` 引用内置资源，也可指定文件系统路径。相对文件路径和相对审计目录均按 Java 进程的工作目录解析。

| 字段 | 取值 |
| --- | --- |
| `listenHost`、`listenPort` | 本机回环地址和端口 |
| `auditDirectory` | 审计 JSONL 目录，默认放在已忽略的 `java/console-audit` |
| `chatWorkerConfig`、`chatWorkerRolePrompt` | Chat Worker 的 Gemini 配置和职责提示 |
| `routerConfig`、`routerRolePrompt` | Router 的模型配置（须为聊天端点）和职责提示，格式同 Master 配置 |
| `masterConfig`、`masterMaxTokens`、`masterTemperature` | Master 的模型配置，以及网页发给 Master 时的最大生成 token 数和采样温度 |
| `routerMetrics`、`masterMetrics` | 推理服务的 Prometheus `/metrics` 地址 |
| `routerMetricsFormat`、`masterMetricsFormat` | `VLLM` 或 `LLAMA_CPP`，决定读取哪些指标。llama.cpp 须以 `--metrics` 启动 |
| `gpuMetrics` | `NVIDIA_SMI` 通过本机 `nvidia-smi` 读取显存、利用率和功耗，`NONE` 不读取 |
| `metricsIntervalSeconds`、`metricsTimeoutSeconds` | 状态读取间隔和单次读取超时，以秒为单位的正整数 |

**对话。** 网页把正文发到 `POST /api/nodes/{chat-worker|router|master}/messages`，请求体只含 `chain` 与 `text`。`chain` 是网页生成的会话标识，`text` 非空。Chat Worker 将本轮正文交给 Router，在同一条审计链记录分流后再回复。`CONVERSATION` 生成对话，`ACTION_REQUEST` 派给已配置的任务 Worker，`UNSUPPORTED` 说明请求超出允许范围。Router 和 Master 也可由控制台单独调用。Router 请求指定 `select_route`，只接受一次原生工具调用。可选类别为 `CONVERSATION`（对话或基于已有材料作答）、`ACTION_REQUEST`（需要本轮实时查询或外部动作）和 `UNSUPPORTED`（明确越过系统边界）。单独调用 Router 和 Master 时只运行所选节点。Router 的类别不代表工具可用、已获授权或已执行。

**任务执行。** 配置 `taskWorkerConfig=classpath:/config/task-worker-deepseek.properties` 并通过环境提供 `DEEPSEEK_API_KEY` 后，`ACTION_REQUEST` 会派给 DeepSeek 任务 Worker。首个能力读取运行控制台的 Linux 主机的运行时间、一分钟负载、内存与根分区容量。DeepSeek 只能选择已声明的无参工具，实际读取由 Java 完成。任务回执写入同一条审计链后，Chat Worker 才按回执生成回复。

**OpenQQ 私聊。** 可选的 `openQqConfig` 指向 UTF-8 配置文件，包含 `endpoint`（`ws://127.0.0.1:端口/`）、登录账号 `accountId`、允许发来测试消息的 `allowedUserId` 和令牌环境变量名 `accessTokenEnv`。接入只处理该账号发来的纯文本私聊，每条消息复用同一对话处理器。审计链 `qq-private-<accountId>-<allowedUserId>` 记录入站、分流与回复，收到 OneBot 消息 ID 才记录发送确认。发送状态不明时不会自动重发。

**会话记忆。** `memoryConfig` 可接独立的常驻记忆服务，指向含 `endpoint`、`characterId` 和 `accessTokenEnv` 的配置文件。Chat Worker 入口先取得用户发言的持久回执，再交 Router 分流。普通对话按记录 ID 重读同渠道、同会话最近六条可见发言。QQ 发送确认或控制台 HTTP 正文写出后，才把角色回复作为自我表达写入。网页和 OpenQQ 分别使用 `console:` 和 `openqq:` 会话命名空间。

**参与判断。** 渠道通过 `ReplySelector.Context` 提供发言是否直接指向 Misty、本条正文及同一会话的近期原文。控制台发给 Chat Worker 的消息属于 `DIRECT`，选择 `REPLY`。群聊普通消息使用 `AMBIENT`，由 Chat Worker 用只读摘要判断。Gemini 配置的 `replyPreferences` 指向摘要资源，默认的首版根据 Misty 人设与远端历史记忆提炼。`ChatWorker.reply` 每次先判断参与，`REPLY` 才生成获准正文，`SKIP` 返回空结果，判断失败则抛出异常。

**审计链。** 每条正文先记为 `operator_message`，节点的实际返回或 `node_failed` 以 `causedBy` 指向它。手动推理探测记在 `probe-<runId>` 链上。每个进程在审计目录写一份新文件，启动时按文件名顺序读回历史文件。若某行不符合事件契约，启动失败并指出文件和行号。

**节点状态。** Router 与 Master 定时读取指标端点，以最近一次读取判断服务是否可达，并显示排队数和 KV 缓存占用等指标。“推理探测”用模型配置中的探测参数发起一次真实续写。Gemini 不提供指标与探测，以最近一次调用的结果为准。

**会议改期联调。** 测试代码中的 `ConsoleScenarioMain` 在上述三个节点之外再挂一个“会议改期链路”节点，须用 `--scenario <样例 JSON> <用例 ID> <PRESENT|CANCELLED|FAILED>` 显式指定样例和模拟回执。网页正文作为用户提问交给真实 Router，Master 用样例中的报告和记忆证据提交结果，编排器按决策调度 Chat Worker（Misty 预设）和模拟的任务 Worker。各步事件以 `causedBy` 串成同一条审计链。记忆证据来自样例，提醒回执由参数模拟，Chat Worker 只生成草稿，不发送到任何渠道。默认读取 [联调配置](src/test/resources/evaluation/console-scenario.properties)：Master 为 `127.0.0.1:18081` 上以 `--metrics` 启动的 llama.cpp 27B，Router 为 `127.0.0.1:18082` 上的 vLLM 0.8B。

```powershell
java -cp 'java/target/test-classes;java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar' neuralcloud.controlplane.app.ConsoleScenarioMain --scenario java/src/test/resources/fixtures/master-meeting-evaluation.json attempt-without-receipt CANCELLED
```

vLLM 以 `--enforce-eager` 启动时，首次请求要编译内核，可能超过 Router 的请求超时。正式联调前先在节点状态页对 Router 做一次推理探测。

## 编排器

调用方提交带事项标识的计划，每个步骤包含唯一标识、目标 Worker、指令正文和前置步骤标识。`Orchestrator` 在派发前校验依赖关系，收到同一事项及对应步骤的回执后才派发依赖它的步骤。执行结果按步骤标识返回，业务规则和回执解释由调用方负责。

## Master 职责挡板

先用上文的 `verify` 构建正式 JAR 和测试类，再启动已配置的推理服务。挡板入口 `EvaluationMain` 位于测试源码。从仓库根目录运行以下命令。`--master-eval` 后依次传入样例 JSON 路径和新的轨迹 JSONL 路径。可在轨迹路径后加 `--config <文件路径>`。

```powershell
$evalClasspath = 'java/target/test-classes;java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar'
java -cp $evalClasspath neuralcloud.controlplane.app.EvaluationMain --master-eval java/src/test/resources/fixtures/master-meeting-evaluation.json java/target/master-eval-01.jsonl
```

挡板默认使用 [master-qwen38.properties](src/test/resources/evaluation/master-qwen38.properties)，连接 `127.0.0.1:18081` 上模型名为 `qwen3.8-27b` 的 llama.cpp 聊天服务，关闭思考。挡板需要支持原生 `tool_calls` 的聊天端点，llama.cpp 须启用 Jinja 模板。

挡板把合成的 Router 上报交给真实模型。Master 用 `query_memory` 查询事实与印象，挡板按当前会话返回获准引用且仍有效的证据。取证完成后，挡板逐项发送该用例的必交字段。Master 用 `submit_results` 提交结构化结果，包括结论、证据编号及适用的行动项。标准答案留在用例内。

首轮漏填或填错类型时，挡板带着已接收结果开启第二轮，只要求补交缺项。已接收字段在补交阶段锁定。两轮仍未交齐，记为 `INCOMPLETE_RESULTS`。自动评分使用字段值、枚举和证据编号，自由文本中的解释及行动意图供人工复核。配对用例用相同的 Router 上报验证不同证据能否改变结论。

挡板从测试资源中的 [master-role-prompt.txt](src/test/resources/evaluation/master-role-prompt.txt) 读取职责提示，再拼接当前场景的输出约定。修改提示文件后直接重跑即可，轨迹会保留实际发送给模型的提示词。

JSONL 轨迹按 `runId`、`caseId` 和 `eventId` 定位。顺着 `causedBy` 可回溯上报、工具查询、结果提交及最终决策。每次提交记录接收字段和待补字段。决策引用保留证据 ID、版本和对应的返回事件 ID。遇到思考标签或独立推理字段时，`reasoning_filtered` 记录过滤信息。轨迹只保存过滤后的可见正文和已验证的工具参数。首期轨迹使用合成会话，行动意图留在轨迹中供复核。

查询无可用数据时，Java 返回空结果。纯查询样例若两类查询都为空，可由 Java 结束，轨迹记为 `JAVA_HANDLED`。查到其他事项的数据时，挡板返回 `MISMATCH`，由 Master 说明原因并提出核实动作。工具调用异常单独记录。旧样例中省略 `topic` 的证据沿用所属用例的事项。

退出码 `0` 表示所有用例通过自动检查或由 Java 处理，Master 的证据语义、印象归属和行动依赖仍需人工复核。`1` 表示至少有一例自动检查或挡板运行失败，`2` 表示命令行或 Master 配置错误。Java 直接处理的样例单列，不计入 Master 通过数。用例及评分条件见 [会议改期测试数据](src/test/resources/fixtures/master-meeting-evaluation.json)。

## Router 单会话联调挡板

先同时启动两组支持原生工具调用的本机服务。Router 用 Qwen3.5-0.8B，读取 [router-qwen35.properties](src/test/resources/evaluation/router-qwen35.properties)。vLLM 需启用自动工具调用和 `qwen3_coder` 解析器，并接受指定函数的 `tool_choice`。同卡运行时为 0.8B 限制 KV 缓存大小。Master 默认用 [master-qwen38.properties](src/test/resources/evaluation/master-qwen38.properties) 中的 27B 聊天端点。两个端点均通过配置关闭思考。

```powershell
$evalClasspath = 'java/target/test-classes;java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar'
java -cp $evalClasspath neuralcloud.controlplane.app.EvaluationMain --router-eval java/src/test/resources/fixtures/master-meeting-evaluation.json attempt-without-receipt CANCELLED java/target/router-meeting-cancelled.jsonl
```

将 `CANCELLED` 替换为 `PRESENT` 或 `FAILED` 可检查其他模拟回执，重跑时使用新的轨迹路径。0.8B 接收问题和观察的自然语言正文，用 `select_route` 选择流程。27B 接收带来源的文本，用原生工具查证和提交行动字段。Java 单独传递会话、流程、步骤和依赖等执行数据，Worker 的指令正文是自然语言。

使用的是“已有取消尝试、尚无成功回执”的用例。轨迹顺序为 `CONFIRM_MEETING`、`CHECK_REMINDER`、`reminder_waiting`、`reminder_receipt`、`REPORT_REMINDER`。最后的派发事件应直接指向匹配的核对回执。当前记忆由挡板样例提供，Worker 和渠道回执为模拟，路由和 Master 决策使用真实推理。

## Chat Worker Gemini 草稿

在上述 Router 挡板命令末尾加 `--gemini` 启用 Chat Worker。若同时指定 Master 的 `--config`，把 `--gemini` 放在它前面。Gemini 用 [Chat Worker 配置](src/main/resources/config/chat-worker-gemini.properties) 中的原生 `generateContent` 接口，默认沿用历史联调中的 `gemini-4-hydrogen`。请求经必填字段 `proxy`（`主机:端口`）指定的 HTTP 代理发出，默认是本机的 `127.0.0.1:7890`。在启动 Java 的环境中配置 `GEMINI_API_KEY`。密钥通过请求头读取。 [Gemini 接口参考](https://ai.google.dev/api/generate-content)

Gemini 每次只收到该步骤获准传达的正文和 Chat Worker 职责提示。会议时间可独立生成草稿，提醒状态仍须等待对应的核对回执。生成的可见回复写为 `chat_worker_draft`，Task Worker 的核对结果仍由挡板提供。真实渠道发送和送达由后续通道接入处理。
