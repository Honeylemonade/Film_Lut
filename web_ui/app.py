from __future__ import annotations

import json
import base64
import os
import re
import shutil
import subprocess
import sys
import threading
import uuid
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
import tempfile

from flask import Flask, jsonify, render_template, request


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s: %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler('lut_process.log', encoding='utf-8')
    ]
)
logger = logging.getLogger(__name__)


ROOT = Path(__file__).resolve().parents[1]
LUT_DIR = ROOT / "luts"
CUSTOM_LUT_DIR = ROOT / "custom_luts"
USER_IMPORT_LUT_DIR = LUT_DIR / "user_imports"
EXPORTS_DIR = ROOT / "exports"
UPLOADS_TMP_DIR = ROOT / "uploads_tmp"
FAVORITES_FILE = ROOT / "lut_favorites.json"

ALLOWED_IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
ALLOWED_LUT_EXT = {".cube"}
DEFAULT_MAX_DIM = 1440
ALLOWED_EXPORT_MAX_DIMS = {720, 1440, 3840}

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
    # Migrate legacy favorites file name.
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

        # Convert old id prefixes ("builtin:" / "custom:") to current "lut:" ids.
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
        return False, "Directory does not exist."
    if not path.is_dir():
        return False, "Path is not a directory."

    cmd: list[str] | None = None
    if sys.platform.startswith("darwin"):
        cmd = ["open", str(path)]
    elif sys.platform.startswith("win"):
        cmd = ["explorer", str(path)]
    elif shutil.which("xdg-open"):
        cmd = ["xdg-open", str(path)]

    if cmd is None:
        return False, "Auto-open folder is not supported on this system."

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
    # Build FFmpeg lut3d path in a Windows-safe format.
    # - Use forward slashes to avoid backslash escaping issues.
    # - Escape drive-letter style as `C\:/...` to satisfy FFmpeg parser.
    lut_abs = str(lut_path.resolve())
    lut_posix = lut_abs.replace("\\", "/")
    if len(lut_posix) >= 3 and lut_posix[1:3] == ":/":
        lut_posix = lut_posix[0] + r"\:/" + lut_posix[3:]
    lut_escaped = lut_posix.replace("'", "\\'")

    filter_chain = f"lut3d=file='{lut_escaped}'"
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


def scale_to_max_dim_filter(max_dim: int) -> str:
    # Keep the longest edge within max_dim before applying LUT/effects.
    return (
        "scale="
        f"'if(gte(iw,ih),min(iw,{max_dim}),-2)':"
        f"'if(gte(ih,iw),min(ih,{max_dim}),-2)':"
        "flags=lanczos"
    )


