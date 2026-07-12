"""Optional local text-to-speech via Kokoro (kokoro-onnx).

Loaded lazily and treated as optional: if kokoro-onnx isn't installed or the
model can't be fetched/loaded, TTS reports itself unavailable and the API/UI
degrade gracefully instead of erroring.
"""

from __future__ import annotations

import ctypes
import glob
import io
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import wave
from typing import List, Optional
from urllib.request import urlretrieve

from bs4 import BeautifulSoup

from .settings import settings

logger = logging.getLogger(__name__)


def _preload_cuda_libs() -> None:
    """onnxruntime-gpu's native extension is dynamically linked against CUDA and
    cuDNN shared libraries (libcudart.so.13, libcudnn.so.9, …). Those ship in the
    nvidia-*-cu13 pip wheels, but under a consolidated ``nvidia/cu13/lib`` layout
    that onnxruntime's own auto-loader doesn't search — so ``import onnxruntime``
    fails with "libcudart.so.13: cannot open shared object file" even though the
    libs are installed. We dlopen them with RTLD_GLOBAL first so the linker
    resolves onnxruntime's DT_NEEDED entries against already-loaded libraries.

    No-op when the wheels aren't present (CPU-only installs on plain
    ``onnxruntime`` have no ``nvidia/`` libs to find), so this is safe to always
    call. Multiple passes handle inter-library load order without hardcoding it.
    """
    sos: List[str] = []
    seen = set()
    for entry in sys.path:
        if not entry or not os.path.isdir(entry):
            continue
        for so in glob.glob(os.path.join(entry, "nvidia", "*", "lib", "*.so.*")):
            name = os.path.basename(so)
            if name not in seen:
                seen.add(name)
                sos.append(so)
    if not sos:
        return
    pending = sos
    for _ in range(3):
        failed = []
        for so in pending:
            try:
                ctypes.CDLL(so, mode=ctypes.RTLD_GLOBAL)
            except OSError:
                failed.append(so)
        if not failed:
            return
        pending = failed
    logger.debug("some CUDA libs could not be preloaded: %s", pending)

_BASE = ("https://github.com/thewh1teagle/kokoro-onnx/releases/download/"
         "model-files-v1.0")
VOICES_URL = f"{_BASE}/voices-v1.0.bin"
VOICES_NAME = "voices-v1.0.bin"

# Two model variants: int8 is quantized for CPU (smaller, ~real-time on CPU);
# fp32 is the full-precision model used on GPU (int8 doesn't accelerate well on
# CUDA). The right one is chosen at load time based on the selected device.
CPU_MODEL_URL = f"{_BASE}/kokoro-v1.0.int8.onnx"
CPU_MODEL_NAME = "kokoro-v1.0.int8.onnx"
GPU_MODEL_URL = f"{_BASE}/kokoro-v1.0.onnx"
GPU_MODEL_NAME = "kokoro-v1.0.onnx"

DEFAULT_VOICE = "af_heart"
MAX_CHUNK_CHARS = 600   # normal chunk size (a few sentences)
FIRST_CHUNK_CHARS = 180  # tiny first chunk so audio starts within a second or two


