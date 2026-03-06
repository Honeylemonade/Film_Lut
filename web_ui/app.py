from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import uuid
from datetime import datetime
from pathlib import Path

from flask import Flask, jsonify, render_template, request


ROOT = Path(__file__).resolve().parents[1]
LUT_DIR = ROOT / "luts"
CUSTOM_LUT_DIR = ROOT / "custom_luts"
EXPORTS_DIR = ROOT / "exports"
UPLOADS_TMP_DIR = ROOT / "uploads_tmp"

ALLOWED_IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
ALLOWED_LUT_EXT = {".cube"}

app = Flask(__name__, template_folder="static", static_folder="static")
JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value)


def quality_to_qv(quality: int) -> int:
    quality = max(1, min(100, quality))
    return int(round(31 - (quality - 1) * (29 / 99)))


def grain_to_noise_strength(grain_strength: int) -> int:
    grain_strength = max(0, min(100, grain_strength))
    # ffmpeg noise strength: 0-100, practical range here is 0-30
    return int(round((grain_strength / 100) * 30))


def collect_luts() -> list[dict]:
    items: list[dict] = []

    if LUT_DIR.exists():
        for p in sorted(LUT_DIR.rglob("*.cube")):
            rel = p.relative_to(LUT_DIR).as_posix()
            items.append(
                {
                    "id": f"builtin:{rel}",
                    "label": f"[内置] {rel}",
                    "type": "builtin",
                    "path": str(p),
                }
            )

    if CUSTOM_LUT_DIR.exists():
        for p in sorted(CUSTOM_LUT_DIR.rglob("*.cube")):
            rel = p.relative_to(CUSTOM_LUT_DIR).as_posix()
            items.append(
                {
                    "id": f"custom:{rel}",
                    "label": f"[自定义] {rel}",
                    "type": "custom",
                    "path": str(p),
                }
            )

    return items


def lut_id_to_path(lut_id: str) -> Path | None:
    if lut_id.startswith("builtin:"):
        rel = lut_id.removeprefix("builtin:")
        p = (LUT_DIR / rel).resolve()
        if p.exists() and p.suffix.lower() == ".cube":
            return p
        return None

    if lut_id.startswith("custom:"):
        rel = lut_id.removeprefix("custom:")
        p = (CUSTOM_LUT_DIR / rel).resolve()
        if p.exists() and p.suffix.lower() == ".cube":
            return p
        return None

    return None


def ensure_dirs() -> None:
    CUSTOM_LUT_DIR.mkdir(parents=True, exist_ok=True)
    EXPORTS_DIR.mkdir(parents=True, exist_ok=True)
    UPLOADS_TMP_DIR.mkdir(parents=True, exist_ok=True)


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/luts")
def api_luts():
    return jsonify({"luts": collect_luts()})


@app.post("/api/import-luts")
def api_import_luts():
    ensure_dirs()
    files = request.files.getlist("lut_files")
    imported = []

    for f in files:
        name = Path(f.filename or "")
        if name.suffix.lower() not in ALLOWED_LUT_EXT:
            continue

        dst = CUSTOM_LUT_DIR / safe_name(name.name)
        if dst.exists():
            stem = dst.stem
            suffix = dst.suffix
            i = 1
            while True:
                candidate = CUSTOM_LUT_DIR / f"{stem}_{i}{suffix}"
                if not candidate.exists():
                    dst = candidate
                    break
                i += 1

        f.save(dst)
        imported.append(str(dst.name))

    return jsonify({"imported": imported, "count": len(imported)})


def _process_job(
    job_id: str,
    saved_images: list[Path],
    lut_paths: list[Path],
    output_dir: Path,
    quality: int,
    qv: int,
    grain_strength: int,
    noise_strength: int,
    run_tmp: Path,
) -> None:
    total = len(saved_images) * len(lut_paths)
    done = 0
    success = 0
    failed = 0
    logs: list[str] = []
    outputs: list[str] = []

    try:
        for image_path in saved_images:
            for lut_path in lut_paths:
                lut_name = safe_name(lut_path.stem)
                out_name = f"{safe_name(image_path.stem)}__{lut_name}.jpg"
                out_path = output_dir / out_name
                escaped_lut_path = lut_path.as_posix().replace("'", "\\'")
                filter_chain = f"lut3d=file='{escaped_lut_path}'"
                if noise_strength > 0:
                    filter_chain = f"{filter_chain},noise=alls={noise_strength}:allf=t+u"

                cmd = [
                    "ffmpeg",
                    "-y",
                    "-i",
                    str(image_path),
                    "-vf",
                    filter_chain,
                    "-q:v",
                    str(qv),
                    "-pix_fmt",
                    "yuvj444p",
                    "-frames:v",
                    "1",
                    str(out_path),
                ]

                proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                done += 1
                if proc.returncode == 0:
                    success += 1
                    outputs.append(str(out_path))
                else:
                    failed += 1
                    err_lines = (proc.stderr or "").strip().splitlines()
                    logs.append(f"失败: {image_path.name} + {lut_path.name} -> {(err_lines[-1] if err_lines else '未知错误')}")

                with JOBS_LOCK:
                    if job_id in JOBS:
                        JOBS[job_id]["done"] = done
                        JOBS[job_id]["success"] = success
                        JOBS[job_id]["failed"] = failed
                        JOBS[job_id]["progress"] = round((done / total) * 100, 2) if total > 0 else 100.0
                        JOBS[job_id]["log_tail"] = logs[-50:]

        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["status"] = "done"
                JOBS[job_id]["result"] = {
                    "total": total,
                    "success": success,
                    "failed": failed,
                    "quality": quality,
                    "ffmpeg_qv": qv,
                    "grain_strength": grain_strength,
                    "ffmpeg_noise_strength": noise_strength,
                    "output_dir": str(output_dir.resolve()),
                    "outputs": outputs[:200],
                    "log_tail": logs[-50:],
                }
    except Exception as exc:
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["status"] = "error"
                JOBS[job_id]["error"] = str(exc)
    finally:
        shutil.rmtree(run_tmp, ignore_errors=True)


