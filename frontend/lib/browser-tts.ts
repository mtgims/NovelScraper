// In-browser Kokoro TTS via kokoro-js + WebGPU. This runs synthesis on the
// visitor's own GPU (transformers.js under the hood), so a deployed server does
// no synthesis work — it only serves the text manifest. Falls back to the
// server engine (see tts-player) when WebGPU isn't available.
//
// The model downloads once and is cached by the browser (Cache Storage, managed
// by transformers.js), so only the first Listen pays for it. The precision
// (dtype) is chosen from the GPU's capabilities — see pickDtype.

import type { KokoroTTS } from "kokoro-js";

const MODEL_ID = "onnx-community/Kokoro-82M-v1.0-ONNX";

// GPU model precision. fp16 (~163MB) runs on the GPU but needs the adapter's
// `shader-f16` feature. Without it there is no good on-device option:
//   - fp32 (~326MB) *can* use the GPU, but is a heavy download and, in practice,
//     ONNX Runtime Web's WebGPU backend still falls back to CPU on many mobile
//     GPU drivers (tested: a hardware-accelerated Mali phone ran fp32 on the CPU
//     and nearly froze — full precision on CPU is the worst case).
//   - q8 (~90MB, int8) downloads small but the WebGPU backend can't accelerate
//     int8, so it runs on the CPU too.
// So on f16-less phones on-device is effectively CPU-bound either way; we keep q8
// as the lighter fallback and steer such devices to the server engine instead.
type Dtype = "fp16" | "q8";

interface GpuAdapterLike {
  features: { has(name: string): boolean };
}
interface GpuLike {
  requestAdapter(): Promise<GpuAdapterLike | null>;
}

function gpu(): GpuLike | null {
  if (typeof navigator === "undefined" || !("gpu" in navigator)) return null;
  return (navigator as unknown as { gpu: GpuLike }).gpu;
}

// Request the WebGPU adapter once and reuse it (both the usability check and the
// dtype choice need it; requesting twice is wasteful).
let adapterPromise: Promise<GpuAdapterLike | null> | null = null;
function getAdapter(): Promise<GpuAdapterLike | null> {
  if (!adapterPromise) {
    adapterPromise = (async () => {
      const g = gpu();
      if (!g) return null;
      try {
        return await g.requestAdapter();
      } catch {
        return null;
      }
    })();
  }
  return adapterPromise;
}

/**
 * Whether on-device synthesis is actually usable. `navigator.gpu` can exist
 * while `requestAdapter()` returns null (no/blocked GPU — e.g. headless, some
 * VMs, disabled hardware acceleration), in which case synthesis would fail. This
 * async check confirms a real adapter before we route audio to the browser.
 */
export async function browserTtsUsable(): Promise<boolean> {
  return (await getAdapter()) !== null;
}

/**
 * Pick the model precision from the GPU's capabilities. fp16 (GPU) requires the
 * `shader-f16` WebGPU feature; without it we fall back to q8. On such devices
 * on-device synthesis tends to run on the CPU regardless (see the Dtype note),
 * so the server engine is preferred — this just keeps the lighter model.
 */
async function pickDtype(): Promise<Dtype> {
  const adapter = await getAdapter();
  return adapter?.features.has("shader-f16") ? "fp16" : "q8";
}

let enginePromise: Promise<KokoroTTS> | null = null;

// Aggregate download progress across the model's files (0..100).
const fileProgress = new Map<string, { loaded: number; total: number }>();
const progressListeners = new Set<(percent: number) => void>();

function emitProgress() {
  let loaded = 0;
  let total = 0;
  for (const f of fileProgress.values()) {
    loaded += f.loaded;
    total += f.total;
  }
  const pct = total > 0 ? Math.min(100, Math.round((loaded / total) * 100)) : 0;
  progressListeners.forEach((cb) => cb(pct));
}

/** Subscribe to model-download progress. Returns an unsubscribe function. */
export function onModelProgress(cb: (percent: number) => void): () => void {
  progressListeners.add(cb);
  return () => {
    progressListeners.delete(cb);
  };
}

/** Lazily load the model (singleton). First call kicks off the model download
 *  (~163MB fp16, ~90MB q8), cached by the browser thereafter. */
export function loadBrowserTts(): Promise<KokoroTTS> {
  if (!enginePromise) {
    enginePromise = (async () => {
      const { KokoroTTS } = await import("kokoro-js");
      const dtype = await pickDtype();
      const tts = await KokoroTTS.from_pretrained(MODEL_ID, {
        dtype,
        device: "webgpu",
        progress_callback: (p: unknown) => {
          const e = p as { file?: string; loaded?: number; total?: number };
          if (e && e.file && typeof e.total === "number") {
            fileProgress.set(e.file, { loaded: e.loaded ?? e.total, total: e.total });
            emitProgress();
          }
        },
      });
      return tts;
    })();
    // If loading fails, allow a later retry rather than caching the rejection.
    enginePromise.catch(() => {
      enginePromise = null;
    });
  }
  return enginePromise;
}

/** Voice ids supported by the in-browser model (identical set to the server). */
export async function browserVoices(): Promise<string[]> {
  const tts = await loadBrowserTts();
  return Object.keys(tts.voices);
}

// Synthesis is serialized: there is a single WebGPU model instance, so letting
// a prefetch call overlap the currently-playing chunk's synthesis would contend
// on it. Chaining keeps them strictly one-at-a-time.
let synthChain: Promise<unknown> = Promise.resolve();

/** Synthesize `text` to a WAV Blob on the user's GPU. */
export function synthesizeBlob(
  text: string,
  voice: string,
  speed: number
): Promise<Blob> {
  const run = synthChain.then(async () => {
    const tts = await loadBrowserTts();
    const audio = await tts.generate(text, {
      voice: voice as NonNullable<Parameters<KokoroTTS["generate"]>[1]>["voice"],
      speed,
    });
    return audio.toBlob();
  });
  synthChain = run.catch(() => {});
  return run;
}
