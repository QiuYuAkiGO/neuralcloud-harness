#Requires -Version 7.0
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

function Read-Properties([string]$Path) {
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-StringData
}

function Read-ModelProfile([string]$Path) {
    $settings = Read-Properties $Path
    $url = [uri]$settings.endpoint
    if ($url.Scheme -ne 'http' -or $url.Host -ne '127.0.0.1' -or
        $url.AbsolutePath -ne '/v1/chat/completions' -or -not $settings.model) {
        throw "模型配置须为带模型标识的本机聊天端点：$Path"
    }
    @{ Url = $url; ModelsUrl = [uri]::new($url, '/v1/models'); Alias = $settings.model }
}

function Read-LocalJson([uri]$Url) {
    try {
        $data = Invoke-RestMethod -Uri $Url -NoProxy -TimeoutSec 2 -SkipHttpErrorCheck -StatusCodeVariable status
        if ($status -eq 200) { $data }
    } catch [System.Net.Http.HttpRequestException], [System.Threading.Tasks.TaskCanceledException], [System.TimeoutException] {
        $null
    }
}

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
    Pop-Location
}
