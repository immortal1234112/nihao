"""
AI Inference Server — Local Image & Video Generation
=====================================================
Provides REST API for Stable Diffusion (image) and CogVideoX (video) generation.
Designed for RTX 4060 8GB or similar GPUs. Falls back to CPU if no GPU available.

Endpoints:
  POST /api/v1/generate/image      — Text-to-image
  POST /api/v1/generate/video      — Text-to-video (async)
  GET  /api/v1/generate/video/{id} — Query video task status
  GET  /api/v1/health              — Health check + model info
"""

from __future__ import annotations

import base64
import io
import logging
import os
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("inference")

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
MODEL_DIR = Path(os.environ.get("MODEL_DIR", Path(__file__).parent / "models"))
MODEL_DIR.mkdir(parents=True, exist_ok=True)

IMAGE_MODEL_ID = os.environ.get("IMAGE_MODEL", "stabilityai/sdxl-turbo")
VIDEO_MODEL_ID = os.environ.get("VIDEO_MODEL", "THUDM/CogVideoX-2b")

USE_FP16 = os.environ.get("USE_FP16", "1") == "1"
USE_CPU_OFFLOAD = os.environ.get("USE_CPU_OFFLOAD", "1") == "1"

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
DTYPE = torch.float16 if USE_FP16 and DEVICE == "cuda" else torch.float32

# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
app = FastAPI(
    title="AI Inference Server",
    version="1.0.0",
    docs_url="/docs",
)

# ---------------------------------------------------------------------------
# Global model handles (lazy-loaded)
# ---------------------------------------------------------------------------
image_pipe: Optional[object] = None
video_pipe: Optional[object] = None
model_lock = threading.Lock()

# 推理串行化锁：diffusers pipeline 非线程安全，单卡并发推理会导致崩溃/显存溢出
generation_lock = threading.Lock()

# Video task store (in-memory; use Redis in production)
video_tasks: dict[str, dict] = {}

# 视频任务保留时间：终态任务 30 分钟后清理，防止内存无限增长
TASK_TTL_SECONDS = 30 * 60
TERMINAL_STATUSES = {"completed", "failed"}


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------
class ImageRequest(BaseModel):
    prompt: str = Field(..., description="Image description prompt")
    negative_prompt: str = Field(default="blurry, low quality, distorted", description="Negative prompt")
    width: int = Field(default=1024, ge=256, le=2048, description="Image width")
    height: int = Field(default=1024, ge=256, le=2048, description="Image height")
    num_images: int = Field(default=1, ge=1, le=4, description="Number of images")
    steps: int = Field(default=4, ge=1, le=50, description="Inference steps (4 for Turbo)")
    guidance_scale: float = Field(default=0.0, ge=0.0, le=20.0, description="CFG scale (0 for Turbo)")
    seed: int = Field(default=-1, description="Random seed (-1 = random)")
    style: str = Field(default="realistic", description="Style hint (ignored by base model)")


class ImageResponse(BaseModel):
    images: list[str] = Field(description="Base64-encoded PNG images")
    seed: int
    model: str
    time_seconds: float


class VideoRequest(BaseModel):
    prompt: str = Field(..., description="Video description prompt")
    negative_prompt: str = Field(default="blurry, low quality, distorted")
    num_frames: int = Field(default=49, ge=9, le=100)
    fps: int = Field(default=8, ge=4, le=30)
    seed: int = Field(default=-1)
    guidance_scale: float = Field(default=6.0, ge=1.0, le=20.0)
    num_inference_steps: int = Field(default=50, ge=10, le=100)


class VideoTaskResponse(BaseModel):
    task_id: str
    status: str  # "queued" | "processing" | "completed" | "failed"
    video_base64: Optional[str] = None
    error: Optional[str] = None
    progress: float = 0.0


class HealthResponse(BaseModel):
    status: str
    device: str
    dtype: str
    image_model: str
    video_model: str
    image_model_loaded: bool
    video_model_loaded: bool
    gpu_name: Optional[str] = None
    gpu_memory_mb: Optional[float] = None


# ---------------------------------------------------------------------------
# Model loading
# ---------------------------------------------------------------------------
def get_image_pipeline():
    """Lazy-load the image generation pipeline."""
    global image_pipe
    if image_pipe is not None:
        return image_pipe

    with model_lock:
        if image_pipe is not None:
            return image_pipe

        log.info("Loading image model: %s (device=%s, dtype=%s)", IMAGE_MODEL_ID, DEVICE, DTYPE)

        from diffusers import AutoPipelineForText2Image

        pipe = AutoPipelineForText2Image.from_pretrained(
            IMAGE_MODEL_ID,
            torch_dtype=DTYPE,
            cache_dir=str(MODEL_DIR),
        )

        if USE_CPU_OFFLOAD and DEVICE == "cuda":
            pipe.enable_model_cpu_offload()
            log.info("  -> CPU offload enabled")
        else:
            pipe = pipe.to(DEVICE)

        # Optimizations
        if hasattr(pipe, "enable_vae_slicing"):
            pipe.enable_vae_slicing()
        if hasattr(pipe, "enable_attention_slicing"):
            try:
                pipe.enable_attention_slicing(slice_size="auto")
            except Exception:
                pass

        image_pipe = pipe
        log.info("  -> Image model loaded ✓")
        return image_pipe


