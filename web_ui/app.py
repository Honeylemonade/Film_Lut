from __future__ import annotations

import json
import base64
import re
import shutil
import subprocess
import sys
import threading
import uuid
from datetime import datetime
from pathlib import Path
import tempfile

from flask import Flask, jsonify, render_template, request


ROOT = Path(__file__).resolve().parents[1]
LUT_DIR = ROOT / "luts"
CUSTOM_LUT_DIR = ROOT / "custom_luts"
USER_IMPORT_LUT_DIR = LUT_DIR / "user_imports"
EXPORTS_DIR = ROOT / "exports"
UPLOADS_TMP_DIR = ROOT / "uploads_tmp"
FAVORITES_FILE = ROOT / "lut_favorites.json"

ALLOWED_IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
ALLOWED_LUT_EXT = {".cube"}

app = Flask(__name__, template_folder="static", static_folder="static")
JOBS: dict[str, dict] = {}
JOBS_LOCK = threading.Lock()
FAVORITES_LOCK = threading.Lock()


def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value)


def grain_to_noise_strength(grain_strength: int) -> int:
    grain_strength = max(0, min(100, grain_strength))
    # ffmpeg noise strength: 0-100, practical range here is 0-30
    return int(round((grain_strength / 100) * 30))


def dispersion_to_chromashift(dispersion_strength: int) -> float:
    dispersion_strength = max(0, min(100, dispersion_strength))
    # chromashift offset in pixels
    return round((dispersion_strength / 100) * 6.0, 2)


def vignette_to_angle(vignette_strength: int) -> float:
    vignette_strength = max(0, min(100, vignette_strength))
    # vignette angle parameter, larger means stronger vignette
    return round((vignette_strength / 100) * 1.6, 3)


def sharpen_to_unsharp_amount(sharpen_strength: int) -> float:
    sharpen_strength = max(-100, min(100, sharpen_strength))
    # unsharp luma amount: negative softens, positive sharpens
    return round((sharpen_strength / 100) * 2.0, 2)


def clarity_to_contrast(clarity_strength: int) -> float:
    clarity_strength = max(-100, min(100, clarity_strength))
    # eq contrast: 1.0 (off), lower reduces clarity, higher boosts clarity
    return round(1.0 + (clarity_strength / 100) * 0.35, 3)


def collect_luts() -> list[dict]:
    items: list[dict] = []

    if LUT_DIR.exists():
        for p in sorted(LUT_DIR.rglob("*.cube")):
            rel = p.relative_to(LUT_DIR).as_posix()
            items.append(
                {
                    "id": f"lut:{rel}",
                    "label": rel,
                    "type": "lut",
                    "path": str(p),
                }
            )

    return items


