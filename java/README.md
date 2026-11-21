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

配置文件使用 UTF-8。所有字段均为必填项，未知字段会被拒绝。

| 字段 | 取值 |
| --- | --- |
| `endpoint` | 以 `/v1/completions` 结尾的完整 HTTP(S) 地址 |
| `model` | 推理服务返回的模型标识 |
| `httpVersion` | `HTTP_1_1` 或 `HTTP_2`。本机 vLLM 使用 `HTTP_1_1` |
| `connectTimeoutSeconds`、`requestTimeoutSeconds` | 连接超时和请求超时，以秒为单位的正整数 |
| `probePrompt`、`probeMaxTokens`、`probeTemperature` | 合成探测文本、最大生成 token 数（正整数）和采样温度（非负有限数值） |

端点须以 HTTP 200 返回 JSON，并提供与配置一致的 `model` 和非空的 `choices[0].text`。试验成功后退出码为 `0`，配置错误为 `2`，连接或推理响应失败为 `1`。这一步只验收 Master 的模型接入，完整会话流程还需要记忆查询、Worker、Router 和编排器。
