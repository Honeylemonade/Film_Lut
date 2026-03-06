# Film LUT 批量处理工具

本项目提供一个本地 Web 工具，用于给图片批量套用 LUT，并导出无损 PNG。

## 功能

- 批量上传图片（支持多选）
- 图片预览：单张大图预览，多张堆叠预览
- 多选内置 LUT（读取 `luts/`）
- 导入自定义 `.cube` LUT
- 支持删除自定义 LUT（内置 LUT 不可删除）
- 批量处理（图片数 x LUT 数）
- 导出无损 PNG（`compression_level=0`）
- 支持 LUT 收藏标星（持久保存，下次打开可快速筛选/选择）
- 支持胶片颗粒效果与强度调节（0-100）
- 支持自定义导出目录
- 导出完成后自动打开导出目录
- 支持实时转换进度条（百分比 + 完成数/总数）

## 技术栈

- 后端：Python + Flask
- 前端：原生 HTML/CSS/JavaScript
- 图像处理：FFmpeg（`lut3d` + `noise`）

## 环境要求

- Python 3.9+
- FFmpeg（命令行可直接执行 `ffmpeg`）
- macOS / Linux / Windows（均可运行，路径填写按系统格式）

## 安装依赖

在项目根目录执行：

```bash
python3 -m pip install -r web_ui/requirements.txt
```

如果你使用虚拟环境，先激活虚拟环境再执行上面的命令。

## 启动方式

在项目根目录执行：

```bash
python3 web_ui/app.py
```

启动后在浏览器打开：

- [http://127.0.0.1:8787](http://127.0.0.1:8787)

## 编译/打包（可选）

本项目是 Python Web 服务，日常使用不需要编译。

如果你希望打包成单文件可执行程序（例如便于分发），可以使用 `pyinstaller`：

1. 安装打包工具

```bash
python3 -m pip install pyinstaller
```

2. 在项目根目录执行打包

```bash
pyinstaller --onefile --add-data "web_ui/static:web_ui/static" web_ui/app.py
```

说明：
- macOS/Linux 使用 `:` 作为 `--add-data` 分隔符。
- Windows 需要改成 `;`，例如：
  `--add-data "web_ui/static;web_ui/static"`

3. 打包产物

- 可执行文件在 `dist/` 目录中。

## 使用流程

1. 上传图片（可批量）
2. 选择一个或多个 LUT
3. 可选：导入自己的 `.cube` LUT
4. 设置导出目录、颗粒强度
5. 点击“开始批量套 LUT”
6. 在页面查看实时进度和处理结果

## 目录说明

- `luts/`：内置 LUT 库
- `custom_luts/`：导入的自定义 LUT
- `exports/`：默认导出目录（当未手动指定导出目录时使用）
- `uploads_tmp/`：运行时临时上传目录（任务结束后清理）
- `web_ui/app.py`：后端服务
- `web_ui/static/index.html`：前端页面

## 常见问题

### 1) 提示未检测到 ffmpeg
请先安装 FFmpeg，并确保终端可执行：

```bash
ffmpeg -version
```

### 2) 进度条不动
通常是浏览器缓存了旧前端页面。请强制刷新页面：

- macOS: `Cmd + Shift + R`
- Windows: `Ctrl + F5`

### 3) 端口被占用
默认端口是 `8787`。如冲突，可修改 `web_ui/app.py` 末尾的 `app.run(..., port=8787)`。

## License

MIT
