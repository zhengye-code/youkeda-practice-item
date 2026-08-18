# 自动获取 SILK 编码器并放到 tools/ 目录
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/download-silk.ps1 -Url <exe下载地址>
# 或直接运行（仅打印指引）

param(
    [string]$Url = ""
)

$ErrorActionPreference = "Stop"
$toolsDir = $PSScriptRoot
$target = Join-Path $toolsDir "silk_encoder.exe"

if ($Url -ne "") {
    Write-Host "正在从 $Url 下载 SILK 编码器..."
    curl.exe -L -o $target $Url
    if (Test-Path $target) {
        Write-Host "已保存到 $target"
        Write-Host "可直接运行 bot，程序会自动加载内置编码器。"
    } else {
        Write-Host "下载失败，请检查 URL。"
    }
    exit
}

Write-Host @"
未提供 -Url 参数。请任选一种方式获取 silk_v3_encoder：

  1) 从 https://github.com/kn007/silk-v3-encoder 下载源码，在 Windows 用 MSYS2/MinGW 编译
  2) 搜索 'silk_encoder.exe 下载'（常见于 QQ 机器人框架发布包），下载后：
        powershell -ExecutionPolicy Bypass -File tools/download-silk.ps1 -Url <下载地址>
  3) 直接把编译/下载好的 silk_encoder.exe 复制到本 tools/ 目录

注意：请确保下载的是可信来源的 Windows 可执行文件。
"@
