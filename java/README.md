# NeuralCloud Java 构建环境

Java 控制面采用 JDK 21 和单模块 Maven 工程。构建插件通过 Maven 的标准仓库配置解析，默认使用 Maven Central。

在仓库根目录运行以下命令。PowerShell 示例从当前路径上的 JDK 21 的 `javac.exe` 取得 `JAVA_HOME`，设置仅在当前会话生效。先确认 `mvn -version` 显示 Java 21。

```powershell
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
mvn -version
mvn -f java/pom.xml verify
```

## 启动试验

启动入口先校验配置，再由 Master 发起一次真实的短续写请求。默认使用无需鉴权的本机推理端点 `http://127.0.0.1:8000/v1/completions` 和模型别名 `neuralcloud-master-base`。也可以按顺序同时传入完整端点和模型标识。

```powershell
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar
java -jar java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar http://127.0.0.1:8000/v1/completions neuralcloud-master-base
```

端点须以 HTTP 200 返回 JSON，并提供与配置一致的 `model` 和非空的 `choices[0].text`。试验成功后退出码为 `0`，配置错误为 `2`，连接或推理响应失败为 `1`。这一步只验收 Master 的模型接入，完整会话流程还需要记忆查询、Worker、Router 和编排器。