@app.post("/api/process/start")
def api_process_start():
    ensure_dirs()

    if shutil.which("ffmpeg") is None:
        return jsonify({"error": "未检测到 ffmpeg，请先安装 ffmpeg。"}), 400

    images = request.files.getlist("images")
    selected_luts_raw = request.form.get("selected_luts", "[]")
    output_dir_raw = (request.form.get("output_dir", "") or "").strip()
    quality_raw = request.form.get("quality", "95")
    grain_strength_raw = request.form.get("grain_strength", "0")

    try:
        selected_lut_ids = json.loads(selected_luts_raw)
        if not isinstance(selected_lut_ids, list):
            raise ValueError
    except Exception:
        return jsonify({"error": "selected_luts 参数格式错误。"}), 400

    try:
        quality = int(quality_raw)
    except ValueError:
        return jsonify({"error": "quality 必须是 1-100 的整数。"}), 400
    try:
        grain_strength = int(grain_strength_raw)
    except ValueError:
        return jsonify({"error": "grain_strength 必须是 0-100 的整数。"}), 400

    if not images:
        return jsonify({"error": "请至少上传 1 张图片。"}), 400
    if not selected_lut_ids:
        return jsonify({"error": "请至少选择 1 个 LUT。"}), 400
    if quality < 1 or quality > 100:
        return jsonify({"error": "quality 必须在 1-100 之间。"}), 400
    if grain_strength < 0 or grain_strength > 100:
        return jsonify({"error": "grain_strength 必须在 0-100 之间。"}), 400

    lut_paths: list[Path] = []
    for lut_id in selected_lut_ids:
        p = lut_id_to_path(str(lut_id))
        if p is None:
            return jsonify({"error": f"无效 LUT: {lut_id}"}), 400
        lut_paths.append(p)

    if output_dir_raw:
        output_dir = Path(output_dir_raw).expanduser()
    else:
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        output_dir = EXPORTS_DIR / f"batch_{stamp}"
    output_dir.mkdir(parents=True, exist_ok=True)

    run_tmp = UPLOADS_TMP_DIR / datetime.now().strftime("run_%Y%m%d_%H%M%S_%f")
    run_tmp.mkdir(parents=True, exist_ok=True)

    saved_images: list[Path] = []
    for image in images:
        original_name = Path(image.filename or "")
        if original_name.suffix.lower() not in ALLOWED_IMAGE_EXT:
            continue
        dst = run_tmp / safe_name(original_name.name)
        image.save(dst)
        saved_images.append(dst)
    if not saved_images:
        shutil.rmtree(run_tmp, ignore_errors=True)
        return jsonify({"error": "上传文件里没有有效图片格式。"}), 400

    qv = quality_to_qv(quality)
    noise_strength = grain_to_noise_strength(grain_strength)
    total = len(saved_images) * len(lut_paths)

    job_id = uuid.uuid4().hex
    with JOBS_LOCK:
        JOBS[job_id] = {
            "status": "running",
            "progress": 0.0,
            "done": 0,
            "total": total,
            "success": 0,
            "failed": 0,
            "quality": quality,
            "grain_strength": grain_strength,
            "output_dir": str(output_dir.resolve()),
            "log_tail": [],
        }

    worker = threading.Thread(
        target=_process_job,
        args=(
            job_id,
            saved_images,
            lut_paths,
            output_dir,
            quality,
            qv,
            grain_strength,
            noise_strength,
            run_tmp,
        ),
        daemon=True,
    )
    worker.start()

    return jsonify({"job_id": job_id, "total": total, "output_dir": str(output_dir.resolve())})


@app.get("/api/process/status/<job_id>")
def api_process_status(job_id: str):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if job is None:
            return jsonify({"error": "任务不存在或已过期。"}), 404
        payload = dict(job)
    return jsonify(payload)


if __name__ == "__main__":
    ensure_dirs()
    app.run(host="127.0.0.1", port=8787, debug=False)
