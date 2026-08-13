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

// GPU model precision (on transformers.js v4 = native WebGPU EP). We only load
// on-device when a WebGPU adapter exists. fp16 (~163MB) needs the adapter's
// `shader-f16` feature and runs on the GPU — fast on capable devices. Without
// shader-f16 there is no working GPU option:
//   - fp32 (~326MB) does run on the GPU, but produces CORRUPTED/silent audio on
//     some mobile GPUs (tested: Mali-G720 → no sound, and 326MB nearly OOMs the
//     phone). Not usable there.
//   - so we fall back to q8 (~90MB, int8), which the WebGPU backend can't
//     accelerate → it runs on the CPU: slow but *correct*.
// f16-less devices (many phones) should therefore use the SERVER engine; q8 is
// just a "make some sound" fallback.
type Dtype = "fp16" | "fp32" | "q8";

interface GpuAdapterInfoLike {
  vendor?: string;
  architecture?: string;
  device?: string;
  description?: string;
}
interface GpuAdapterLike {
  features: { has(name: string): boolean };
  info?: GpuAdapterInfoLike;
  requestAdapterInfo?: () => Promise<GpuAdapterInfoLike>;
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

async function getAdapterInfo(adapter: GpuAdapterLike): Promise<GpuAdapterInfoLike> {
  try {
    if (adapter.info) return adapter.info;
    if (adapter.requestAdapterInfo) return await adapter.requestAdapterInfo();
  } catch {
    /* ignore — info is best-effort (some browsers redact it) */
  }
  return {};
}

function isMobile(): boolean {
  if (typeof navigator === "undefined") return false;
  const uaData = (navigator as unknown as { userAgentData?: { mobile?: boolean } }).userAgentData;
  if (uaData && typeof uaData.mobile === "boolean") return uaData.mobile;
  return /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent || "");
}

/**
 * Choose the model precision, preferring a GPU-accelerated path:
 *  - fp16 when the adapter exposes `shader-f16` (GPU, smallest of the GPU paths);
 *  - else fp32, which still runs on the GPU via the WebGPU EP (fp32 is core
 *    WebGPU, not an optional feature Brave/others can strip like shader-f16) and
 *    is correct on desktop GPUs. This is the fix for "no f16 → fell back to q8 on
 *    the CPU → slow";
 *  - q8 (CPU, int8 isn't WebGPU-accelerated) ONLY on mobile GPUs, because some
 *    (Mali, Adreno, PowerVR, Apple mobile) render fp32 WebGPU as corrupted/silent
 *    audio. Correct-but-slow beats broken there.
 * When adapter info is redacted (e.g. Brave), trust fp32 on desktop and stay safe
 * with q8 on mobile.
 */
async function pickDtype(): Promise<Dtype> {
  const adapter = await getAdapter();
  if (!adapter) return "q8";
  if (adapter.features.has("shader-f16")) return "fp16";

  const info = await getAdapterInfo(adapter);
  const desc = `${info.vendor ?? ""} ${info.architecture ?? ""} ${info.device ?? ""} ${info.description ?? ""}`.toLowerCase();
  const mobileBadGpu = /mali|adreno|powervr|imagination|\bimg\b|apple a\d/.test(desc);
  const desktopGpu = /nvidia|geforce|amd|radeon|rdna|intel|arc|iris/.test(desc);
  if (mobileBadGpu) return "q8";
  if (desktopGpu) return "fp32";
  return isMobile() ? "q8" : "fp32"; // unknown vendor (redacted): desktop → fp32
}

function isFirefox(): boolean {
  return typeof navigator !== "undefined" && /firefox/i.test(navigator.userAgent || "");
}

// Set to "wasm" after a WebGPU synthesis attempt fails, so we stop retrying the GPU.
let deviceOverride: "wasm" | null = null;

/**
 * "webgpu" where it can actually run the model, else "wasm" (CPU). Firefox's
 * onnxruntime-web WebGPU EP produces silent/garbage audio for Kokoro, so we go
 * straight to CPU there instead of downloading the big fp32 model only to fail;
 * Chromium (Brave/Chrome/Edge) runs it on the GPU. [deviceOverride] pins CPU
 * after a runtime WebGPU failure on any other browser.
 */