def load_favorite_ids() -> set[str]:
    # 处理以前的隐藏文件迁移
    legacy_file = ROOT / ".lut_favorites.json"
    if legacy_file.exists() and not FAVORITES_FILE.exists():
        try:
            shutil.move(str(legacy_file), str(FAVORITES_FILE))
        except Exception:
            pass

    if not FAVORITES_FILE.exists():
        return set()
    try:
        data = json.loads(FAVORITES_FILE.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            return set()
        
        # 兼容旧版本保存的 "builtin:" 和 "custom:" 前缀
        new_ids = set()
        for item in data:
            item_str = str(item)
            if item_str.startswith("builtin:"):
                new_ids.add("lut:" + item_str.removeprefix("builtin:"))
            elif item_str.startswith("custom:"):
                new_ids.add("lut:user_imports/" + item_str.removeprefix("custom:"))
            else:
                new_ids.add(item_str)
        return new_ids
    except Exception:
        return set()


def save_favorite_ids(ids: set[str]) -> None:
    FAVORITES_FILE.write_text(
        json.dumps(sorted(ids), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def lut_id_to_path(lut_id: str) -> Path | None:
    if lut_id.startswith("lut:"):
        rel = lut_id.removeprefix("lut:")
        p = (LUT_DIR / rel).resolve()
        if p.exists() and p.suffix.lower() == ".cube" and (p == LUT_DIR.resolve() or LUT_DIR.resolve() in p.parents):
            return p
        return None

    # Legacy id compatibility
    if lut_id.startswith("builtin:"):
        rel = lut_id.removeprefix("builtin:")
        p = (LUT_DIR / rel).resolve()
        if p.exists() and p.suffix.lower() == ".cube" and (p == LUT_DIR.resolve() or LUT_DIR.resolve() in p.parents):
            return p
        return None
    if lut_id.startswith("custom:"):
        rel = lut_id.removeprefix("custom:")
        p = (CUSTOM_LUT_DIR / rel).resolve()
        if p.exists() and p.suffix.lower() == ".cube" and (p == CUSTOM_LUT_DIR.resolve() or CUSTOM_LUT_DIR.resolve() in p.parents):
            return p
        return None

    return None


def ensure_dirs() -> None:
    LUT_DIR.mkdir(parents=True, exist_ok=True)
    USER_IMPORT_LUT_DIR.mkdir(parents=True, exist_ok=True)
    EXPORTS_DIR.mkdir(parents=True, exist_ok=True)
    UPLOADS_TMP_DIR.mkdir(parents=True, exist_ok=True)

    # Migrate legacy custom_luts into LUT root so all LUTs are managed in one place.
    if CUSTOM_LUT_DIR.exists():
        for src in sorted(CUSTOM_LUT_DIR.rglob("*.cube")):
            rel = src.relative_to(CUSTOM_LUT_DIR)
            dst = USER_IMPORT_LUT_DIR / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            if dst.exists():
                stem = dst.stem
                suffix = dst.suffix
                i = 1
                while True:
                    candidate = dst.with_name(f"{stem}_{i}{suffix}")
                    if not candidate.exists():
                        dst = candidate
                        break
                    i += 1
            shutil.move(str(src), str(dst))


def open_folder(path: Path) -> tuple[bool, str]:
    if not path.exists():
        return False, "目录不存在。"
    if not path.is_dir():
        return False, "路径不是目录。"

    cmd: list[str] | None = None
    if sys.platform.startswith("darwin"):
        cmd = ["open", str(path)]
    elif sys.platform.startswith("win"):
        cmd = ["explorer", str(path)]
    elif shutil.which("xdg-open"):
        cmd = ["xdg-open", str(path)]

    if cmd is None:
        return False, "当前系统不支持自动打开目录。"

    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True, ""
    except Exception as exc:
        return False, str(exc)


def build_filter_chain(
    lut_path: Path,
    noise_strength: int,
    chromashift_px: float,
    vignette_angle: float,
    unsharp_amount: float,
    clarity_contrast: float,
) -> str:
    escaped_lut_path = lut_path.as_posix().replace("'", "\\'")
    filter_chain = f"lut3d=file='{escaped_lut_path}'"
    if chromashift_px > 0:
        shift = f"{chromashift_px:.2f}"
        filter_chain = f"{filter_chain},chromashift=cbh=-{shift}:cbv=-{shift}:crh={shift}:crv={shift}"
    if vignette_angle > 0:
        filter_chain = f"{filter_chain},vignette=angle={vignette_angle:.3f}"
    if abs(unsharp_amount) > 1e-6:
        filter_chain = f"{filter_chain},unsharp=5:5:{unsharp_amount:.2f}:5:5:0.0"
    if abs(clarity_contrast - 1.0) > 1e-6:
        filter_chain = f"{filter_chain},eq=contrast={clarity_contrast:.3f}"
    if noise_strength > 0:
        filter_chain = f"{filter_chain},noise=alls={noise_strength}:allf=t+u"
    return filter_chain


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/luts")
def api_luts():
    with FAVORITES_LOCK:
        favorite_ids = load_favorite_ids()

    luts = collect_luts()
    for item in luts:
        item["favorite"] = item["id"] in favorite_ids
    return jsonify({"luts": luts})


@app.post("/api/favorites/bulk")
def api_favorites_bulk():
    payload = request.get_json(silent=True) or {}
    lut_ids_raw = payload.get("lut_ids")
    favorite_raw = payload.get("favorite")

    if not isinstance(lut_ids_raw, list):
        return jsonify({"error": "lut_ids 必须是数组。"}), 400
    if not isinstance(favorite_raw, bool):
        return jsonify({"error": "favorite 必须是布尔值。"}), 400

    lut_ids = {str(item) for item in lut_ids_raw}
    valid_ids = {item["id"] for item in collect_luts()}
    lut_ids = {item for item in lut_ids if item in valid_ids}

    with FAVORITES_LOCK:
        favorites = load_favorite_ids()
        if favorite_raw:
            favorites.update(lut_ids)
        else:
            favorites.difference_update(lut_ids)
        favorites.intersection_update(valid_ids)
        save_favorite_ids(favorites)
        result = sorted(favorites)

    return jsonify({"favorites": result, "count": len(result)})


@app.post("/api/luts/delete")
def api_luts_delete():
    payload = request.get_json(silent=True) or {}
    lut_ids_raw = payload.get("lut_ids")
    if not isinstance(lut_ids_raw, list):
        return jsonify({"error": "lut_ids 必须是数组。"}), 400

    lut_ids = [str(item) for item in lut_ids_raw]
    deleted: list[str] = []
    skipped_invalid: list[str] = []

    for lut_id in lut_ids:
        p = lut_id_to_path(lut_id)
        if p is None:
            skipped_invalid.append(lut_id)
            continue
        try:
            p.unlink()
            deleted.append(lut_id)
        except Exception:
            skipped_invalid.append(lut_id)

    valid_ids = {item["id"] for item in collect_luts()}
    with FAVORITES_LOCK:
        favorites = load_favorite_ids()
        favorites.intersection_update(valid_ids)
        save_favorite_ids(favorites)

    return jsonify(
        {
            "deleted": deleted,
            "deleted_count": len(deleted),
            "skipped_invalid": skipped_invalid,
        }
    )


@app.post("/api/open-output")
def api_open_output():
    payload = request.get_json(silent=True) or {}
    path_raw = payload.get("path")
    if not isinstance(path_raw, str) or not path_raw.strip():
        return jsonify({"error": "path 参数不能为空。"}), 400

    p = Path(path_raw).expanduser().resolve()
    ok, err = open_folder(p)
    if not ok:
        return jsonify({"error": err}), 400
    return jsonify({"ok": True})


@app.post("/api/import-luts")
def api_import_luts():
    ensure_dirs()
    files = request.files.getlist("lut_files")
    imported = []

    for f in files:
        name = Path(f.filename or "")
        if name.suffix.lower() not in ALLOWED_LUT_EXT:
            continue

        dst = USER_IMPORT_LUT_DIR / safe_name(name.name)
        if dst.exists():
            stem = dst.stem
            suffix = dst.suffix
            i = 1
            while True:
                candidate = USER_IMPORT_LUT_DIR / f"{stem}_{i}{suffix}"
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
    grain_strength: int,
    noise_strength: int,
    dispersion_strength: int,
    chromashift_px: float,
    vignette_strength: int,
    vignette_angle: float,
    sharpen_strength: int,
    unsharp_amount: float,
    clarity_strength: int,
    clarity_contrast: float,
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
                out_name = f"{safe_name(image_path.stem)}__{lut_name}.png"
                out_path = output_dir / out_name
                filter_chain = build_filter_chain(
                    lut_path,
                    noise_strength,
                    chromashift_px,
                    vignette_angle,
                    unsharp_amount,
                    clarity_contrast,
                )

                cmd = [
                    "ffmpeg",
                    "-y",
                    "-i",
                    str(image_path),
                    "-vf",
                    filter_chain,
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
                    "output_format": "png",
                    "grain_strength": grain_strength,
                    "ffmpeg_noise_strength": noise_strength,
                    "dispersion_strength": dispersion_strength,
                    "ffmpeg_chromashift_px": chromashift_px,
                    "vignette_strength": vignette_strength,
                    "ffmpeg_vignette_angle": vignette_angle,
                    "sharpen_strength": sharpen_strength,
                    "ffmpeg_unsharp_amount": unsharp_amount,
                    "clarity_strength": clarity_strength,
                    "ffmpeg_clarity_contrast": clarity_contrast,
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
    grain_strength_raw = request.form.get("grain_strength", "50")
    dispersion_strength_raw = request.form.get("dispersion_strength", "50")
    vignette_strength_raw = request.form.get("vignette_strength", "30")
    sharpen_strength_raw = request.form.get("sharpen_strength", "0")
    clarity_strength_raw = request.form.get("clarity_strength", "0")

    try:
        selected_lut_ids = json.loads(selected_luts_raw)
        if not isinstance(selected_lut_ids, list):
            raise ValueError
    except Exception:
        return jsonify({"error": "selected_luts 参数格式错误。"}), 400

    try:
        grain_strength = int(grain_strength_raw)
    except ValueError:
        return jsonify({"error": "grain_strength 必须是 0-100 的整数。"}), 400
    try:
        dispersion_strength = int(dispersion_strength_raw)
    except ValueError:
        return jsonify({"error": "dispersion_strength 必须是 0-100 的整数。"}), 400
    try:
        vignette_strength = int(vignette_strength_raw)
    except ValueError:
        return jsonify({"error": "vignette_strength 必须是 0-100 的整数。"}), 400
    try:
        sharpen_strength = int(sharpen_strength_raw)
    except ValueError:
        return jsonify({"error": "sharpen_strength 必须是 -100 到 100 的整数。"}), 400
    try:
        clarity_strength = int(clarity_strength_raw)
    except ValueError:
        return jsonify({"error": "clarity_strength 必须是 -100 到 100 的整数。"}), 400

    if not images:
        return jsonify({"error": "请至少上传 1 张图片。"}), 400
    if not selected_lut_ids:
        return jsonify({"error": "请至少选择 1 个 LUT。"}), 400
    if grain_strength < 0 or grain_strength > 100:
        return jsonify({"error": "grain_strength 必须在 0-100 之间。"}), 400
    if dispersion_strength < 0 or dispersion_strength > 100:
        return jsonify({"error": "dispersion_strength 必须在 0-100 之间。"}), 400
    if vignette_strength < 0 or vignette_strength > 100:
        return jsonify({"error": "vignette_strength 必须在 0-100 之间。"}), 400
    if sharpen_strength < -100 or sharpen_strength > 100:
        return jsonify({"error": "sharpen_strength 必须在 -100 到 100 之间。"}), 400
    if clarity_strength < -100 or clarity_strength > 100:
        return jsonify({"error": "clarity_strength 必须在 -100 到 100 之间。"}), 400

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

    noise_strength = grain_to_noise_strength(grain_strength)
    chromashift_px = dispersion_to_chromashift(dispersion_strength)
    vignette_angle = vignette_to_angle(vignette_strength)
    unsharp_amount = sharpen_to_unsharp_amount(sharpen_strength)
    clarity_contrast = clarity_to_contrast(clarity_strength)
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
            "output_format": "png",
            "grain_strength": grain_strength,
            "dispersion_strength": dispersion_strength,
            "vignette_strength": vignette_strength,
            "sharpen_strength": sharpen_strength,
            "clarity_strength": clarity_strength,
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
            grain_strength,
            noise_strength,
            dispersion_strength,
            chromashift_px,
            vignette_strength,
            vignette_angle,
            sharpen_strength,
            unsharp_amount,
            clarity_strength,
            clarity_contrast,
            run_tmp,
        ),
        daemon=True,
    )
    worker.start()

    return jsonify({"job_id": job_id, "total": total, "output_dir": str(output_dir.resolve())})


@app.post("/api/preview")
def api_preview():
    ensure_dirs()
    if shutil.which("ffmpeg") is None:
        return jsonify({"error": "未检测到 ffmpeg，请先安装 ffmpeg。"}), 400

    image = request.files.get("image")
    lut_id = str(request.form.get("lut_id", "")).strip()
    if image is None:
        return jsonify({"error": "缺少预览图片。"}), 400
    if not lut_id:
        return jsonify({"error": "缺少 lut_id。"}), 400

    lut_path = lut_id_to_path(lut_id)
    if lut_path is None:
        return jsonify({"error": "无效 LUT。"}), 400

    try:
        grain_strength = int(request.form.get("grain_strength", "50"))
        dispersion_strength = int(request.form.get("dispersion_strength", "50"))
        vignette_strength = int(request.form.get("vignette_strength", "30"))
        sharpen_strength = int(request.form.get("sharpen_strength", "0"))
        clarity_strength = int(request.form.get("clarity_strength", "0"))
    except ValueError:
        return jsonify({"error": "预览参数必须是整数。"}), 400

    if not (0 <= grain_strength <= 100):
        return jsonify({"error": "grain_strength 必须在 0-100 之间。"}), 400
    if not (0 <= dispersion_strength <= 100):
        return jsonify({"error": "dispersion_strength 必须在 0-100 之间。"}), 400
    if not (0 <= vignette_strength <= 100):
        return jsonify({"error": "vignette_strength 必须在 0-100 之间。"}), 400
    if not (-100 <= sharpen_strength <= 100):
        return jsonify({"error": "sharpen_strength 必须在 -100 到 100 之间。"}), 400
    if not (-100 <= clarity_strength <= 100):
        return jsonify({"error": "clarity_strength 必须在 -100 到 100 之间。"}), 400

    noise_strength = grain_to_noise_strength(grain_strength)
    chromashift_px = dispersion_to_chromashift(dispersion_strength)
    vignette_angle = vignette_to_angle(vignette_strength)
    unsharp_amount = sharpen_to_unsharp_amount(sharpen_strength)
    clarity_contrast = clarity_to_contrast(clarity_strength)
    filter_chain = build_filter_chain(
        lut_path,
        noise_strength,
        chromashift_px,
        vignette_angle,
        unsharp_amount,
        clarity_contrast,
    )

    suffix = Path(image.filename or "preview.png").suffix.lower()
    if suffix not in ALLOWED_IMAGE_EXT:
        suffix = ".png"

    with tempfile.TemporaryDirectory(prefix="lut_preview_", dir=str(UPLOADS_TMP_DIR)) as tmp_dir:
        src_path = Path(tmp_dir) / f"input{suffix}"
        out_path = Path(tmp_dir) / "preview.png"
        image.save(src_path)

        cmd = [
            "ffmpeg",
            "-y",
            "-i",
            str(src_path),
            "-vf",
            f"{filter_chain},scale='min(960,iw)':-2:flags=lanczos",
            "-c:v",
            "png",
            "-compression_level",
            "1",
            "-pix_fmt",
            "rgb24",
            "-frames:v",
            "1",
            str(out_path),
        ]
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        if proc.returncode != 0 or not out_path.exists():
            err_lines = (proc.stderr or "").strip().splitlines()
            return jsonify({"error": err_lines[-1] if err_lines else "预览生成失败。"}), 500

        payload = base64.b64encode(out_path.read_bytes()).decode("ascii")
        return jsonify({"image_data_url": f"data:image/png;base64,{payload}"})


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