class _TTS:
    def __init__(self) -> None:
        self._kokoro = None
        self._voices: List[str] = []
        self._device = "unknown"   # "cuda" or "cpu" once loaded
        self._unavailable = False
        self._load_lock = threading.Lock()
        self._synth_lock = threading.Lock()  # serialize CPU-bound synthesis

    def _resolve_providers(self):
        """Pick the onnxruntime provider list and matching model variant based on
        settings.tts_device and what's actually available. Returns
        (providers, model_name, model_url, device_label)."""
        import onnxruntime as rt

        available = rt.get_available_providers()
        want = settings.tts_device
        has_cuda = "CUDAExecutionProvider" in available
        # Decide whether to attempt GPU.
        use_gpu = (want == "cuda") or (want == "auto" and has_cuda)
        if use_gpu and not has_cuda:
            logger.warning(
                "TTS device=cuda requested but CUDAExecutionProvider not "
                "available (install onnxruntime-gpu + CUDA/cuDNN); using CPU.")
            use_gpu = False

        if use_gpu:
            return (["CUDAExecutionProvider", "CPUExecutionProvider"],
                    GPU_MODEL_NAME, GPU_MODEL_URL, "cuda")
        return (["CPUExecutionProvider"], CPU_MODEL_NAME, CPU_MODEL_URL, "cpu")

    def _build_session(self, model_path, providers):
        import onnxruntime as rt

        opts = rt.SessionOptions()
        opts.graph_optimization_level = rt.GraphOptimizationLevel.ORT_ENABLE_ALL
        if settings.tts_threads > 0:
            opts.intra_op_num_threads = settings.tts_threads
        return rt.InferenceSession(
            str(model_path), sess_options=opts, providers=providers)

    def _download(self, path, url, label):
        if not path.exists():
            logger.info("downloading Kokoro %s (%s)…", label, path.name)
            urlretrieve(url, path)

    def _ensure(self):
        if self._kokoro is not None:
            return self._kokoro
        if self._unavailable:
            return None
        with self._load_lock:
            if self._kokoro is not None:
                return self._kokoro
            # Must run before anything imports onnxruntime (kokoro_onnx does).
            _preload_cuda_libs()
            try:
                from kokoro_onnx import Kokoro
            except Exception as e:  # noqa: BLE001
                logger.warning("TTS unavailable (kokoro-onnx not installed): %s", e)
                self._unavailable = True
                return None

            providers, model_name, model_url, device = self._resolve_providers()
            voices = settings.model_dir / VOICES_NAME
            model = settings.model_dir / model_name
            try:
                settings.model_dir.mkdir(parents=True, exist_ok=True)
                self._download(voices, VOICES_URL, "voices")
                self._download(model, model_url, f"{device} model")
                session = self._build_session(model, providers)
                self._kokoro = Kokoro.from_session(session, str(voices))
                self._voices = sorted(self._kokoro.get_voices())
                # Report the provider onnxruntime actually bound (CUDA can silently
                # fall back to CPU inside the session if the runtime libs are broken).
                active = session.get_providers()
                self._device = "cuda" if active and active[0] == "CUDAExecutionProvider" else "cpu"
                logger.info("TTS loaded on %s (providers=%s, model=%s)",
                            self._device, active, model_name)
            except Exception as e:  # noqa: BLE001
                # GPU path can fail late (missing cuDNN, OOM, etc.). Fall back to
                # the CPU int8 model once before giving up.
                if device == "cuda":
                    logger.warning(
                        "TTS GPU load failed (%s); falling back to CPU.", e)
                    try:
                        cpu_model = settings.model_dir / CPU_MODEL_NAME
                        self._download(cpu_model, CPU_MODEL_URL, "cpu model")
                        session = self._build_session(
                            cpu_model, ["CPUExecutionProvider"])
                        self._kokoro = Kokoro.from_session(session, str(voices))
                        self._voices = sorted(self._kokoro.get_voices())
                        self._device = "cpu"
                        logger.info("TTS loaded on cpu (GPU fallback).")
                        return self._kokoro
                    except Exception as e2:  # noqa: BLE001
                        e = e2
                logger.warning("TTS unavailable (model load/download failed): %s", e)
                self._unavailable = True
                return None
        return self._kokoro

    def available(self) -> bool:
        return self._ensure() is not None

    def device(self) -> str:
        """'cuda', 'cpu', or 'unknown' (before load). Surfaced in the API."""
        self._ensure()
        return self._device

    def voices(self) -> List[str]:
        self._ensure()
        return self._voices

    def valid_voice(self, voice: Optional[str]) -> str:
        v = voice or DEFAULT_VOICE
        return v if v in self._voices else DEFAULT_VOICE

    def _reset(self) -> None:
        """Drop the loaded model so the next _ensure() rebuilds it — used to
        recover from a runtime GPU failure part way through a long chapter."""
        with self._load_lock:
            self._kokoro = None
            self._unavailable = False

    def _create(self, text: str, voice: str, speed: float):
        """Synthesize once, recovering from a single runtime failure. A GPU can
        throw mid-session (e.g. cuBLAS "resource allocation failed" after many
        chunks of a long chapter); rebuild the session — which frees GPU memory
        and, if the GPU stays wedged, falls back to CPU inside _ensure — then
        retry, so one failed chunk doesn't kill playback."""
        kokoro = self._ensure()
        if kokoro is None:
            raise RuntimeError("TTS unavailable")
        try:
            return kokoro.create(text, voice=voice, speed=speed, lang="en-us")
        except Exception as e:  # noqa: BLE001
            logger.warning("TTS synthesis failed (%s); rebuilding model and retrying", e)
            self._reset()
            kokoro = self._ensure()
            if kokoro is None:
                raise RuntimeError("TTS unavailable after reset") from e
            return kokoro.create(text, voice=voice, speed=speed, lang="en-us")

    def synth_wav(self, text: str, voice: str, speed: float) -> bytes:
        import numpy as np

        with self._synth_lock:
            samples, sample_rate = self._create(text, voice, speed)
        pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sample_rate)
            w.writeframes(pcm.tobytes())
        return buf.getvalue()


