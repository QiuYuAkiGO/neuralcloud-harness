@{
    MasterExecutable = '%LOCALAPPDATA%/NeuralCloud/llama.cpp/bin/llama-server.exe'
    MasterWeights = 'D:/NeuralCloudModels/llama.cpp/Qwen3.8-27B-Q4_K_M.gguf'
    MasterContextTokens = 4096
    RouterDistribution = 'Ubuntu-24.04'
    RouterExecutable = '/opt/neuralcloud-vllm/venv/bin/vllm'
    # 相对路径以 WSL 用户主目录为基准。
    RouterWeights = '.cache/neuralcloud/modelscope-comparison/Qwen3.5-0.8B'
    RouterContextTokens = 2048
    RouterGpuMemoryUtilization = '0.15'
    RouterKvCacheBytes = 536870912
    StartupTimeoutSeconds = 180
}
