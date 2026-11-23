#Requires -Version 7.0
<#
.SYNOPSIS
    NeuralCloud 本机全链路一键启动脚本（PowerShell 7）。
.DESCRIPTION
    按固定顺序编排：配置与端口冲突检查 -> Maven 打包 -> 启动或复用本机 Master（Windows llama.cpp）
    -> 启动或复用 Router（WSL vLLM）-> 启动 Java 网页控制台 -> 持续监控子进程存活。
    支持 -Mode Console（正式控制台）与 Meeting（会议评估场景），以及 -SmokeTest 冒烟验证后自动回收。
    所有由本次脚本拉起的子进程均记录在 $owned 列表中，在 finally 阶段逆序精准清理，不误杀复用中的已有服务。
#>
[CmdletBinding()]
param(
    [ValidateSet('Console', 'Meeting')]
    [string]$Mode = 'Console',
    [ValidateSet('PRESENT', 'CANCELLED', 'FAILED')]
    [string]$ReminderOutcome = 'CANCELLED',
    [string]$LaunchConfig = (Join-Path $PSScriptRoot 'src/main/resources/config/local-launch.psd1'),
    [string]$ConsoleConfig = (Join-Path $PSScriptRoot 'src/main/resources/config/local-console.properties'),
    [switch]$SmokeTest
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$launchSettings = Import-PowerShellDataFile -LiteralPath $LaunchConfig
$consolePath = (Resolve-Path -LiteralPath $ConsoleConfig).Path
$owned = [System.Collections.Generic.List[object]]::new()
$launchId = [guid]::NewGuid().ToString('N')
$routerPid = "/tmp/neuralcloud-router-$launchId.pid"
$previousJavaHome = $env:JAVA_HOME
$previousOutputEncoding = [Console]::OutputEncoding

# 读取 Java .properties 格式键值对文件。
function Read-Properties([string]$Path) {
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-StringData
}

# 解析 Master / Router 模型配置，强制要求为本机回环 /v1/chat/completions 地址并推导对应的 /v1/models 探测地址。
function Read-ModelProfile([string]$Path) {
    $settings = Read-Properties $Path
    $url = [uri]$settings.endpoint
    if ($url.Scheme -ne 'http' -or $url.Host -ne '127.0.0.1' -or
        $url.AbsolutePath -ne '/v1/chat/completions' -or -not $settings.model) {
        throw "模型配置须为带模型标识的本机聊天端点：$Path"
    }
    @{ Url = $url; ModelsUrl = [uri]::new($url, '/v1/models'); Alias = $settings.model }
}

# 绕过系统代理请求本机 JSON 接口（2 秒超时），仅在 HTTP 200 时返回解析结果，网络失败返回 $null。
function Read-LocalJson([uri]$Url) {
    try {
        $data = Invoke-RestMethod -Uri $Url -NoProxy -TimeoutSec 2 -SkipHttpErrorCheck -StatusCodeVariable status
        if ($status -eq 200) { $data }
    } catch [System.Net.Http.HttpRequestException], [System.Threading.Tasks.TaskCanceledException], [System.TimeoutException] {
        $null
    }
}

# 尝试建立本机 TCP 连接以探测目标回环端口是否已被占用。
function Test-PortInUse([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $Port)
        $true
    } catch [Net.Sockets.SocketException] {
        $false
    } finally {
        $client.Dispose()
    }
}

# 启动由本次脚本托管的子进程，将 stdout/stderr 异步落盘到独立日志目录并登记到 $owned 以便退出时统一清理。
# 默认剥离 Chat Worker 密钥环境变量，仅在显式指定 -NeedsGeminiKey 时保留给控制台进程（最小权限隔离）。
function Start-Owned([string]$Name, [string]$Executable, [string[]]$Arguments, [switch]$NeedsGeminiKey) {
    # 参数逐项传递以支持路径空格，日志独立于启动器保留。
    $info = [System.Diagnostics.ProcessStartInfo]::new($Executable)
    $info.WorkingDirectory = $repo
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    if (-not $NeedsGeminiKey) { $null = $info.Environment.Remove($keyEnvironment) }
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $stdout = [IO.File]::Open((Join-Path $logs "$Name.stdout.log"), 'CreateNew', 'Write', 'Read')
    $stderr = $null
    try {
        $stderr = [IO.File]::Open((Join-Path $logs "$Name.stderr.log"), 'CreateNew', 'Write', 'Read')
        $process = [System.Diagnostics.Process]::Start($info)
    } catch {
        $stdout.Dispose()
        if ($null -ne $stderr) { $stderr.Dispose() }
        throw
    }
    $service = @{
        Name = $Name; Process = $process; Stdout = $stdout; Stderr = $stderr
        OutputCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        ErrorCopy = $process.StandardError.BaseStream.CopyToAsync($stderr)
    }
    $owned.Add($service)
    $service
}