def get_video_pipeline():
    """Lazy-load the video generation pipeline."""
    global video_pipe
    if video_pipe is not None:
        return video_pipe

    with model_lock:
        if video_pipe is not None:
            return video_pipe

        log.info("Loading video model: %s (device=%s, dtype=%s)", VIDEO_MODEL_ID, DEVICE, DTYPE)

        from diffusers import CogVideoXPipeline

        pipe = CogVideoXPipeline.from_pretrained(
            VIDEO_MODEL_ID,
            torch_dtype=DTYPE,
            cache_dir=str(MODEL_DIR),
        )

        if USE_CPU_OFFLOAD and DEVICE == "cuda":
            pipe.enable_model_cpu_offload()
        else:
            pipe = pipe.to(DEVICE)

        if hasattr(pipe, "enable_vae_slicing"):
            pipe.enable_vae_slicing()

        video_pipe = pipe
        log.info("  -> Video model loaded ✓")
        return video_pipe


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def pil_to_base64(image) -> str:
    """Convert PIL Image to base64 PNG string."""
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def video_to_base64(frames: list, fps: int = 8) -> str:
    """Convert list of PIL frames to base64 MP4 string (fps 来自请求参数)."""
    import imageio

    buf = io.BytesIO()
    writer = imageio.get_writer(buf, format="mp4", fps=fps, codec="libx264")
    for frame in frames:
        writer.append_data(frame)
    writer.close()
    buf.seek(0)
    return base64.b64encode(buf.read()).decode("utf-8")


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------
@app.get("/api/v1/health", response_model=HealthResponse)
def health():
    gpu_name = None
    gpu_memory = None
    if DEVICE == "cuda":
        gpu_name = torch.cuda.get_device_name(0)
        gpu_memory = torch.cuda.get_device_properties(0).total_memory / (1024**2)

    return HealthResponse(
        status="ok",
        device=DEVICE,
        dtype=str(DTYPE),
        image_model=IMAGE_MODEL_ID,
        video_model=VIDEO_MODEL_ID,
        image_model_loaded=image_pipe is not None,
        video_model_loaded=video_pipe is not None,
        gpu_name=gpu_name,
        gpu_memory_mb=gpu_memory,
    )