function chosenDevice(): "webgpu" | "wasm" {
  if (deviceOverride) return deviceOverride;
  return isFirefox() ? "wasm" : "webgpu";
}

/** True audio backend in use, for a UI note ("GPU" vs "CPU"). */
export function browserTtsBackend(): "gpu" | "cpu" {
  return chosenDevice() === "webgpu" ? "gpu" : "cpu";
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
 *  (~326MB fp32 / ~163MB fp16 / ~90MB q8), cached by the browser thereafter. */
export function loadBrowserTts(): Promise<KokoroTTS> {
  if (!enginePromise) {
    enginePromise = (async () => {
      const { KokoroTTS } = await import("kokoro-js");
      const device = chosenDevice();
      // CPU (wasm) runs int8 well and downloads far less; the GPU path wants fp32
      // (no shader-f16) or fp16.
      const dtype: Dtype = device === "wasm" ? "q8" : await pickDtype();
      const tts = await KokoroTTS.from_pretrained(MODEL_ID, {
        dtype,
        device,
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

/** The 53 voices of Kokoro v1.0 (the exact set both the in-browser model and the
 *  server expose), in the model's speaker order. Used to seed the picker so every
 *  voice shows — grouped by language — *before* the (slow) model finishes loading
 *  and even when the server engine is unavailable. Kept in sync with the model. */
export const BROWSER_VOICES: string[] = [
  "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
  "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
  "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
  "am_onyx", "am_puck", "am_santa",
  "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
  "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
  "ef_dora", "em_alex", "ff_siwis",
  "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
  "if_sara", "im_nicola",
  "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
  "pf_dora", "pm_alex", "pm_santa",
  "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
  "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
];

/** Voice ids supported by the in-browser model (identical set to the server).
 *  Falls back to the static list if the model's map is unexpectedly empty. */
export async function browserVoices(): Promise<string[]> {
  const tts = await loadBrowserTts();
  const keys = Object.keys(tts.voices);
  return keys.length ? keys : BROWSER_VOICES;
}

// Synthesis is serialized: there is a single WebGPU model instance, so letting
// a prefetch call overlap the currently-playing chunk's synthesis would contend
// on it. Chaining keeps them strictly one-at-a-time.
let synthChain: Promise<unknown> = Promise.resolve();

/** True when the generated buffer is empty or effectively silent — some WebGPU
 *  backends (notably Firefox) return that instead of throwing. */
function isSilent(audio: unknown): boolean {
  const data = (audio as { audio?: Float32Array }).audio;
  if (!data || data.length === 0) return true;
  let max = 0;
  const step = Math.max(1, Math.floor(data.length / 2000));
  for (let i = 0; i < data.length; i += step) {
    const v = Math.abs(data[i]);
    if (v > max) max = v;
  }
  return max < 1e-4;
}

async function synthOnce(text: string, voice: string, speed: number): Promise<Blob> {
  const tts = await loadBrowserTts();
  const audio = await tts.generate(text, {
    voice: voice as NonNullable<Parameters<KokoroTTS["generate"]>[1]>["voice"],
    speed,
  });
  if (isSilent(audio)) throw new Error(`empty audio from ${chosenDevice()} backend`);
  return audio.toBlob();
}

/** Synthesize `text` to a WAV Blob on the user's GPU — falling back to CPU (wasm)
 *  if the WebGPU path throws or returns silence (e.g. Firefox's WebGPU EP). */
export function synthesizeBlob(
  text: string,
  voice: string,
  speed: number
): Promise<Blob> {
  const run = synthChain.then(async () => {
    try {
      return await synthOnce(text, voice, speed);
    } catch (e) {
      if (chosenDevice() === "webgpu") {
        // GPU synthesis failed/was silent — pin CPU, rebuild, and retry so
        // playback works instead of silently dying back to idle.
        deviceOverride = "wasm";
        enginePromise = null;
        return await synthOnce(text, voice, speed);
      }
      throw e;
    }
  });
  synthChain = run.catch(() => {});
  return run;
}