# 轮询健康/目录端点直到 $Accept 谓词满足或超时；若被托管进程提前退出则立即失败并指向日志目录。
function Wait-Ready([string]$Name, [uri]$Url, [scriptblock]$Accept, $Service) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $launchSettings.StartupTimeoutSeconds) {
        if ($Service.Process.HasExited) {
            throw "$Name 启动退出，退出码 $($Service.Process.ExitCode)。日志目录 $logs"
        }
        $response = Read-LocalJson $Url
        if ($null -ne $response -and (& $Accept $response)) { return }
        Start-Sleep -Seconds 1
    }
    throw "$Name 未在限时内就绪。日志目录 $logs"
}

# 检测目标模型端口是否已由外部运行中的同别名服务占用：匹配则直接复用，端口被其他服务占用则拒绝启动。
function Test-ReusableModel([string]$Name, $Model) {
    if (-not (Test-PortInUse $Model.Url.Port)) { return $false }
    $existing = Read-LocalJson $Model.ModelsUrl
    if ($existing.data.id -notcontains $Model.Alias) {
        throw "$Name 端口 $($Model.Url.Port) 已占用，且未提供预期模型 $($Model.Alias)"
    }
    Write-Host "复用现有 $Name 服务 $($Model.Alias)"
    $true
}

