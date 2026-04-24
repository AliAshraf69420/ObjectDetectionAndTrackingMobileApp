import os
import importlib
import importlib.util
import threading
from typing import Any

from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
import cv2
import numpy as np

try:
    dotenv_mod = importlib.import_module("dotenv")
    load_dotenv = getattr(dotenv_mod, "load_dotenv", None)
    if callable(load_dotenv):
        load_dotenv()
except Exception:
    pass

app = FastAPI()


def _parse_cors_allow_origins(raw: str) -> list[str]:
    value = (raw or "*").strip()
    if value == "*":
        return ["*"]
    return [origin.strip() for origin in value.split(",") if origin.strip()]


MAX_IMAGE_BYTES = int(os.getenv("MAX_IMAGE_BYTES", str(8 * 1024 * 1024)))
CORS_ALLOW_ORIGINS = _parse_cors_allow_origins(os.getenv("CORS_ALLOW_ORIGINS", "*"))

DETECTION_BACKEND = os.getenv("DETECTION_BACKEND", "yolo").strip().lower()
YOLO_MODEL = os.getenv("YOLO_MODEL", "yolov8n.pt").strip()
TARGET_CLASS = os.getenv("TARGET_CLASS", "bottle").strip().lower()
YOLO_CONF = float(os.getenv("YOLO_CONF", "0.35"))
YOLO_DEVICE = os.getenv("YOLO_DEVICE", "cpu").strip().lower()

app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ALLOW_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _iou(a: dict[str, int], b: dict[str, int]) -> float:
    ax1, ay1 = a["x"], a["y"]
    ax2, ay2 = a["x"] + a["width"], a["y"] + a["height"]
    bx1, by1 = b["x"], b["y"]
    bx2, by2 = b["x"] + b["width"], b["y"] + b["height"]

    inter_w = max(0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0, min(ay2, by2) - max(ay1, by1))
    inter = inter_w * inter_h
    if inter <= 0:
        return 0.0
    area_a = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
    denom = area_a + area_b - inter
    return float(inter / denom) if denom > 0 else 0.0


_state_lock = threading.Lock()
_tracks: dict[int, dict[str, int]] = {}  # track_id -> last bbox
_next_id: int = 0


def _load_yolo():
    try:
        ul = importlib.import_module("ultralytics")
    except Exception as e:
        raise HTTPException(
            status_code=503,
            detail=(
                "Ultralytics YOLO backend not available. Install server deps including ultralytics/torch, "
                "or set DETECTION_BACKEND=none. Error: " + str(e)
            ),
        )

    YOLO = getattr(ul, "YOLO", None)
    if YOLO is None:
        raise HTTPException(status_code=503, detail="ultralytics.YOLO not found")

    # Cache on the function object to avoid module-level side-effects during import.
    cached = getattr(_load_yolo, "_model", None)
    if cached is not None:
        return cached

    try:
        model = YOLO(YOLO_MODEL)
    except Exception as e:
        raise HTTPException(
            status_code=503,
            detail=(
                f"Failed to load YOLO model '{YOLO_MODEL}'. "
                "If this is the first run, the weights may need to download. Error: " + str(e)
            ),
        )

    setattr(_load_yolo, "_model", model)
    return model


