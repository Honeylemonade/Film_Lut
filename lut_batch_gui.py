import queue
import re
import shutil
import subprocess
import threading
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk


class LutBatchApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("LUT 批量套用工具 (Desktop)")
        self.root.geometry("1100x760")
        self.root.minsize(980, 680)
        self._setup_styles()

        self.project_dir = Path(__file__).resolve().parent
        self.builtin_lut_dir = self.project_dir / "luts"
        self.custom_lut_dir = self.project_dir / "custom_luts"
        self.output_dir_var = tk.StringVar(value=str(self.project_dir / "exports"))

        self.image_paths: list[Path] = []
        self.lut_map: dict[str, Path] = {}

        self.worker: threading.Thread | None = None
        self.log_queue: queue.Queue[tuple[str, str]] = queue.Queue()

        self._build_ui()
        self.refresh_luts()
        self._check_ffmpeg()

    def _setup_styles(self) -> None:
        style = ttk.Style()
        available = style.theme_names()
        if "clam" in available:
            style.theme_use("clam")
        elif "alt" in available:
            style.theme_use("alt")

        # Force readable colors across different macOS appearance modes.
        self.root.configure(bg="#f3f4f6")
        self.root.option_add("*Font", "Helvetica 12")
        self.root.option_add("*Listbox.Background", "#ffffff")
        self.root.option_add("*Listbox.Foreground", "#111827")
        self.root.option_add("*Text.Background", "#ffffff")
        self.root.option_add("*Text.Foreground", "#111827")

        style.configure("TFrame", background="#f3f4f6")
        style.configure("TLabelframe", background="#f3f4f6")
        style.configure("TLabelframe.Label", background="#f3f4f6", foreground="#111827", font=("Helvetica", 12, "bold"))
        style.configure("TLabel", background="#f3f4f6", foreground="#111827", font=("Helvetica", 12))
        style.configure("TButton", font=("Helvetica", 12), padding=(10, 6))
        style.configure("TEntry", fieldbackground="#ffffff", foreground="#111827")
        style.configure("TProgressbar", troughcolor="#e5e7eb", background="#2563eb")

    def _build_ui(self) -> None:
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(3, weight=1)

        title = ttk.Label(
            self.root,
            text="批量图片 LUT 处理（非 Web 桌面版）",
            font=("Helvetica", 16, "bold"),
        )
        title.grid(row=0, column=0, padx=12, pady=(12, 8), sticky="w")

        top_frame = ttk.Frame(self.root)
        top_frame.grid(row=1, column=0, padx=12, pady=6, sticky="nsew")
        top_frame.columnconfigure(0, weight=1)
        top_frame.columnconfigure(1, weight=1)
        top_frame.rowconfigure(1, weight=1)

        self._build_image_panel(top_frame)
        self._build_lut_panel(top_frame)

        self._build_export_panel()
        self._build_log_panel()

    def _build_image_panel(self, parent: ttk.Frame) -> None:
        ttk.Label(parent, text="1) 图片列表（支持批量）", font=("Helvetica", 12, "bold")).grid(
            row=0, column=0, sticky="w", pady=(0, 6)
        )

        frame = ttk.Frame(parent)
        frame.grid(row=1, column=0, padx=(0, 8), sticky="nsew")
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(0, weight=1)

        self.image_listbox = tk.Listbox(frame, selectmode=tk.EXTENDED, height=16)
        self.image_listbox.grid(row=0, column=0, sticky="nsew")

        image_scroll = ttk.Scrollbar(frame, orient=tk.VERTICAL, command=self.image_listbox.yview)
        image_scroll.grid(row=0, column=1, sticky="ns")
        self.image_listbox.config(yscrollcommand=image_scroll.set)

        btn_row = ttk.Frame(parent)
        btn_row.grid(row=2, column=0, sticky="w", pady=(8, 0))
        ttk.Button(btn_row, text="添加图片", command=self.add_images).grid(row=0, column=0, padx=(0, 6))
        ttk.Button(btn_row, text="移除选中", command=self.remove_selected_images).grid(
            row=0, column=1, padx=(0, 6)
        )
        ttk.Button(btn_row, text="清空图片", command=self.clear_images).grid(row=0, column=2)

    def _build_lut_panel(self, parent: ttk.Frame) -> None:
        ttk.Label(parent, text="2) LUT 列表（可多选）", font=("Helvetica", 12, "bold")).grid(
            row=0, column=1, sticky="w", pady=(0, 6)
        )

        frame = ttk.Frame(parent)
        frame.grid(row=1, column=1, padx=(8, 0), sticky="nsew")
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(0, weight=1)

        self.lut_listbox = tk.Listbox(frame, selectmode=tk.EXTENDED, height=16)
        self.lut_listbox.grid(row=0, column=0, sticky="nsew")

        lut_scroll = ttk.Scrollbar(frame, orient=tk.VERTICAL, command=self.lut_listbox.yview)
        lut_scroll.grid(row=0, column=1, sticky="ns")
        self.lut_listbox.config(yscrollcommand=lut_scroll.set)

        btn_row = ttk.Frame(parent)
        btn_row.grid(row=2, column=1, sticky="w", pady=(8, 0))
        ttk.Button(btn_row, text="导入 LUT 文件", command=self.import_luts).grid(row=0, column=0, padx=(0, 6))
        ttk.Button(btn_row, text="刷新 LUT 列表", command=self.refresh_luts).grid(row=0, column=1)

    def _build_export_panel(self) -> None:
        export_frame = ttk.LabelFrame(self.root, text="3) 导出设置")
        export_frame.grid(row=2, column=0, padx=12, pady=8, sticky="ew")
        export_frame.columnconfigure(1, weight=1)

        ttk.Label(export_frame, text="导出目录:").grid(row=0, column=0, sticky="w", padx=8, pady=8)
        ttk.Entry(export_frame, textvariable=self.output_dir_var).grid(
            row=0, column=1, sticky="ew", padx=(0, 8), pady=8
        )
        ttk.Button(export_frame, text="选择目录", command=self.choose_output_dir).grid(
            row=0, column=2, sticky="w", padx=(0, 8), pady=8
        )

        ttk.Label(export_frame, text="输出格式:").grid(row=1, column=0, sticky="w", padx=8, pady=8)
        ttk.Label(export_frame, text="固定无损 PNG（compression_level=0）").grid(
            row=1, column=1, columnspan=2, sticky="w", padx=(0, 8), pady=8
        )

        action_row = ttk.Frame(export_frame)
        action_row.grid(row=2, column=0, columnspan=3, sticky="ew", padx=8, pady=(4, 10))
        self.run_btn = ttk.Button(action_row, text="4) 开始批量套 LUT", command=self.start_batch)
        self.run_btn.grid(row=0, column=0, sticky="w")

    def _build_log_panel(self) -> None:
        log_frame = ttk.LabelFrame(self.root, text="处理日志")
        log_frame.grid(row=3, column=0, padx=12, pady=(0, 12), sticky="nsew")
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)

        self.log_text = tk.Text(log_frame, wrap="word", height=15)
        self.log_text.grid(row=0, column=0, sticky="nsew")
        self.log_text.configure(state="disabled")

        log_scroll = ttk.Scrollbar(log_frame, orient=tk.VERTICAL, command=self.log_text.yview)
        log_scroll.grid(row=0, column=1, sticky="ns")
        self.log_text.config(yscrollcommand=log_scroll.set)

        self.progress_var = tk.DoubleVar(value=0)
        self.progress = ttk.Progressbar(log_frame, maximum=100, variable=self.progress_var)
        self.progress.grid(row=1, column=0, columnspan=2, sticky="ew", padx=0, pady=(8, 0))

    def _check_ffmpeg(self) -> None:
        if shutil.which("ffmpeg") is None:
            messagebox.showwarning(
                "缺少 FFmpeg",
                "未检测到 ffmpeg，请先安装 ffmpeg 并确保命令可用。",
            )

    def _log(self, message: str) -> None:
        self.log_text.configure(state="normal")
        self.log_text.insert("end", message + "\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def _set_running(self, running: bool) -> None:
        self.run_btn.config(state=("disabled" if running else "normal"))

    def add_images(self) -> None:
        files = filedialog.askopenfilenames(
            title="选择图片（可多选）",
            filetypes=[
                ("图片文件", "*.jpg *.jpeg *.png *.bmp *.tif *.tiff *.webp"),
                ("所有文件", "*.*"),
            ],
        )
        if not files:
            return

        existing = {str(path) for path in self.image_paths}
        added = 0
        for item in files:
            p = Path(item)
            if str(p) not in existing:
                self.image_paths.append(p)
                self.image_listbox.insert("end", str(p))
                existing.add(str(p))
                added += 1

        self._log(f"已添加图片 {added} 张。")

    def remove_selected_images(self) -> None:
        selected = list(self.image_listbox.curselection())
        if not selected:
            return

        for idx in reversed(selected):
            self.image_listbox.delete(idx)
            del self.image_paths[idx]

        self._log(f"已移除 {len(selected)} 张图片。")

    def clear_images(self) -> None:
        self.image_paths.clear()
        self.image_listbox.delete(0, "end")
        self._log("已清空图片列表。")

    def import_luts(self) -> None:
        files = filedialog.askopenfilenames(
            title="导入 LUT 文件（.cube）",
            filetypes=[("LUT 文件", "*.cube"), ("所有文件", "*.*")],
        )
        if not files:
            return

        self.custom_lut_dir.mkdir(parents=True, exist_ok=True)

        imported = 0
        for src_str in files:
            src = Path(src_str)
            if src.suffix.lower() != ".cube":
                continue

            dst = self.custom_lut_dir / src.name
            if dst.exists():
                stem = src.stem
                suffix = src.suffix
                index = 1
                while True:
                    candidate = self.custom_lut_dir / f"{stem}_{index}{suffix}"
                    if not candidate.exists():
                        dst = candidate
                        break
                    index += 1

            shutil.copy2(src, dst)
            imported += 1

        self.refresh_luts()
        self._log(f"已导入 LUT {imported} 个到 {self.custom_lut_dir}。")

    def refresh_luts(self) -> None:
        self.lut_listbox.delete(0, "end")
        self.lut_map.clear()

        lut_paths: list[Path] = []
        if self.builtin_lut_dir.exists():
            lut_paths.extend(sorted(self.builtin_lut_dir.rglob("*.cube")))
        if self.custom_lut_dir.exists():
            lut_paths.extend(sorted(self.custom_lut_dir.rglob("*.cube")))

        for path in lut_paths:
            if self.builtin_lut_dir in path.parents:
                rel = path.relative_to(self.builtin_lut_dir)
                display = f"[内置] {rel.as_posix()}"
            else:
                rel = path.relative_to(self.custom_lut_dir)
                display = f"[自定义] {rel.as_posix()}"

            self.lut_map[display] = path
            self.lut_listbox.insert("end", display)

        self._log(f"LUT 列表已刷新，共 {len(lut_paths)} 个。")

    def choose_output_dir(self) -> None:
        directory = filedialog.askdirectory(title="选择导出目录")
        if directory:
            self.output_dir_var.set(directory)
            self._log(f"导出目录已设为: {directory}")

    @staticmethod
    def _safe_name(value: str) -> str:
        return re.sub(r"[^A-Za-z0-9._-]+", "_", value)

    @staticmethod
    def _escape_ffmpeg_filter_path(path: Path) -> str:
        s = path.resolve().as_posix()
        return s.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'").replace(",", "\\,")

    def _selected_lut_paths(self) -> list[Path]:
        selected_indices = self.lut_listbox.curselection()
        selected: list[Path] = []
        for idx in selected_indices:
            display = self.lut_listbox.get(idx)
            p = self.lut_map.get(display)
            if p is not None:
                selected.append(p)
        return selected

    def start_batch(self) -> None:
        if self.worker and self.worker.is_alive():
            messagebox.showinfo("处理中", "已有批处理任务在运行，请稍候。")
            return

        if shutil.which("ffmpeg") is None:
            messagebox.showerror("缺少 ffmpeg", "未检测到 ffmpeg，请先安装。")
            return

        images = list(self.image_paths)
        luts = self._selected_lut_paths()
        output_dir = Path(self.output_dir_var.get()).expanduser()

        if not images:
            messagebox.showwarning("未选择图片", "请先添加至少 1 张图片。")
            return
        if not luts:
            messagebox.showwarning("未选择 LUT", "请先选择至少 1 个 LUT。")
            return
        if not str(output_dir):
            messagebox.showwarning("未设置导出目录", "请先选择导出目录。")
            return

        output_dir.mkdir(parents=True, exist_ok=True)

        self.progress_var.set(0)
        self._set_running(True)
        self._log(f"开始批量处理: 图片 {len(images)} 张 x LUT {len(luts)} 个, 输出无损 PNG")

        self.worker = threading.Thread(
            target=self._run_batch,
            args=(images, luts, output_dir),
            daemon=True,
        )
        self.worker.start()
        self.root.after(100, self._poll_queue)

    def _run_batch(self, images: list[Path], luts: list[Path], output_dir: Path) -> None:
        total = len(images) * len(luts)
        done = 0
        success = 0
        failed = 0

        for image_path in images:
            for lut_path in luts:
                done += 1
                lut_rel_name = (
                    lut_path.relative_to(self.builtin_lut_dir).as_posix()
                    if self.builtin_lut_dir in lut_path.parents
                    else f"custom_{lut_path.name}"
                )

                out_name = f"{self._safe_name(image_path.stem)}__{self._safe_name(Path(lut_rel_name).stem)}.png"
                out_path = output_dir / out_name

                escaped_lut = self._escape_ffmpeg_filter_path(lut_path)
                ffmpeg_cmd = [
                    "ffmpeg",
                    "-y",
                    "-i",
                    str(image_path),
                    "-vf",
                    f"lut3d=file='{escaped_lut}'",
                    "-c:v",
                    "png",
                    "-compression_level",
                    "0",
                    "-pix_fmt",
                    "rgb24",
                    "-frames:v",
                    "1",
                    str(out_path),
                ]

                proc = subprocess.run(
                    ffmpeg_cmd,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    text=True,
                )

                if proc.returncode == 0:
                    success += 1
                    self.log_queue.put(("log", f"[{done}/{total}] 完成: {out_path.name}"))
                else:
                    failed += 1
                    err = (proc.stderr or "").strip().splitlines()
                    err_tail = err[-1] if err else "未知错误"
                    self.log_queue.put(("log", f"[{done}/{total}] 失败: {image_path.name} + {lut_path.name} -> {err_tail}"))

                progress = (done / total) * 100
                self.log_queue.put(("progress", f"{progress:.2f}"))

        self.log_queue.put(("done", f"任务结束: 成功 {success}，失败 {failed}，输出目录: {output_dir}"))

    def _poll_queue(self) -> None:
        keep_polling = False
        while not self.log_queue.empty():
            msg_type, payload = self.log_queue.get()
            if msg_type == "log":
                self._log(payload)
            elif msg_type == "progress":
                self.progress_var.set(float(payload))
            elif msg_type == "done":
                self._log(payload)
                self._set_running(False)
                messagebox.showinfo("批处理完成", payload)
            else:
                self._log(payload)

        if self.worker and self.worker.is_alive():
            keep_polling = True

        if keep_polling:
            self.root.after(100, self._poll_queue)


def main() -> None:
    root = tk.Tk()
    app = LutBatchApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
