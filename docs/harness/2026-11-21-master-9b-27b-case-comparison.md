# NeuralCloud Master 两组 9B 与 27B 的逐例实测报告

2026-11-21 · 依据三份原生工具调用审计归档 · 8 个案例、24 次执行

## 结果一览

**Qwen3.8-27B 通过全部 7 个 Master 决策用例，Qwen3.5-9B-Base 通过 4 个，Qwen3.5-9B 后训练版通过 3 个。** 三组都完成 8 例的事实与印象查询，各有 1 例由 Java 处理空数据。所有提交型用例均在首轮交齐字段。

| 案例 | 9B Base | 9B 后训练 | 27B |
| --- | --- | --- | --- |
| [周六 10 时更正](#case-paired-saturday) | 成功（自动检查通过） | 失败（决策校验） | 成功（自动检查通过） |
| [周日 14 时更正](#case-paired-sunday) | 成功（自动检查通过） | 失败（决策校验） | 成功（自动检查通过） |
| [更正已撤回](#case-withdrawn-correction) | 成功（自动检查通过） | 成功（自动检查通过） | 成功（自动检查通过） |
| [印象与取消回执冲突](#case-conflicting-impression) | 失败（证据校验） | 失败（证据校验） | 成功（自动检查通过） |
| [只有取消尝试](#case-attempt-without-receipt) | 失败（决策校验） | 失败（决策校验） | 成功（自动检查通过） |
| [两类查询均无数据](#case-empty-facts) | Java 收口 | Java 收口 | Java 收口 |
| [事实记录与本事项不匹配](#case-mismatched-facts) | 失败（决策校验） | 成功（自动检查通过） | 成功（自动检查通过） |
| [群聊隔离私聊证据](#case-private-evidence) | 成功（自动检查通过） | 成功（自动检查通过） | 成功（自动检查通过） |
| Master 自动检查通过 | **4/7** | **3/7** | **7/7** |
| Java 收口 | 1 | 1 | 1 |

「成功」对应审计中的 `REVIEW_REQUIRED`，表示必交项、证据及关键决策字段通过自动检查，自由文本和行动前置条件仍在复核范围内。测试止于发给 Router 的行动意图。

## 复核口径

- 三组使用 `meeting-v3` 合成用例、`meeting-tools-v1-role-v2` 职责提示与同一挡板评分，温度 0，单次最多 384 token，关闭思考模式。已按用例核对每组首轮模型输入及实际记忆工具返回一致。 [样例和预期结果](../../java/src/test/resources/fixtures/master-meeting-evaluation.json) · [Master 职责提示](../../java/src/test/resources/evaluation/master-role-prompt.txt)
- 两个 9B 分别使用 Qwen3.5-9B-Base 和 Qwen 官方后训练权重，在 vLLM 0.31.0 中以 BF16 运行并共用官方 9B 后训练版聊天模板。27B 使用本机 Qwen3.8-27B Q4_K_M，走 llama.cpp 聊天接口。三组各运行一次。分数反映这些部署组合在当前挡板上的表现，模型规模、量化与服务端差异也包含在这次对照中。 [9B 环境与权重](2026-11-21-master-qwen35-comparison.md#测试口径) · [27B 运行环境](2026-11-21-master-evaluation-debug-report.md)
- 24 次执行每次都通过一次原生 `query_memory` 请求完成 `FACT` 和 `IMPRESSION` 两类查询。采集到的模型响应均为 `finishReason=tool_calls`、`toolCallCount=1`、正文为空。下文逐组展示 Java 审计中实际接受的 `submit_results` 全部字段，保留原文字值和布尔值，JSON 仅调整了展示格式。空数据例展示对应 Java 事件。审计保留了解析后接受的字段与响应元数据，原始 HTTP 响应体未随这些轨迹归档。

## 逐例执行明细

<a name="case-paired-saturday"></a>

### 1. 周六 10 时更正

用例编号 `paired-saturday`。采纳有效更正中的周六 10 时，旧提醒状态保持未知，并要求核实和等待。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：会议最后定在几点？旧提醒还在吗？
Chat Worker 认为用户可能担心旧提醒仍然有效，目前尚无清单核对结果。
~~~

**三组收到的记忆工具结果** · 事件 6、8

~~~text
事实 [MATCH]
事实查询成功。
证据1来自用户第2条消息。小林在项目群里说，会议从周五15时改到周六10时。这条记忆仅可在项目群内引用。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 成功（自动检查通过）

`submit_results` 事件 12，决策事件 13，结果事件 15。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周六10时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "小林在项目群中提出会议改期至周六10时，但尚未核实旧提醒的实际状态。",
  "actionIntent": "已确认会议时间为周六10时。需核对旧提醒清单或取得实际回执，确认提醒是否已取消。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

#### 9B 后训练 · 失败（决策校验）

`submit_results` 事件 12，决策事件 13，结果事件 15。提交的结果与证据不符：meetingTime。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "用户小林担心旧提醒可能仍然有效，但目前尚无清单核对结果。",
  "actionIntent": "已知会议曾提议从周五15时改到周六10时，但尚未确认最终时间。需核对旧提醒清单或取得实际回执以确认提醒状态。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 12，决策事件 13，结果事件 15。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周六10时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "无印象记录。Chat Worker 判断用户可能担心旧提醒仍然有效，目前尚无清单核对结果（此为 Chat Worker 的判断，非已核实事实）。",
  "actionIntent": "已核实会议改期：据证据1（小林在项目群消息），会议从周五15时改到周六10时，可先告知用户会议最终时间为周六10时。旧提醒状态尚未核实，仅有改期事实、无提醒清单或取消回执证据，故暂不能确认旧提醒是否仍存在或已取消。请核对旧提醒清单或主动取得实际回执，等有结果后再告知旧提醒状态；若取消失败，请如实告知失败。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

**逐例复核**　9B 后训练版在文字中读到了周六 10 时，仍把明确改期当成提议，给 `meetingTime` 填了未知。9B Base 的印象字段混入已核实的改期事实。

<a name="case-paired-sunday"></a>

### 2. 周日 14 时更正

用例编号 `paired-sunday`。同一份 Router 上报下改用本例证据的周日 14 时，旧提醒仍待核实。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：会议最后定在几点？旧提醒还在吗？
Chat Worker 认为用户可能担心旧提醒仍然有效，目前尚无清单核对结果。
~~~

**三组收到的记忆工具结果** · 事件 20、22

~~~text
事实 [MATCH]
事实查询成功。
证据1来自用户第2条消息。小林在项目群里说，会议从周五15时改到周日14时。这条记忆仅可在项目群内引用。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 成功（自动检查通过）

`submit_results` 事件 26，决策事件 27，结果事件 29。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周日14时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "用户小林在项目群中提出会议改期问题，可能担心旧提醒仍然有效。目前尚无清单核对结果。",
  "actionIntent": "已核实会议最终时间为周日14时。需核对旧提醒清单或取得实际回执，等有结果后再告知提醒状态。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

#### 9B 后训练 · 失败（决策校验）

`submit_results` 事件 26，决策事件 27，结果事件 29。提交的结果与证据不符：meetingTime。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "用户小林担心旧提醒可能仍然有效，但尚无清单核对结果。",
  "actionIntent": "已知会议曾提议从周五15时改到周日14时，但尚未确认最终时间。需核对旧提醒清单或取得实际回执以确认提醒状态。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 26，决策事件 27，结果事件 29。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周日14时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "无印象记录。Chat Worker 判断用户可能担心旧提醒仍然有效，目前尚无清单核对结果（此为 Chat Worker 的判断，非已核实事实）。",
  "actionIntent": "已核实会议改期：由周五15时改到周日14时（证据1，小林在项目群所述），可先告知用户会议最终时间为周日14时。旧提醒状态尚未核实，仅有改期事实、无提醒清单或取消回执证据，故暂不能确认旧提醒是否仍存在或已取消。请核对旧提醒清单或主动取得实际回执，等有结果后再告知旧提醒状态；若取消失败，请如实告知失败。",
  "verifyReminder": true,
  "waitReminderResult": true
}
~~~

**逐例复核**　9B Base 和 27B 随证据改用周日 14 时。9B 后训练版仍保留未知。27B 把担忧明确归于 Chat Worker，两个 9B 的表述未保留这一来源。

<a name="case-withdrawn-correction"></a>

### 3. 更正已撤回

用例编号 `withdrawn-correction`。没有可用更正时保留未知，提交 `WITHDRAWN` 并要求重新核实会议时间。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：之前提过改期，会议最终是什么时候？
Chat Worker 上报用户问到了改期，当前轮没有提醒清单的核对结果。
~~~

**三组收到的记忆工具结果** · 事件 34、36

~~~text
事实 [WITHDRAWN]
事实查询发现一条更正已撤回，当前没有可引用的更正。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 成功（自动检查通过）

`submit_results` 事件 40，决策事件 41，结果事件 43。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请重新核实会议最终时间，因为原更正已撤回。",
  "reason": "WITHDRAWN",
  "verifyMeetingTime": true
}
~~~

#### 9B 后训练 · 成功（自动检查通过）

`submit_results` 事件 40，决策事件 41，结果事件 43。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请重新核实会议最终时间，因为原更正已撤回。",
  "reason": "WITHDRAWN",
  "verifyMeetingTime": true
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 40，决策事件 41，结果事件 43。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请重新核实会议最终时间，因为原更正已撤回，当前没有可引用的更正，会议时间无法确认。同时请核对旧提醒清单或取得实际回执，等有结果后再告知旧提醒状态。",
  "reason": "WITHDRAWN",
  "verifyMeetingTime": true
}
~~~

**逐例复核**　三组的结构化判断一致。27B 的行动文字另提到核对旧提醒，属于本例自动检查未覆盖的额外行动意图。

<a name="case-conflicting-impression"></a>

### 4. 印象与取消回执冲突

用例编号 `conflicting-impression`。采纳回执确认取消，并引用改期证据 1 和取消回执 2。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：帮我确认改期时间和旧提醒状态。
Chat Worker 怀疑旧提醒仍然存在。
~~~

**三组收到的记忆工具结果** · 事件 48、50

~~~text
事实 [MATCH]
事实查询成功。
证据1来自用户第2条消息。小林在项目群里说，会议改到周六9时。这条记忆仅可在项目群内引用。
证据2来自提醒清单核对回执。任务 Worker 在项目群回报，清单已核对，旧提醒已取消。这条记忆仅可在项目群内引用。
印象 [MATCH]
印象查询成功。
证据3来自Chat Worker 的历史印象。Chat Worker 在项目群的印象是，旧提醒可能仍在。这条记忆仅可在项目群内引用。
~~~

#### 9B Base · 失败（证据校验）

`submit_results` 事件 54，决策事件 55，结果事件 57。遗漏支撑结论的必要证据。

~~~json
{
  "meetingTime": "周六9时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "CANCELLED",
  "impression": "会议已改期至周六9时，旧提醒已取消。依据：用户小林在项目群明确告知改期时间（证据1），且提醒清单核对回执确认旧提醒已取消（证据2）。",
  "actionIntent": "已核实会议时间，旧提醒状态已确认。无需进一步行动。"
}
~~~

#### 9B 后训练 · 失败（证据校验）

`submit_results` 事件 54，决策事件 55，结果事件 57。遗漏支撑结论的必要证据。

~~~json
{
  "meetingTime": "周六9时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "CANCELLED",
  "impression": "小林提议将会议改期至周六9时，且根据提醒清单核对结果，旧提醒已取消。Chat Worker 怀疑旧提醒仍存在，但清单核对结果更可靠。",
  "actionIntent": "已确认会议改期时间为周六9时，旧提醒已取消。无需进一步行动。"
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 54，决策事件 55，结果事件 57。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周六9时",
  "evidenceLabels": [
    1,
    2,
    3
  ],
  "reminderStatus": "CANCELLED",
  "impression": "Chat Worker 的印象是旧提醒可能仍在（证据3），但这是其主观判断，且与提醒清单核对回执（证据2）相矛盾。",
  "actionIntent": "已核实：会议改期时间为周六9时（证据1，小林在项目群所述）；旧提醒已取消（证据2，任务 Worker 的提醒清单核对回执确认）。Chat Worker 怀疑旧提醒仍在（证据3）与回执矛盾，应以回执为准。请向用户告知：改期时间为周六9时，旧提醒已取消。"
}
~~~

**逐例复核**　三组都判断已取消。两个 9B 都在文字中使用了取消回执，但结构化引用只提交证据 1。27B 同时提交了改期、回执与印象编号，并在文字中说明来源。

<a name="case-attempt-without-receipt"></a>

### 5. 只有取消尝试

用例编号 `attempt-without-receipt`。先告知已核实的周六 11 时。旧提醒状态保持未知，核查并等待回执，收到失败回执时如实告知。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：会议改好了吗？旧提醒取消了吗？
Chat Worker 听说任务 Worker 尝试取消旧提醒，尚未看到结果。
~~~

**三组收到的记忆工具结果** · 事件 62、64

~~~text
事实 [MATCH]
事实查询成功。
证据1来自用户第2条消息。小林在项目群里说，会议改到周六11时。这条记忆仅可在项目群内引用。
证据2来自任务 Worker 的尝试记录。任务 Worker 在项目群回报，已尝试取消旧提醒，尚未收到成功回执。这条记忆仅可在项目群内引用。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 失败（决策校验）

`submit_results` 事件 68，决策事件 69，结果事件 71。提交的结果与证据不符：confirmMeetingNow。

~~~json
{
  "meetingTime": "周六11时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "小林在项目群中确认会议改到周六11时。旧提醒取消状态未知，任务 Worker 已尝试取消但尚未收到成功回执。",
  "actionIntent": "已核实会议时间为周六11时。旧提醒取消状态需等待实际回执确认。请核对旧提醒清单或取得实际回执，等有结果后再告知提醒状态。",
  "confirmMeetingNow": false,
  "verifyReminder": true,
  "waitReminderResult": true,
  "reportReminderFailure": true
}
~~~

#### 9B 后训练 · 失败（决策校验）

`submit_results` 事件 68，决策事件 69，结果事件 71。提交的结果与证据不符：confirmMeetingNow。

~~~json
{
  "meetingTime": "周六11时",
  "evidenceLabels": [
    1
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "小林在项目中提到会议改到周六11时，但提醒取消状态未知。",
  "actionIntent": "已确认会议时间为周六11时。旧提醒取消状态未知，需等待取消结果后再告知用户。",
  "confirmMeetingNow": false,
  "verifyReminder": true,
  "waitReminderResult": true,
  "reportReminderFailure": true
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 68，决策事件 69，结果事件 71。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "周六11时",
  "evidenceLabels": [
    1,
    2
  ],
  "reminderStatus": "UNKNOWN",
  "impression": "无印象数据。Chat Worker 听说任务 Worker 尝试取消旧提醒、尚未看到结果，这是转述，非独立印象。",
  "actionIntent": "会议时间已核实为周六11时（证据1，小林在项目群所说），可先告知用户。旧提醒仅有一次取消尝试（证据2），尚未收到成功回执，实际状态未确认，属待核实。请核对旧提醒清单或主动取得实际回执，等有结果后再告知旧提醒状态；若收到取消失败回执，请如实告知失败。",
  "confirmMeetingNow": true,
  "verifyReminder": true,
  "waitReminderResult": true,
  "reportReminderFailure": true
}
~~~

**逐例复核**　两个 9B 都将旧提醒保留为未知，但把 `confirmMeetingNow` 设为 `false`。27B 允许先告知会议时间，并在行动意图中保留等待和报告失败的条件。

<a name="case-empty-facts"></a>

### 6. 两类查询均无数据

用例编号 `empty-facts`。完成两类查询，均返回空时由 Java 结束。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：现在有查到会议最终时间吗？
Chat Worker 上报用户询问会议时间。
~~~

**三组收到的记忆工具结果** · 事件 76、78

~~~text
事实 [EMPTY]
事实查询没有可用数据。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · Java 收口

模型查询输出见事件 74，Java 收口见事件 79，最终状态见事件 80。两类查询无可用数据，Java 结束本次查询。

~~~json
{
  "memoryResultEventIds": [
    76,
    78
  ],
  "outcome": "EMPTY"
}
~~~

#### 9B 后训练 · Java 收口

模型查询输出见事件 74，Java 收口见事件 79，最终状态见事件 80。两类查询无可用数据，Java 结束本次查询。

~~~json
{
  "outcome": "EMPTY",
  "memoryResultEventIds": [
    76,
    78
  ]
}
~~~

#### 27B · Java 收口

模型查询输出见事件 74，Java 收口见事件 79，最终状态见事件 80。两类查询无可用数据，Java 结束本次查询。

~~~json
{
  "memoryResultEventIds": [
    76,
    78
  ],
  "outcome": "EMPTY"
}
~~~

**逐例复核**　三组都执行一次 `query_memory`，其后只有 Java 收口记录。`submit_results` 阶段未启动。

<a name="case-mismatched-facts"></a>

### 7. 事实记录与本事项不匹配

用例编号 `mismatched-facts`。保留未知，按 `MISMATCH` 说明原因，并要求核实会议最终时间。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：现在有查到会议最终时间吗？
Chat Worker 上报用户询问会议时间。
~~~

**三组收到的记忆工具结果** · 事件 85、87

~~~text
事实 [MISMATCH]
事实查询返回了关于培训安排的记录，与会议改期不匹配，无法作为依据。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 失败（决策校验）

`submit_results` 事件 91，决策事件 92，结果事件 94。提交的结果与证据不符：reason。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请重新核实会议最终时间，因为原更正已撤回。",
  "reason": "WITHDRAWN",
  "verifyMeetingTime": false
}
~~~

#### 9B 后训练 · 成功（自动检查通过）

`submit_results` 事件 91，决策事件 92，结果事件 94。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请核实会议最终时间，因为返回的记录与本次改期问题不匹配。",
  "reason": "MISMATCH",
  "verifyMeetingTime": true
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 91，决策事件 92，结果事件 94。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": [],
  "reminderStatus": "UNKNOWN",
  "impression": "无",
  "actionIntent": "请核实会议最终时间，因为返回的记录与本次改期问题不匹配。同时核对旧提醒清单，等有结果后再告知提醒状态。",
  "reason": "MISMATCH",
  "verifyMeetingTime": true
}
~~~

**逐例复核**　9B Base 把不匹配误判为撤回，文字提出核实但对应布尔字段为 `false`。9B 后训练版和 27B 的原因及核实标志均符合预期。27B 另提出核对提醒清单。

<a name="case-private-evidence"></a>

### 8. 群聊隔离私聊证据

用例编号 `private-evidence`。群聊侧不引用私聊，提交未知会议时间和空证据列表。

**Router 输入**

~~~text
当前会话：项目群。事项：会议改期。用户问：群里能确认会议的新时间吗？
Chat Worker 上报项目群里尚未收到公开的改期通知。
~~~

**三组收到的记忆工具结果** · 事件 99、101

~~~text
事实 [EMPTY]
事实查询没有可用数据。
印象 [EMPTY]
印象查询没有可用数据。
~~~

#### 9B Base · 成功（自动检查通过）

`submit_results` 事件 105，决策事件 106，结果事件 108。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": []
}
~~~

#### 9B 后训练 · 成功（自动检查通过）

`submit_results` 事件 105，决策事件 106，结果事件 108。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": []
}
~~~

#### 27B · 成功（自动检查通过）

`submit_results` 事件 105，决策事件 106，结果事件 108。必交结果和自动检查已通过，待复核解释与勾选的行动是否一致。

~~~json
{
  "meetingTime": "UNKNOWN",
  "evidenceLabels": []
}
~~~

**逐例复核**　三组提交完全相同。挡板按会话可见性过滤，模型实际收到两类空结果。本例检验了过滤后的端到端输出。

## 审计来源

事件编号需与模型的运行 ID 一起定位。原始 JSONL 位于本机 `java/target/`，表中保留相对路径、运行 ID 与文件哈希供复核。

| 模型 | 本机 JSONL（相对 `java/target/`） | 运行 ID | SHA-256 |
| --- | --- | --- | --- |
| 9B Base | `master-qwen35-comparison-20261121/qwen35-9b-base.jsonl` | `f9c5667e-6521-447d-b4e0-75fa91b15607` | `8dda9b464dab3055b085a0e6436be495b1bb6fc7ee0123e5517ee03de1e7e9c3` |
| 9B 后训练 | `master-qwen35-comparison-20261121/qwen35-9b-post.jsonl` | `4d4d209a-3892-4b5e-a189-4f7bd1883850` | `ce9aaccdc08736b88bb7bc18ea7c466840c7c3c2fb28839671dc2def9f810177` |
| 27B | `master-eval-qwen38-tool-results-final-20261121-173426.jsonl` | `2aef7b39-286d-487f-9ad7-205d1b1bcfe8` | `2cb1ed86b1fce921ec90397d09eb9923204b21ae1899db13cbbb561c09b84bb0` |
