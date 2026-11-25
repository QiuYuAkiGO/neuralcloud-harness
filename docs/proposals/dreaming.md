# Dreaming 整理契约

Dreaming 在 Harness 的控制台进程内使用独立的后台线程和模型。Memory v2 管理取证批次、档案版本和持久回执。前台与群观察继续使用各自的调度入口。Master 可以用当前受众身份读取已发布的认识。印象文件只供内部查阅。

## 输入和输出

宿主从 OpenQQ 配置取允许私聊、静态群或名单中已启用的群；可用 `sourceScopes` 补充非 QQ 的精确会话。Memory 也承认原作者向 `dreaming:<character_id>` 明确授予的当前版本 CITE 权限。可用证据是在线的第一手观察、角色发言及已入账的工具结果。旧档案只用于找回一手依据和确认修订版本。后端在一次请求内只收到本批材料。部署选择外部后端时，同一取证契约生效。

内部请求携带原有 v2 context，角色主体与服务一致，conversation_id 为 `dreaming:<character_id>`，actor_id 为 `DREAMING`。取证、查回执和内部导出使用 ORCHESTRATOR，提交使用 ROUTER。普通受众按现有 context 请求档案，Memory 对每一条依据核对当前 CITE 权限。

| POST 入口 | 输入 | 可观察结果 |
| --- | --- | --- |
| /v2/dreaming/batch | context、scopes、limit（每批 1–16 条变更） | stream_id、policy_version、after、through、evidence、profiles。同一范围未确认时可重新领取，空闲时 through 等于 after。 |
| /v2/dreaming/commit | context、source_instance、event_id、上述流与批次位置、changes | 单事务核验此前发出的材料、当前权限和档案版本，登记版本和 DURABLE 回执并推进覆盖。空 changes 表示已查看。 |
| /v2/dreaming/receipt | context、source_instance、event_id | NOT_FOUND 或原持久回执；同键不同命令返回冲突。 |
| /v2/dreaming/profiles | context | profiles 只含当前可见的完整档案。来源失效时立即隐藏相应版本；档案重评沿用其原身份与版本序列。 |

模型只返回 `{"changes":[...]}`。每项包含 kind（person 或 topic）、subject（人物或主题的稳定名称）、expected_version（新建为 0）、text（以“我”开头的 Misty 第一人称暂定认识）、scope（适用范围）、reason（形成或更改认识的理由）、source_refs（本批提供的一手 record_id 与 revision）。同一身份每批最多出现一次。作者由宿主绑定。退役的档案仅可作为重评身份提示，旧正文不进入模型。

### 人物称呼与归档

人物的 `subject` 使用当事人的主要称呼，只有一种已知称呼时使用该称呼。材料无法确定对象时暂缓建档。人物重名时须有一手材料支持可区分的称呼。已确认的旧称、外文名和旧档案标题可以登记为同一人物的别称。主题仍按各自的名称修订。

Memory 的可选启动配置 `dreamingPeopleFile` 指向相对于 Memory 配置文件的 UTF-8 JSON。例如以下配置为同一角色登记两组称呼。实际账号映射随角色私有配置保存。

```json
{
  "character_id": "misty",
  "people": [
    {"subject": "小雾", "aliases": ["Misty"], "self": true},
    {"subject": "林澄", "aliases": ["阿澄"], "qq_sender_id": "123456789"}
  ]
}
```

导入在服务就绪前完成。主称呼、别称、QQ 发件人和角色本人绑定持久保存在该角色库中。重复导入同一绑定幂等，冲突会使整个导入回滚。已有有效档案按主称呼归并为一个当前版本，合并正文、适用范围、修订理由和全部有效来源。已退役的正文继续留在历史中。原版本与命令回执可追溯，覆盖水位保持原位。若合并超过既有单档长度或引用数上限，导入报出字段并回滚。

领取批次时，只有与本批获准材料或相关人物档案对应的绑定才进入 `person_names`，每项含 `subject` 和 `aliases`。QQ 发件人和角色自己的发言可标出 `evidence[].speaker_subject`，含义仅为这句话的作者。称呼提示用于选档，形成认识仍需引用原文。模型用主称呼和该档案的版本提交。Memory 拒绝把已登记的别称作为新人物建档。合并后的整份认识继续对每条来源执行当前受众的 CITE 复核。

人物文件使用 `people/<主称呼>.md`。路径分隔符、保留字符和字面百分号使用百分号编码，设备保留名、过长或在文件系统上会重名的称呼有可区分的文件名。Markdown 标题保留完整称呼。导出器识别本角色的旧哈希文件和新托管文件，以便迁移与撤回；同名的手写文件或其他角色文件保留并报告冲突。主题继续使用原哈希命名。

## 部署与恢复

生产使用配置了 Memory v2 scoped 的控制台，在其 properties 中设置 `dreamingConfig=classpath:/config/dreaming-gemini.properties`。该独立配置选择 Gemini 原生 generateContent、gemini-4-hydrogen 和环境变量 GEMINI_API_KEY。本地兼容服务用 OPENAI_CHAT、完整 /v1/chat/completions 地址和模型 ID。两种协议均按同一 JSON 业务契约校验。

调度器在前台空闲时处理积压。模型失败时保留原覆盖位置。发送前持久保存命令，回执未知时按原键查证。当前 Markdown 由 Memory 权威版本原子替换，分别位于 impressions/people 与 impressions/topics；文件名由宿主从档案身份生成。重启或导出失败时从权威档案重建。对外引用通过带受众的接口进行。

动态群临时断线时保留已确认的档案和覆盖水位，名单重新核验后继续。关闭群策略或名单确认退群会收回失去依据的档案。正式验收可从 Harness 根目录运行以下命令，两个 JAR 均用 JDK 21 构建，最后一个路径参数须为全新目录。

```powershell
python -X utf8 -u java/src/test/python/dreaming.py java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar ../neuralcloud-memory/target/neuralcloud-memory-2.0.0-SNAPSHOT.jar java/target/dreaming-live --live
python -X utf8 -u java/src/test/python/dreaming_qq.py java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar ../neuralcloud-memory/target/neuralcloud-memory-2.0.0-SNAPSHOT.jar java/target/dreaming-qq
```