def parse_max_dim(raw_value: str | None) -> int:
    try:
        max_dim = int((raw_value or "").strip() or str(DEFAULT_MAX_DIM))
    except ValueError:
        raise ValueError("max_dim must be one of 720, 1440, or 3840.")
    if max_dim not in ALLOWED_EXPORT_MAX_DIMS:
        raise ValueError("max_dim must be one of 720, 1440, or 3840.")
    return max_dim

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
        return jsonify({"error": "lut_ids must be an array."}), 400
    if not isinstance(favorite_raw, bool):
        return jsonify({"error": "favorite must be a boolean."}), 400

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
        return jsonify({"error": "lut_ids must be an array."}), 400

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
        return jsonify({"error": "path cannot be empty."}), 400

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
    max_dim: int,
    run_tmp: Path,
    use_gpu: bool,
) -> None:
    total = len(saved_images) * len(lut_paths)
    done = 0
    success = 0
    failed = 0
    logs: list[str] = []
    outputs: list[str] = []

    def run_one(image_path: Path, lut_path: Path) -> tuple[bool, str | None, str | None]:
        lut_name = safe_name(lut_path.stem)
        out_name = f"{safe_name(image_path.stem)}__{lut_name}.jpg"
        out_path = output_dir / out_name
        effect_chain = build_filter_chain(
            lut_path,
            noise_strength,
            chromashift_px,
            vignette_angle,
            unsharp_amount,
            clarity_contrast,
        )
        filter_chain = f"{scale_to_max_dim_filter(max_dim)},{effect_chain}"

        cmd: list[str] = ["ffmpeg"]
        if use_gpu:
            cmd += ["-hwaccel", "auto"]
        cmd += [
            "-y",
            "-i",
            str(image_path.resolve()),
            "-vf",
            filter_chain,
            "-c:v",
            "mjpeg",
            "-q:v",
            "2",
            "-pix_fmt",
            "yuvj420p",
            "-frames:v",
            "1",
            str(out_path.resolve()),
        ]

        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, shell=False)
        if proc.returncode == 0:
            return True, str(out_path), None

        err_lines = (proc.stderr or "").strip().splitlines()
        err_msg = err_lines[-1] if err_lines else "Unknown error"
        return False, None, f"Failed: {image_path.name} + {lut_path.name} -> {err_msg}"

    try:
        tasks: list[tuple[Path, Path]] = [
            (image_path, lut_path) for image_path in saved_images for lut_path in lut_paths
        ]
        if total <= 1:
            max_workers = 1
        elif use_gpu:
            max_workers = min(2, total)
        else:
            cpu_count = os.cpu_count() or 4
            max_workers = min(max(2, cpu_count), 8, total)

        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {executor.submit(run_one, image_path, lut_path): (image_path, lut_path) for image_path, lut_path in tasks}
            for future in as_completed(futures):
                ok, output_path, err = future.result()
                done += 1
                if ok:
                    success += 1
                    if output_path:
                        outputs.append(output_path)
                else:
                    failed += 1
                    if err:
                        logs.append(err)

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
                    "output_format": "jpg",
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
                    "max_dim": max_dim,
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
        return jsonify({"error": "ffmpeg not found. Please install ffmpeg first."}), 400

    images = request.files.getlist("images")
    selected_luts_raw = request.form.get("selected_luts", "[]")
    output_dir_raw = (request.form.get("output_dir", "") or "").strip()
    grain_strength_raw = request.form.get("grain_strength", "50")
    dispersion_strength_raw = request.form.get("dispersion_strength", "50")
    vignette_strength_raw = request.form.get("vignette_strength", "30")
    sharpen_strength_raw = request.form.get("sharpen_strength", "0")
    clarity_strength_raw = request.form.get("clarity_strength", "0")
    max_dim_raw = request.form.get("max_dim", str(DEFAULT_MAX_DIM))
    use_gpu_raw = (request.form.get("use_gpu", "0") or "").strip()

    try:
        selected_lut_ids = json.loads(selected_luts_raw)
        if not isinstance(selected_lut_ids, list):
            raise ValueError
    except Exception:
        return jsonify({"error": "selected_luts format is invalid."}), 400

    try:
        grain_strength = int(grain_strength_raw)
    except ValueError:
        return jsonify({"error": "grain_strength must be an integer in 0-100."}), 400
    try:
        dispersion_strength = int(dispersion_strength_raw)
    except ValueError:
        return jsonify({"error": "dispersion_strength must be an integer in 0-100."}), 400
    try:
        vignette_strength = int(vignette_strength_raw)
    except ValueError:
        return jsonify({"error": "vignette_strength must be an integer in 0-100."}), 400
    try:
        sharpen_strength = int(sharpen_strength_raw)
    except ValueError:
        return jsonify({"error": "sharpen_strength must be an integer in -100 to 100."}), 400
    try:
        clarity_strength = int(clarity_strength_raw)
    except ValueError:
        return jsonify({"error": "clarity_strength must be an integer in -100 to 100."}), 400
    try:
        max_dim = parse_max_dim(max_dim_raw)
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400

    if not images:
        return jsonify({"error": "Please upload at least one image."}), 400
    if not selected_lut_ids:
        return jsonify({"error": "Please select at least one LUT."}), 400
    if grain_strength < 0 or grain_strength > 100:
        return jsonify({"error": "grain_strength must be in 0-100."}), 400
    if dispersion_strength < 0 or dispersion_strength > 100:
        return jsonify({"error": "dispersion_strength must be in 0-100."}), 400
    if vignette_strength < 0 or vignette_strength > 100:
        return jsonify({"error": "vignette_strength must be in 0-100."}), 400
    if sharpen_strength < -100 or sharpen_strength > 100:
        return jsonify({"error": "sharpen_strength must be in -100 to 100."}), 400
    if clarity_strength < -100 or clarity_strength > 100:
        return jsonify({"error": "clarity_strength must be in -100 to 100."}), 400

    lut_paths: list[Path] = []
    for lut_id in selected_lut_ids:
        p = lut_id_to_path(str(lut_id))
        if p is None:
            return jsonify({"error": f"Invalid LUT: {lut_id}"}), 400
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
        return jsonify({"error": "No valid image format found in uploaded files."}), 400

    noise_strength = grain_to_noise_strength(grain_strength)
    chromashift_px = dispersion_to_chromashift(dispersion_strength)
    vignette_angle = vignette_to_angle(vignette_strength)
    unsharp_amount = sharpen_to_unsharp_amount(sharpen_strength)
    clarity_contrast = clarity_to_contrast(clarity_strength)
    total = len(saved_images) * len(lut_paths)
    use_gpu = use_gpu_raw == "1"

    job_id = uuid.uuid4().hex
    with JOBS_LOCK:
        JOBS[job_id] = {
            "status": "running",
            "progress": 0.0,
            "done": 0,
            "total": total,
            "success": 0,
            "failed": 0,
            "output_format": "jpg",
            "grain_strength": grain_strength,
            "dispersion_strength": dispersion_strength,
            "vignette_strength": vignette_strength,
            "sharpen_strength": sharpen_strength,
            "clarity_strength": clarity_strength,
            "max_dim": max_dim,
            "use_gpu": use_gpu,
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
            max_dim,
            run_tmp,
            use_gpu,
        ),
        daemon=True,
    )
    worker.start()

    return jsonify({"job_id": job_id, "total": total, "output_dir": str(output_dir.resolve())})


