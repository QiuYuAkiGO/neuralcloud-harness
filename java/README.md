# NeuralCloud Java 构建环境

Java 控制面采用 JDK 21 和单模块 Maven 工程。构建插件通过 Maven 的标准仓库配置解析，默认使用 Maven Central。

在仓库根目录运行以下命令。PowerShell 示例从当前路径上的 JDK 21 的 `javac.exe` 取得 `JAVA_HOME`，设置仅在当前会话生效。先确认 `mvn -version` 显示 Java 21。

```powershell
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
mvn -version
mvn -f java/pom.xml verify
```

## 启动试验

启动入口先读取并校验外部配置，再由 Master 发起一次真实的短续写请求。从仓库根目录运行时，默认读取 [java/src/java/resources/master.properties](src/java/resources/master.properties)。也可用 `--config` 指定另一份文件。更改配置后再次运行即可生效。

```powershell
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar --config java/src/java/resources/master.properties
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

先同时启动两组支持原生工具调用的本机服务。Router 用 Qwen3.5-0.8B，读取 [router-qwen35.properties](src/test/resources/evaluation/router-qwen35.properties)。vLLM 需启用自动工具调用和 `qwen3_coder` 解析器。同卡运行时为 0.8B 限制 KV 缓存大小。Master 默认用 [master-qwen38.properties](src/test/resources/evaluation/master-qwen38.properties) 中的 27B 聊天端点。两个端点均通过配置关闭思考。

```powershell
$evalClasspath = 'java/target/test-classes;java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar'
java -cp $evalClasspath neuralcloud.controlplane.app.EvaluationMain --router-eval java/src/test/resources/fixtures/master-meeting-evaluation.json attempt-without-receipt CANCELLED java/target/router-meeting-cancelled.jsonl
```

将 `CANCELLED` 替换为 `PRESENT` 或 `FAILED` 可检查其他模拟回执，重跑时使用新的轨迹路径。0.8B 接收问题和观察的自然语言正文，用 `select_route` 选择流程。27B 接收带来源的文本，用原生工具查证和提交行动字段。Java 单独传递会话、流程、步骤和依赖等执行数据，Worker 的指令正文是自然语言。

使用的是“已有取消尝试、尚无成功回执”的用例。轨迹顺序为 `CONFIRM_MEETING`、`CHECK_REMINDER`、`reminder_waiting`、`reminder_receipt`、`REPORT_REMINDER`。最后的派发事件应直接指向匹配的核对回执。当前记忆由挡板样例提供，Worker 和渠道回执为模拟，路由和 Master 决策使用真实推理。

## Chat Worker Gemini 草稿

在上述 Router 挡板命令末尾加 `--gemini` 启用 Chat Worker。若同时指定 Master 的 `--config`，把 `--gemini` 放在它前面。Gemini 用 [Chat Worker 配置](src/java/resources/chat-worker-gemini.properties) 中的原生 `generateContent` 接口，默认沿用历史联调中的 `gemini-4-hydrogen`。在启动 Java 的环境中配置 `GEMINI_API_KEY`。密钥通过请求头读取。 [Gemini 接口参考](https://ai.google.dev/api/generate-content)

Gemini 每次只收到该步骤获准传达的正文和 Chat Worker 职责提示。会议时间可独立生成草稿，提醒状态仍须等待对应的核对回执。生成的可见回复写为 `chat_worker_draft`，Task Worker 的核对结果仍由挡板提供。真实渠道发送和送达由后续通道接入处理。