tts = _TTS()


# Kokoro emits 24 kHz mono WAV — ~1.5 MB for a ~40 s chunk. That's slow to stream
# to a phone (it was the real cause of "narration takes forever to start"). We
# transcode to mono MP3 (~6x smaller) via ffmpeg when it's available. Speech at
# 64 kbps mono is transparent.
_FFMPEG = shutil.which("ffmpeg")
MP3_BITRATE = "64k"


def encode_mp3(wav_bytes: bytes) -> Optional[bytes]:
    """Transcode a WAV blob to mono MP3 via ffmpeg (stdin -> stdout). Returns None
    if ffmpeg is missing or fails, so the caller can fall back to serving WAV."""
    if not _FFMPEG:
        return None
    try:
        proc = subprocess.run(
            [_FFMPEG, "-hide_banner", "-loglevel", "error",
             "-i", "pipe:0", "-ac", "1", "-b:a", MP3_BITRATE, "-f", "mp3", "pipe:1"],
            input=wav_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError) as e:
        logger.warning("mp3 encode: ffmpeg failed to run: %s", e)
        return None
    if proc.returncode == 0 and proc.stdout:
        return proc.stdout
    logger.warning("mp3 encode failed (rc=%s): %s", proc.returncode,
                   proc.stderr.decode("utf-8", "ignore")[:200])
    return None


_SENT_END = re.compile(r'(?<=[.!?"”’])\s+')


def segment_paragraphs(html: str) -> List[List[str]]:
    """Chapter HTML -> paragraphs of sentences. Used for both narration and the
    read-along highlighting (the frontend renders these as sentence spans)."""
    soup = BeautifulSoup(html, "html.parser")
    blocks = soup.find_all("p")
    if blocks:
        texts = [b.get_text(" ", strip=True) for b in blocks]
    else:
        texts = re.split(r"\n+", soup.get_text("\n", strip=True))

    paragraphs: List[List[str]] = []
    for t in texts:
        t = re.sub(r"\s+", " ", t).strip()
        if not t:
            continue
        sentences = [s for s in (x.strip() for x in _SENT_END.split(t)) if s]
        if sentences:
            paragraphs.append(sentences)
    if not paragraphs:
        whole = soup.get_text(" ", strip=True)
        if whole:
            paragraphs = [[whole]]
    return paragraphs


def build_chunks(paragraphs: List[List[str]]):
    """Group flattened sentences into audio chunks (deterministic). Returns
    (flat_sentences, chunks) where each chunk is a list of flat sentence indices.
    The first chunk is kept small so playback starts almost immediately."""
    flat = [s for para in paragraphs for s in para]
    chunks: List[List[int]] = []
    current: List[int] = []
    length = 0
    for i, s in enumerate(flat):
        limit = FIRST_CHUNK_CHARS if not chunks else MAX_CHUNK_CHARS
        if current and length + len(s) + 1 > limit:
            chunks.append(current)
            current = []
            length = 0
        current.append(i)
        length += len(s) + 1
    if current:
        chunks.append(current)
    return flat, chunks


def chunk_to_text(flat: List[str], indices: List[int]) -> str:
    return " ".join(flat[i] for i in indices)