@app.post("/api/v1/generate/image", response_model=ImageResponse)
def generate_image(req: ImageRequest):
    t0 = time.time()

    try:
        pipe = get_image_pipeline()
    except Exception as e:
        log.exception("Failed to load image model")
        raise HTTPException(500, f"Model load failed: {e}")

    seed = req.seed if req.seed >= 0 else torch.randint(0, 2**31 - 1, (1,)).item()
    generator = torch.Generator(device=DEVICE if DEVICE == "cuda" else "cpu").manual_seed(int(seed))

    # Resolve size (must be divisible by 8)
    w = (req.width // 8) * 8
    h = (req.height // 8) * 8

    kwargs = dict(
        prompt=req.prompt,
        negative_prompt=req.negative_prompt if req.negative_prompt else None,
        height=h,
        width=w,
        num_images_per_prompt=req.num_images,
        num_inference_steps=req.steps,
        guidance_scale=req.guidance_scale if req.guidance_scale > 0 else None,
        generator=generator,
    )

    # SDXL Turbo doesn't use guidance_scale
    if req.guidance_scale <= 0:
        kwargs.pop("guidance_scale", None)

    log.info("Generating %d image(s): %s (%dx%d)", req.num_images, req.prompt[:80], w, h)

    try:
        with generation_lock:
            with torch.inference_mode():
                output = pipe(**{k: v for k, v in kwargs.items() if v is not None})
    except torch.cuda.OutOfMemoryError:
        torch.cuda.empty_cache()
        raise HTTPException(507, "GPU out of memory. Try smaller image size or fewer images.")
    except Exception as e:
        log.exception("Image generation failed")
        raise HTTPException(500, f"Generation failed: {e}")

    images = [pil_to_base64(img) for img in output.images]
    elapsed = time.time() - t0
    log.info("  -> %d image(s) in %.1fs (seed=%d)", len(images), elapsed, seed)

    return ImageResponse(
        images=images,
        seed=int(seed),
        model=IMAGE_MODEL_ID,
        time_seconds=round(elapsed, 1),
    )


@app.post("/api/v1/generate/video", response_model=VideoTaskResponse)
def generate_video_async(req: VideoRequest):
    """Submit video generation (async), returns immediately with task_id."""
    task_id = uuid.uuid4().hex[:12]
    seed = req.seed if req.seed >= 0 else torch.randint(0, 2**31 - 1, (1,)).item()

    video_tasks[task_id] = {
        "status": "queued",
        "progress": 0.0,
        "video_base64": None,
        "error": None,
        "req": req,
        "seed": int(seed),
        "submitted_at": time.time(),
    }

    # Start generation in background thread
    threading.Thread(target=_run_video_generation, args=(task_id,), daemon=True).start()

    return VideoTaskResponse(task_id=task_id, status="queued")


@app.get("/api/v1/generate/video/{task_id}", response_model=VideoTaskResponse)
def query_video(task_id: str):
    """Query video generation status."""
    task = video_tasks.get(task_id)
    if task is None:
        raise HTTPException(404, f"Task {task_id} not found")

    return VideoTaskResponse(
        task_id=task_id,
        status=task["status"],
        video_base64=task.get("video_base64"),
        error=task.get("error"),
        progress=task.get("progress", 0.0),
    )


def _sweep_video_tasks():
    """周期清扫：终态任务超过 TTL 后移除，防止内存无限增长。"""
    while True:
        time.sleep(TASK_TTL_SECONDS)
        now = time.time()
        stale = [
            tid for tid, t in video_tasks.items()
            if t.get("submitted_at") and (now - t["submitted_at"]) > TASK_TTL_SECONDS
            and t["status"] in TERMINAL_STATUSES
        ]
        for tid in stale:
            video_tasks.pop(tid, None)
            log.info("Cleaned up expired video task: %s", tid)
        if stale:
            log.info("Video task store: %d remaining", len(video_tasks))


def _run_video_generation(task_id: str):
    """Background worker for video generation."""
    task = video_tasks[task_id]
    task["status"] = "processing"

    try:
        pipe = get_video_pipeline()
    except Exception as e:
        task["status"] = "failed"
        task["error"] = f"Model load failed: {e}"
        return

    req = task["req"]
    seed = task["seed"]
    generator = torch.Generator(device=DEVICE if DEVICE == "cuda" else "cpu").manual_seed(seed)

    log.info("Generating video: %s (%d frames, seed=%d)", req.prompt[:80], req.num_frames, seed)

    try:
        # 串行化推理：diffusers pipeline 非线程安全，多任务并发会互相踩踏
        with generation_lock:
            with torch.inference_mode():
                output = pipe(
                    prompt=req.prompt,
                    negative_prompt=req.negative_prompt or None,
                    num_frames=req.num_frames,
                    num_inference_steps=req.num_inference_steps,
                    guidance_scale=req.guidance_scale,
                    generator=generator,
                )
    except torch.cuda.OutOfMemoryError:
        torch.cuda.empty_cache()
        task["status"] = "failed"
        task["error"] = "GPU out of memory. Try fewer frames."
        return
    except Exception as e:
        log.exception("Video generation failed")
        task["status"] = "failed"
        task["error"] = str(e)
        return

    # Convert frames to base64 video
    frames = output.frames[0]  # First (only) video
    # frames is List[PIL.Image]
    pil_frames = []
    import numpy as np
    for frame in frames:
        if isinstance(frame, np.ndarray):
            from PIL import Image
            pil_frames.append(Image.fromarray(frame))
        else:
            pil_frames.append(frame)  # already PIL

    task["video_base64"] = video_to_base64(pil_frames, fps=req.fps)
    task["status"] = "completed"
    task["progress"] = 100.0
    log.info("  -> Video task %s done", task_id)


# ---------------------------------------------------------------------------
# Lifecycle events
# ---------------------------------------------------------------------------
@app.on_event("startup")
def startup():
    threading.Thread(target=_sweep_video_tasks, daemon=True).start()
    log.info("Video task sweeper started (TTL=%ds)", TASK_TTL_SECONDS)
    log.info("=" * 60)
    log.info("AI Inference Server starting...")
    log.info("  Device:      %s", DEVICE)
    log.info("  Dtype:       %s", DTYPE)
    log.info("  Image model: %s", IMAGE_MODEL_ID)
    log.info("  Video model: %s", VIDEO_MODEL_ID)
    log.info("  Model dir:   %s", MODEL_DIR)
    log.info("  FP16:        %s", USE_FP16)
    log.info("  CPU offload: %s", USE_CPU_OFFLOAD)
    if DEVICE == "cuda":
        log.info("  GPU:         %s", torch.cuda.get_device_name(0))
        mem_gb = torch.cuda.get_device_properties(0).total_memory / (1024**3)
        log.info("  VRAM:        %.1f GB", mem_gb)
    log.info("=" * 60)
    log.info("Models will be lazy-loaded on first request.")
    log.info("First request may take 30-120s to download from HuggingFace.")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", 7860))
    host = os.environ.get("HOST", "0.0.0.0")

    uvicorn.run(
        "server:app",
        host=host,
        port=port,
        reload=False,
        log_level="info",
    )
