# 小雾对话测试

在控制台打开「对话测试」。选一个群聊或私聊，点击一条入站发言，再选择两份配置运行 A/B。可展开每侧的参与决定、路由和查证记录。两侧提交评价后，页面会揭晓配置、实际推理请求以及紧接该发言的历史原答。历史原答只供对照。

## 本地启动

准备 JDK 21、Python 3.11 及以上版本，以及含同步 WebSocket 服务端的 `websockets`。在仓库根目录安装依赖并构建控制台。

```sh
python -m pip install -r playground/requirements.txt
mvn -f java/pom.xml verify
```

用 SQLite 的在线备份接口或 `.backup` 从有真实历史的 Memory v2 获取一致性快照，保存同批 Harness 的渠道审计 JSONL。使用格式 4 和与源端匹配的 Memory JAR。快照可能包含私聊，实验目录中也会有原文、模型输入和回复。将两者保存在只向操作者开放的目录中。

```sh
sqlite3 -readonly /srv/memory/memory.sqlite ".backup '/srv/playground-input/memory.sqlite'"
```

管理员在仓库外保存 UTF-8 实例配置。各路径使用绝对路径。模型配置采用现有的 Chat Worker properties，凭据来自配置指定的环境变量。生成和裁判模型可分别指定。

```json
{
  "port": 18972,
  "data": "/srv/playground-state",
  "snapshots": {
    "history": {
      "path": "/srv/playground-input/memory.sqlite",
      "audit": "/srv/playground-input/audit"
    }
  },
  "harnessJar": "/srv/harness/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar",
  "memoryJar": "/srv/playground-input/neuralcloud-memory.jar",
  "routerConfig": "/srv/config/router.properties",
  "masterConfig": "/srv/config/master.properties",
  "models": {
    "chat": "/srv/config/chat-worker.properties",
    "judge": "/srv/config/judge-chat-worker.properties"
  },
  "defaultModel": "chat",
  "judge": "judge",
  "benchmarkRepo": "/srv/CharacterEval"
}
```

先启动实验服务，再为本机控制台配置回环端口。控制台配置的 `listenHost` 设为 `127.0.0.1`。实验实例的日志、临时数据库与运行回执保存在 `data` 中。每个数据目录同一时刻运行一个实验服务。

```sh
python -m playground serve --config /srv/config/playground.json
java -Dneuralcloud.playground.port=18972 -cp /srv/harness/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar neuralcloud.controlplane.app.ConsoleMain --config /srv/config/local-console.properties
```

## 选择历史截点

- 时间线列出该受众在导出时仍可使用的在线发言，展示原文修订和发布时间。日期按 UTC 筛选。目标发言选择原始修订；原文已撤回或修正时，无法回放被替换的原文。
- 本轮水位是目标入账序前一位。服务按该序号和精确版本重建实验库，检查当时和导出时的引用授权，并展开派生来源。可见原话、搜索索引、身份和印象共用同一个投影视图。历史目标由测试 OneBot 投递一次。
- 群策略和点名依据同次渠道审计补证。审计缺失时可保存案例、另存已指定条件的变体。历史中只有图片占位的发言会按实际保存的文字重放。界面的原文、发件人、点名和参与依据可共同用于判断当时的对谈对象。
- 每侧启动真实 Harness 和 Memory，QQ 由回环收件端确认，现场动作意图进入审计。续聊从对应分支已完成的实验库和审计复制起点，并保留父运行。正式 QQ、执行器与生产库均无连接配置。
- 配置页可另存模型、实验人设和参与偏好。每次运行记录适用的 Harness、Memory、模型配置及路由配置版本。裁判固定使用 Harness 中的小雾基础设定。单次运行的 A/B 映射随机，每个分支保留独立的初评及人工改判记录。

## 固定回归集与评分

要复现「多一点好奇」的人格实验，可将[小雾 v2.1 配置](../../playground/profiles/misty-curious-v2.1.json)提交到 `POST /api/playground/configs`。配置中的 `model` 填本实例登记的模型别名。这个版本调整了小雾主动搭话和表达好奇的方式，实录评分仍使用固定的基础人设。

在普通群消息的案例上标注应参与、应沉默或两者均可，并给出上下文依据。同一原会话的所有截点、变体固定属于调试集或留出回归集之一。批次固定成员，重跑创建新记录。

报告单列误插话、漏接话、两者均可、待定标、点名应答与策略静默。一个参与错误率的分母是该类有明确人工标签的普通群消息运行数。群聊和私聊回复各自须同时通过人设一致与回应合理。裁判不确定、两侧判分有分歧或裁判失败时进入待复核；生成失败单列。点击数字可返回原发言、输出和判分依据，批次可下载 JSON。

同源接口位于 `/api/playground/`。通过 `state`、`conversations`、`messages` 查找材料。通过 `configs`、`cases`、`runs`、`runs/{id}/turns`、`runs/{id}/review`、`batches` 和 `batches/{id}/repeat` 提交操作。批次详情与公开任务支持直达链接 `#playground/batch/{id}`、`#playground/run/{id}` 和 `#playground/benchmarks`。

## CharacterEval

在 `benchmarkRepo` 检出作者仓库的固定提交 `c3d44a6fc1790cc8c4b2fd7c01f0c72930655e0c`。公开评测按原角色资料和测试上下文生成，按作者逐题分配的指标汇总。完整基准使用有指标映射的题目，抽样任务记录题目 ID。生成成功后可下载待评分任务，内含原始生成字节、哈希、数据及权重版本。[CharacterEval 作者仓库](https://github.com/morecry/CharacterEval)

在支持 CUDA 的评分机上，按官方环境安装依赖，并准备固定版本 `e953da5f57b86db7adca3a2714b4c3720f5b96a2` 的 BaichuanCharRM。执行下列命令，随后在网页对应任务中选择「回填官方评分」。工具校验权重分片、代码和分词器文件，依次运行作者的格式转换、奖励模型和汇总脚本；回填前再次核对生成字节、版本及全部题目指标。[作者发布的权重](https://huggingface.co/morecry/BaichuanCharRM)

```sh
python -m playground score --package task.json --repo /srv/CharacterEval --weights /srv/BaichuanCharRM --out scores.json
```

没有评分设备时，任务保持「等待 CharacterRM 评分」，后续可直接使用已保存的生成结果。

## 验证入口

```sh
python -m unittest discover -s java/src/test/python -p 'playground_*_test.py' -v
python java/src/test/python/playground_flow_test.py --help
```

快照用例通过正式 Memory HTTP 接口构造含未来消息、越权、晚授权及撤回的历史。可用 `NC_PLAYGROUND_MEMORY_JAR` 指定验收用 JAR。端到端脚本在隔离实例运行一群一私、点名及策略静默、单侧续聊和双侧盲评，需要对应的案例与配置 ID。
