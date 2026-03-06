#!/bin/bash

# 获取脚本所在目录
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo "====================================="
echo "Film LUT 批量处理工具"
echo "====================================="

# 检查 Python 是否可用
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未找到 python3，请先安装 Python 3.9+"
    exit 1
fi

# 检查 FFmpeg 是否可用
if ! command -v ffmpeg &> /dev/null; then
    echo "⚠️  警告: 未找到 ffmpeg，请先安装 FFmpeg"
fi

echo "📦 检查并安装依赖..."
python3 -m pip install -r web_ui/requirements.txt

echo ""
echo "🚀 启动服务..."
echo "📱 访问地址: http://127.0.0.1:8787"
echo ""

# 在后台启动 Flask 应用
python3 web_ui/app.py &
FLASK_PID=$!

# 等待服务启动
echo "⏳ 等待服务启动..."
sleep 3

# 打开浏览器
if command -v open &> /dev/null; then
    echo "🌐 打开浏览器..."
    open http://127.0.0.1:8787
elif command -v xdg-open &> /dev/null; then
    echo "🌐 打开浏览器..."
    xdg-open http://127.0.0.1:8787
fi

echo ""
echo "✅ 服务已启动！"
echo "按 Ctrl+C 停止服务"
echo ""

# 等待用户中断
wait $FLASK_PID