Push-Location -LiteralPath $repo
try {
    # Windows 子进程的启动消息也要能在使用 UTF-8 的 Bash 终端中显示。
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    # 第一阶段：校验密钥环境变量、三个回环端口互不冲突，以及 Master / Router 是否可复用或具备可执行文件与权重。
    $console = Read-Properties $consolePath
    $master = Read-ModelProfile $console.masterConfig
    $router = Read-ModelProfile $console.routerConfig
    $chat = Read-Properties $console.chatWorkerConfig
    $keyEnvironment = $chat.apiKeyEnv
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($keyEnvironment))) {
        throw "启动环境缺少 Gemini 密钥变量 $keyEnvironment"
    }
    $ports = @([int]$console.listenPort, $master.Url.Port, $router.Url.Port)
    if ($console.listenHost -ne '127.0.0.1' -or @($ports | Sort-Object -Unique).Count -ne 3) {
        throw '启动配置须使用互不相同的本机回环端口'
    }
    if (Test-PortInUse $ports[0]) { throw "网页控制台端口 $($ports[0]) 已占用" }
    $masterReady = Test-ReusableModel 'Master' $master
    $routerReady = Test-ReusableModel 'Router' $router

    $javac = (Get-Command javac.exe -ErrorAction Stop).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac)
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not $masterReady) {
        $llama = [Environment]::ExpandEnvironmentVariables($launchSettings.MasterExecutable)
        $weights = [Environment]::ExpandEnvironmentVariables($launchSettings.MasterWeights)
        if (-not (Test-Path -LiteralPath $llama -PathType Leaf) -or -not (Test-Path -LiteralPath $weights -PathType Leaf)) {
            throw "找不到 Master 程序或权重，请核对 $LaunchConfig"
        }
    }
    if (-not $routerReady) {
        $wsl = (Get-Command wsl.exe -ErrorAction Stop).Source
        $wslArgs = @('-d', $launchSettings.RouterDistribution, '--exec')
        $checkPaths = 'p=$2; [[ $p = /* ]] || p="$HOME/$p"; test -x "$1" && test -d "$p"'
        & $wsl @wslArgs bash -c $checkPaths router $launchSettings.RouterExecutable $launchSettings.RouterWeights
        if ($LASTEXITCODE -ne 0) { throw "找不到 WSL Router 程序或权重，请核对 $LaunchConfig" }
    }
    # 第二阶段：构建 Java 控制面可执行 JAR，并依次就绪 Master（27B）与 Router（WSL vLLM 0.8B），最后拉起网页控制台。
    $logs = (New-Item -ItemType Directory -Path "java/target/local-launch-$launchId").FullName
    Write-Host "启动日志目录 $logs"
    & mvn -q -f java/pom.xml '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Java 21 / Maven 构建失败' }

    # 先确认 27B 已加载，再用固定 KV 预算启动同卡 0.8B。
    if (-not $masterReady) {
        $service = Start-Owned 'master' $llama @('--model', $weights, '--alias', $master.Alias,
            '--host', $master.Url.Host, '--port', [string]$master.Url.Port, '--metrics',
            '--ctx-size', [string]$launchSettings.MasterContextTokens, '--gpu-layers', '99', '--parallel', '1',
            '--jinja', '--no-ui', '--reasoning', 'off', '--reasoning-budget', '0')
        Wait-Ready 'Master' $master.ModelsUrl { param($result) $result.data.id -contains $master.Alias } $service
    }
    if (-not $routerReady) {
        $routerShell = 'pid=$1; model=$2; shift 2; [[ $model = /* ]] || model="$HOME/$model"; printf "%s\n" "$$" > "$pid" || exit 1; exec "$1" serve "$model" "${@:2}"'
        $routerArgs = $wslArgs + @('env', "NEURALCLOUD_LAUNCH_ID=$launchId", 'HF_HUB_OFFLINE=1',
            'TRANSFORMERS_OFFLINE=1', 'VLLM_NO_USAGE_STATS=1', 'bash', '-c', $routerShell, 'router',
            $routerPid, $launchSettings.RouterWeights, $launchSettings.RouterExecutable,
            '--host', $router.Url.Host, '--port', [string]$router.Url.Port, '--served-model-name', $router.Alias,
            '--dtype', 'bfloat16', '--max-model-len', [string]$launchSettings.RouterContextTokens, '--max-num-seqs', '1',
            '--gpu-memory-utilization', $launchSettings.RouterGpuMemoryUtilization,
            '--kv-cache-memory-bytes', [string]$launchSettings.RouterKvCacheBytes, '--enforce-eager',
            '--language-model-only', '--load-format', 'safetensors', '--enable-auto-tool-choice',
            '--tool-call-parser', 'qwen3_coder', '--reasoning-parser', 'qwen3')
        $service = Start-Owned 'router' $wsl $routerArgs
        Wait-Ready 'Router' $router.ModelsUrl { param($result) $result.data.id -contains $router.Alias } $service
    }
    $jar = 'java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar'
    $consoleArgs = if ($Mode -eq 'Meeting') {
        @('-cp', "java/target/test-classes;$jar", 'neuralcloud.controlplane.app.ConsoleScenarioMain', '--scenario',
            'java/src/test/resources/fixtures/master-meeting-evaluation.json', 'attempt-without-receipt', $ReminderOutcome)
    } else {
        @('-cp', $jar, 'neuralcloud.controlplane.app.ConsoleMain')
    }
    $web = Start-Owned 'console' $java (@('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8') +
        $consoleArgs + @('--config', $consolePath)) -NeedsGeminiKey
    $webUrl = "http://$($console.listenHost):$($console.listenPort)/"
    Wait-Ready '网页控制台' ([uri]::new([uri]$webUrl, '/api/nodes')) {
        param($result)
        $nodes = @($result.nodes.id)
        $nodes -contains 'chat-worker' -and $nodes -contains 'router' -and $nodes -contains 'master' -and
            ($Mode -ne 'Meeting' -or $nodes -contains 'meeting-scenario')
    } $web
    Write-Host "控制台已就绪 $webUrl"
    # 非冒烟模式下持续巡检本次拉起的所有托管进程，任一核心组件异常退出即报错并触发 finally 整体回收。
    if (-not $SmokeTest) {
        Write-Host '按 Ctrl+C 结束本次启动。'
        while ($true) {
            $ended = $owned | Where-Object { $_.Process.HasExited } | Select-Object -First 1
            if ($null -ne $ended) {
                throw "$($ended.Name) 已退出，退出码 $($ended.Process.ExitCode)。日志目录 $logs"
            }
            Start-Sleep -Seconds 1
        }
    }
} finally {
    # 只清理本次启动的进程。WSL 进程还须匹配本次运行的身份标记。
    for ($index = $owned.Count - 1; $index -ge 0; $index--) {
        $service = $owned[$index]
        if ($service.Name -eq 'router') {
            $stopRouter = @'
if read -r pid 2>/dev/null < "$1" && [[ $pid =~ ^[0-9]+$ ]] &&
   grep -zFxq "NEURALCLOUD_LAUNCH_ID=$2" "/proc/$pid/environ" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || :
fi
rm -f -- "$1"
'@
            & $wsl @wslArgs bash -c $stopRouter router $routerPid $launchId
            $null = $service.Process.WaitForExit(10000)
        }
        if (-not $service.Process.HasExited) {
            $service.Process.Kill()
            $service.Process.WaitForExit()
        }
        $null = $service.OutputCopy.GetAwaiter().GetResult()
        $null = $service.ErrorCopy.GetAwaiter().GetResult()
        $service.Stdout.Dispose()
        $service.Stderr.Dispose()
        $service.Process.Dispose()
    }
    $env:JAVA_HOME = $previousJavaHome
    [Console]::OutputEncoding = $previousOutputEncoding
    Pop-Location
}