def _detect_target_boxes(frame: np.ndarray) -> list[dict[str, Any]]:
    if DETECTION_BACKEND in {"none", "off", "disabled"}:
        return []

    if DETECTION_BACKEND != "yolo":
        raise HTTPException(status_code=400, detail=f"Unsupported DETECTION_BACKEND: {DETECTION_BACKEND}")

    model = _load_yolo()

    results = model.predict(frame, conf=YOLO_CONF, device=YOLO_DEVICE, verbose=False)
    if not results:
        return []
    r0 = results[0]
    boxes_obj = getattr(r0, "boxes", None)
    if boxes_obj is None:
        return []

    names = getattr(model, "names", None) or getattr(r0, "names", None) or {}

    out: list[dict[str, Any]] = []
    xyxy = getattr(boxes_obj, "xyxy", None)
    conf = getattr(boxes_obj, "conf", None)
    cls = getattr(boxes_obj, "cls", None)

    if xyxy is None or conf is None or cls is None:
        return []

    xyxy_np = xyxy.cpu().numpy() if hasattr(xyxy, "cpu") else np.array(xyxy)
    conf_np = conf.cpu().numpy() if hasattr(conf, "cpu") else np.array(conf)
    cls_np = cls.cpu().numpy() if hasattr(cls, "cpu") else np.array(cls)

    for i in range(len(xyxy_np)):
        class_id = int(cls_np[i])
        class_name = str(names.get(class_id, class_id)).lower()
        if class_name != TARGET_CLASS:
            continue

        x1, y1, x2, y2 = xyxy_np[i].tolist()
        x1i, y1i = int(max(0, round(x1))), int(max(0, round(y1)))
        x2i, y2i = int(max(0, round(x2))), int(max(0, round(y2)))
        out.append(
            {
                "x": x1i,
                "y": y1i,
                "width": max(0, x2i - x1i),
                "height": max(0, y2i - y1i),
                "label": TARGET_CLASS,
                "confidence": float(conf_np[i]),
            }
        )

    out.sort(key=lambda b: b.get("confidence", 0.0), reverse=True)
    return out


def _match_and_update_tracks(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Greedy IoU-based multi-object tracker. Returns candidates with stable track_id fields."""
    global _tracks, _next_id

    if not candidates:
        with _state_lock:
            _tracks = {}
        return []

    with _state_lock:
        prev_tracks = dict(_tracks)
        current_next_id = _next_id

    track_ids = list(prev_tracks.keys())
    assigned_candidates: set[int] = set()
    assigned_tracks: set[int] = set()
    new_tracks: dict[int, dict[str, int]] = {}
    result: list[dict[str, Any]] = []

    if track_ids:
        # Score every (track, candidate) pair and greedily assign highest-IoU matches first.
        pairs: list[tuple[float, int, int]] = []
        for ti, tid in enumerate(track_ids):
            for ci, c in enumerate(candidates):
                box_int = {k: int(c[k]) for k in ("x", "y", "width", "height")}
                pairs.append((_iou(prev_tracks[tid], box_int), ti, ci))
        pairs.sort(reverse=True)

        for score, ti, ci in pairs:
            if score < 0.15:
                break
            if ti in assigned_tracks or ci in assigned_candidates:
                continue
            tid = track_ids[ti]
            c = candidates[ci]
            new_tracks[tid] = {k: int(c[k]) for k in ("x", "y", "width", "height")}
            result.append({**c, "track_id": tid})
            assigned_tracks.add(ti)
            assigned_candidates.add(ci)

    # Unmatched detections become new tracks.
    for ci, c in enumerate(candidates):
        if ci in assigned_candidates:
            continue
        tid = current_next_id
        current_next_id += 1
        new_tracks[tid] = {k: int(c[k]) for k in ("x", "y", "width", "height")}
        result.append({**c, "track_id": tid})

    with _state_lock:
        _tracks = new_tracks
        _next_id = current_next_id

    return result


@app.get("/health")
def health():
    yolo_available = importlib.util.find_spec("ultralytics") is not None
    return {
        "status": "ok",
        "detection_backend": DETECTION_BACKEND,
        "target_class": TARGET_CLASS,
        "yolo_model": YOLO_MODEL,
        "yolo_device": YOLO_DEVICE,
        "yolo_available": yolo_available,
    }

@app.post("/track-frame")
async def track_frame(file: UploadFile = File(...)):
    data = await file.read()

    if not data:
        raise HTTPException(status_code=400, detail="Empty upload")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image too large")

    np_arr = np.frombuffer(data, np.uint8)
    frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

    if frame is None:
        raise HTTPException(status_code=400, detail="Invalid image")

    h, w, _ = frame.shape

    candidates = _detect_target_boxes(frame)
    boxes = _match_and_update_tracks(candidates)

    return {
        "width": w,
        "height": h,
        "boxes": boxes,
    }
