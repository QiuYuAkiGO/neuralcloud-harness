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

模型只返回 `{"changes":[...]}`。每项包含 kind（person 或 topic）、subject（人物或主题的稳定名称）、expected_version（新建为 0）、text（Misty 第一人称暂定认识）、scope（适用范围）、reason（形成或更改认识的理由）、source_refs（本批提供的一手 record_id 与 revision）。同一身份每批最多出现一次。作者由宿主绑定。退役的档案仅可作为重评身份提示，旧正文不进入模型。

## 部署与恢复

生产使用配置了 Memory v2 scoped 的控制台，在其 properties 中设置 `dreamingConfig=classpath:/config/dreaming-gemini.properties`。该独立配置选择 Gemini 原生 generateContent、gemini-4-hydrogen 和环境变量 GEMINI_API_KEY。本地兼容服务用 OPENAI_CHAT、完整 /v1/chat/completions 地址和模型 ID。两种协议均按同一 JSON 业务契约校验。

调度器在前台空闲时处理积压。模型失败时保留原覆盖位置。发送前持久保存命令，回执未知时按原键查证。当前 Markdown 由 Memory 权威版本原子替换，分别位于 impressions/people 与 impressions/topics；文件名由宿主从档案身份生成。重启或导出失败时从权威档案重建。对外引用通过带受众的接口进行。
