# Film LUT 批量处理工具 - Windows PowerShell 启动脚本

# 设置代码页为 UTF-8
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::UTF8

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Film LUT 批量处理工具" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

# 获取脚本所在目录
$scriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# 检查 Python 是否可用
try {
    $pythonVersion = python --version 2>&1
    Write-Host "✅ 已找到 Python: $pythonVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ 错误: 未找到 python，请先安装 Python 3.9+" -ForegroundColor Red
    Read-Host "按 Enter 键继续"
    exit 1
}

# 检查 FFmpeg 是否可用
try {
    ffmpeg -version | Out-Null 2>&1
    Write-Host "✅ 已找到 FFmpeg" -ForegroundColor Green
} catch {
    Write-Host "⚠️  警告: 未找到 ffmpeg" -ForegroundColor Yellow
    Write-Host "可从 https://ffmpeg.org/download.html 下载" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "📦 检查并安装依赖..." -ForegroundColor Cyan
python -m pip install -q -r web_ui\requirements.txt
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "❌ 依赖安装失败" -ForegroundColor Red
    Read-Host "按 Enter 键继续"
    exit 1
}

Write-Host ""
Write-Host "🚀 启动服务..." -ForegroundColor Cyan
Write-Host "📱 访问地址: http://127.0.0.1:8787" -ForegroundColor Green
Write-Host ""
Write-Host "⏳ 等待服务启动..." -ForegroundColor Yellow
Start-Sleep -Seconds 2

# 启动 Flask 应用（后台运行）
$flaskProcess = Start-Process python -ArgumentList "web_ui\app.py" -NoNewWindow -PassThru

# 等待服务启动
Start-Sleep -Seconds 3

# 尝试打开浏览器
try {
    Start-Process "http://127.0.0.1:8787"
} catch {
    # 如果无法打开浏览器，只是继续
}

Write-Host ""
Write-Host "✅ 服务已启动！" -ForegroundColor Green
Write-Host "按 Ctrl+C 停止服务" -ForegroundColor Yellow
Write-Host ""

# 等待进程结束或用户中断
try {
    $flaskProcess | Wait-Process
} catch {
    # 如果被中断，终止 Flask 进程
    Stop-Process -InputObject $flaskProcess -Force -ErrorAction SilentlyContinue
    Write-Host ""
    Write-Host 'Service stopped' -ForegroundColor Yellow
}
