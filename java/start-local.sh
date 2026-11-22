#!/usr/bin/env bash
set -euo pipefail

if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    cat <<'EOF'
用法：bash java/start-local.sh [-Mode Console|Meeting] [-ReminderOutcome PRESENT|CANCELLED|FAILED]
                              [-LaunchConfig 路径] [-ConsoleConfig 路径] [-SmokeTest]
支持 Windows 上的 WSL 和 Git Bash。若无法找到 PowerShell 7，可通过
NEURALCLOUD_PWSH 指定 Bash 可访问的 pwsh.exe 路径。
EOF
    exit 0
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ -n ${WSL_DISTRO_NAME:-} ]]; then
    path_converter=wslpath
    default_pwsh='/mnt/c/Program Files/PowerShell/7/pwsh.exe'
    # 把 Bash 中设置的密钥传给 Windows 子进程。沿用已有的 WSLENV 设置。
    if [[ -v GEMINI_API_KEY && ! ${WSLENV:-} =~ (^|:)GEMINI_API_KEY(/[^:]*)?($|:) ]]; then
        export WSLENV="${WSLENV:+$WSLENV:}GEMINI_API_KEY/w"
    fi
elif [[ ${OSTYPE:-} == msys* || ${OSTYPE:-} == cygwin* ]]; then
    path_converter=cygpath
    default_pwsh='/c/Program Files/PowerShell/7/pwsh.exe'
else
    printf '%s\n' '本机启动配置使用 Windows llama.cpp 和 WSL vLLM，请从 WSL 或 Git Bash 运行。' >&2
    exit 2
fi

pwsh=${NEURALCLOUD_PWSH:-pwsh.exe}
if ! command -v "$pwsh" >/dev/null 2>&1; then
    if [[ $pwsh == pwsh.exe && -x $default_pwsh ]]; then
        pwsh=$default_pwsh
    else
        printf '%s\n' '找不到 PowerShell 7，请设置 NEURALCLOUD_PWSH 或将 pwsh.exe 加入 PATH。' >&2
        exit 2
    fi
fi

args=()
while (($#)); do
    case "${1,,}" in
        -launchconfig|-consoleconfig)
            if (($# < 2)) || [[ $2 == -* ]]; then
                printf '%s 需要跟一个 Bash 可访问的文件路径。\n' "$1" >&2
                exit 2
            fi
            args+=("$1" "$("$path_converter" -aw "$2")")
            shift 2
            ;;
        *)
            args+=("$1")
            shift
            ;;
    esac
done

# 由 PowerShell 统一管理模型服务的就绪检查和进程归属。
exec "$pwsh" -NoProfile -File "$("$path_converter" -aw "$script_dir/start-local.ps1")" "${args[@]}"