@app.post("/api/preview")
def api_preview():
    ensure_dirs()
    if shutil.which("ffmpeg") is None:
        return jsonify({"error": "ffmpeg not found. Please install ffmpeg first."}), 400

    image = request.files.get("image")
    lut_id = str(request.form.get("lut_id", "")).strip()
    if image is None:
        return jsonify({"error": "Missing preview image."}), 400
    if not lut_id:
        return jsonify({"error": "Missing lut_id."}), 400

    lut_path = lut_id_to_path(lut_id)
    if lut_path is None:
        return jsonify({"error": "Invalid LUT."}), 400

    try:
        grain_strength = int(request.form.get("grain_strength", "50"))
        dispersion_strength = int(request.form.get("dispersion_strength", "50"))
        vignette_strength = int(request.form.get("vignette_strength", "30"))
        sharpen_strength = int(request.form.get("sharpen_strength", "0"))
        clarity_strength = int(request.form.get("clarity_strength", "0"))
        max_dim = parse_max_dim(request.form.get("max_dim", str(DEFAULT_MAX_DIM)))
    except ValueError:
        return jsonify({"error": "Preview parameters are invalid."}), 400

    use_gpu_raw = (request.form.get("use_gpu", "0") or "").strip()

    if not (0 <= grain_strength <= 100):
        return jsonify({"error": "grain_strength must be in 0-100."}), 400
    if not (0 <= dispersion_strength <= 100):
        return jsonify({"error": "dispersion_strength must be in 0-100."}), 400
    if not (0 <= vignette_strength <= 100):
        return jsonify({"error": "vignette_strength must be in 0-100."}), 400
    if not (-100 <= sharpen_strength <= 100):
        return jsonify({"error": "sharpen_strength must be in -100 to 100."}), 400
    if not (-100 <= clarity_strength <= 100):
        return jsonify({"error": "clarity_strength must be in -100 to 100."}), 400

    noise_strength = grain_to_noise_strength(grain_strength)
    chromashift_px = dispersion_to_chromashift(dispersion_strength)
    vignette_angle = vignette_to_angle(vignette_strength)
    unsharp_amount = sharpen_to_unsharp_amount(sharpen_strength)
    clarity_contrast = clarity_to_contrast(clarity_strength)
    use_gpu = use_gpu_raw == "1"
    effect_chain = build_filter_chain(
        lut_path,
        noise_strength,
        chromashift_px,
        vignette_angle,
        unsharp_amount,
        clarity_contrast,
    )
    filter_chain = f"{scale_to_max_dim_filter(max_dim)},{effect_chain}"

    suffix = Path(image.filename or "preview.png").suffix.lower()
    if suffix not in ALLOWED_IMAGE_EXT:
        suffix = ".png"

    UPLOADS_TMP_DIR.mkdir(parents=True, exist_ok=True)
    logger.info(f"Start preview generation - LUT: {lut_path.name}")

    with tempfile.TemporaryDirectory(prefix="lut_preview_", dir=str(UPLOADS_TMP_DIR)) as tmp_dir:
        src_path = Path(tmp_dir) / f"input{suffix}"
        out_path = Path(tmp_dir) / "preview.jpg"
        image.save(src_path)

        src_abs = str(src_path.resolve())
        out_abs = str(out_path.resolve())

        cmd: list[str] = ["ffmpeg"]
        if use_gpu:
            cmd += ["-hwaccel", "auto"]
        cmd += [
            "-y",
            "-i",
            src_abs,
            "-vf",
            filter_chain,
            "-c:v",
            "mjpeg",
            "-q:v",
            "3",
            "-pix_fmt",
            "yuvj420p",
            "-frames:v",
            "1",
            out_abs,
        ]

        logger.info(f"FFmpeg command: {' '.join(cmd)}")
        logger.info(f"Input path: {src_abs}")
        logger.info(f"Output path: {out_abs}")
        logger.info(f"Filter Chain: {filter_chain}")

        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=False)

        logger.info(f"FFmpeg return code: {proc.returncode}")
        if proc.stdout:
            logger.info(f"FFmpeg stdout: {proc.stdout[:500]}")
        if proc.stderr:
            logger.error(f"FFmpeg stderr: {proc.stderr[:1000]}")
        logger.info(f"Output exists: {out_path.exists()}")

        if proc.returncode != 0 or not out_path.exists():
            err_msg = proc.stderr or "Preview generation failed."
            err_lines = err_msg.strip().splitlines()
            error_text = err_lines[-1] if err_lines else err_msg
            logger.error(f"Preview processing failed: {error_text}")
            return jsonify(
                {
                    "error": error_text,
                    "debug_cmd": " ".join(cmd) if sys.platform.startswith("win") else "",
                    "stderr": proc.stderr[-500:] if proc.stderr else "",
                }
            ), 500

        payload = base64.b64encode(out_path.read_bytes()).decode("ascii")
        return jsonify({"image_data_url": f"data:image/jpeg;base64,{payload}"})

@app.get("/api/process/status/<job_id>")
def api_process_status(job_id: str):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if job is None:
            return jsonify({"error": "Task not found or expired."}), 404
        payload = dict(job)
    return jsonify(payload)


if __name__ == "__main__":
    ensure_dirs()
    app.run(host="127.0.0.1", port=8787, debug=False)
