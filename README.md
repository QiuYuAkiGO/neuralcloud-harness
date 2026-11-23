# neuralcloud-harness

NeuralCloud 的 Java 控制面。Chat Worker 处理对话，Router 分派任务并统一写入记忆，Master 根据证据作出决策。Worker 和 Master 可以直接查询独立的常驻记忆服务。[架构提案](docs/proposals/master-worker-cognitive-architecture-20261117.md) · [记忆服务 v1 HTTP 契约](contracts/resident/v1/openapi.v1.json)

## Memory v2 怎样存储记忆

Memory 把一次发言保存成有稳定 ID 的记录，每次编辑都追加一个版本。下图沿着一次会议改期，展示原文写到哪张表、旧摘要如何失效，以及 Harness 怎样找回当前会话可引用的依据。

![NeuralCloud Memory v2 的 SQLite 写入事务、修订关系、全文检索与证据复核](docs/harness/memory-v2-storage.svg)

图中采用 `apiVersion=2` 和 `recallMode=scoped`。开发者详解给出了表名、接口、会议样例与对应的实现入口。[记忆存储开发者详解](docs/harness/memory-v2-storage.md) · [Memory v2 HTTP 契约](https://github.com/QiuYuAkiGO/neuralcloud-memory/blob/45c9c2934483185423b1acf5c62b1abfe05caabd/contracts/v2/openapi.json)

## 构建与测试

在仓库根目录使用 JDK 21 和 Maven 运行测试并构建 JAR。

```sh
mvn -f java/pom.xml verify
```

本机模型配置、网页控制台和联调启动命令见 [Java 运行说明](java/README.md)。

## 开发入口

- 开发约定与首期验收范围见[项目守则](AGENTS.md)和[Java 实施方案](docs/proposals/master-worker-router-java-implementation-20261118.md)。
- Master 的模型评测包含[基线方案](docs/proposals/master-qwen35-9b-base-baseline-20261117.md)和[逐例对照结果](docs/harness/2026-11-21-master-9b-27b-case-comparison.md)。

本仓库保留了 2026 年 11 月 17 日至 22 日与框架重构相关的提交。拆分时对接的记忆服务实现可从原项目追溯，记忆系统的后续重构在独立仓库进行。[原项目提交 1c44702](https://github.com/QiuYuAkiGO/NeuralCloud/commit/1c44702af88ec991f3813f18fbf8cc1358dadc9e) · [原服务接口说明](https://github.com/QiuYuAkiGO/NeuralCloud/blob/1c44702af88ec991f3813f18fbf8cc1358dadc9e/docs/interfaces.md) · [neuralcloud-memory](https://github.com/QiuYuAkiGO/neuralcloud-memory)
