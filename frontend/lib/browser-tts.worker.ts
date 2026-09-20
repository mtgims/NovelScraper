// Web Worker that runs on-device Kokoro TTS (kokoro-js) off the main thread, so
// synthesis — especially the CPU (wasm) path on browsers whose WebGPU can't run
// the model, e.g. Firefox — never freezes the page. The main-thread client is
// lib/browser-tts.ts; this file is only ever loaded via `new Worker(new URL(...))`.
import type { KokoroTTS } from "kokoro-js";

// Minimal typed handle to the worker global (avoids needing the "webworker" TS lib).
const ctx = self as unknown as {
  postMessage(msg: unknown): void;
  onmessage: ((e: MessageEvent) => void) | null;
};

const MODEL_ID = "onnx-community/Kokoro-82M-v1.0-ONNX";

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

async function getAdapterInfo(adapter: GpuAdapterLike): Promise<GpuAdapterInfoLike> {
  try {
    if (adapter.info) return adapter.info;
    if (adapter.requestAdapterInfo) return await adapter.requestAdapterInfo();
  } catch {
    /* best-effort */
  }
  return {};
}

function isMobile(): boolean {
  const uaData = (navigator as unknown as { userAgentData?: { mobile?: boolean } }).userAgentData;
  if (uaData && typeof uaData.mobile === "boolean") return uaData.mobile;
  return /Mobi|Android|iPhone|iPad|iPod/i.test(navigator.userAgent || "");
}

async function pickDtype(): Promise<Dtype> {
  const adapter = await getAdapter();
  if (!adapter) return "q8";
  if (adapter.features.has("shader-f16")) return "fp16";
  const info = await getAdapterInfo(adapter);
  const desc = `${info.vendor ?? ""} ${info.architecture ?? ""} ${info.device ?? ""} ${info.description ?? ""}`.toLowerCase();
  if (/mali|adreno|powervr|imagination|\bimg\b|apple a\d/.test(desc)) return "q8";
  if (/nvidia|geforce|amd|radeon|rdna|intel|arc|iris/.test(desc)) return "fp32";
  return isMobile() ? "q8" : "fp32";
}

function isFirefox(): boolean {
  return /firefox/i.test(navigator.userAgent || "");
}

// Pinned to "wasm" once a WebGPU synthesis attempt fails at runtime.
let deviceOverride: "wasm" | null = null;
function chosenDevice(): "webgpu" | "wasm" {
  if (deviceOverride) return deviceOverride;
  // Firefox's onnxruntime-web WebGPU EP renders this model as silence, so use CPU
  // there directly instead of downloading the big GPU model only to fail.
  return isFirefox() ? "wasm" : "webgpu";
}

const fileProgress = new Map<string, { loaded: number; total: number }>();
function emitProgress() {
  let loaded = 0;
  let total = 0;
  for (const f of fileProgress.values()) {
    loaded += f.loaded;
    total += f.total;
  }
  const pct = total > 0 ? Math.min(100, Math.round((loaded / total) * 100)) : 0;
  ctx.postMessage({ type: "progress", pct });
}

let enginePromise: Promise<KokoroTTS> | null = null;
function loadEngine(): Promise<KokoroTTS> {
  if (!enginePromise) {
    enginePromise = (async () => {
      const { KokoroTTS } = await import("kokoro-js");
      const device = chosenDevice();
      const dtype: Dtype = device === "wasm" ? "q8" : await pickDtype();
      ctx.postMessage({ type: "backend", backend: device === "webgpu" ? "gpu" : "cpu" });
      return await KokoroTTS.from_pretrained(MODEL_ID, {
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
    })();
    enginePromise.catch(() => {
      enginePromise = null;
    });
  }
  return enginePromise;
}

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
  const tts = await loadEngine();
  const audio = await tts.generate(text, {
    voice: voice as NonNullable<Parameters<KokoroTTS["generate"]>[1]>["voice"],
    speed,
  });
  if (isSilent(audio)) throw new Error(`empty audio from ${chosenDevice()} backend`);
  return audio.toBlob();
}

async function synth(text: string, voice: string, speed: number): Promise<Blob> {
  try {
    return await synthOnce(text, voice, speed);
  } catch (e) {
    if (chosenDevice() === "webgpu") {
      deviceOverride = "wasm";
      enginePromise = null;
      ctx.postMessage({ type: "backend", backend: "cpu" });
      return await synthOnce(text, voice, speed);
    }
    throw e;
  }
}

// Serialize model calls: a single engine instance can't run two generations at once.
let chain: Promise<unknown> = Promise.resolve();

ctx.onmessage = (e: MessageEvent) => {
  const m = e.data as
    | { type: "voices"; id: number }
    | { type: "generate"; id: number; text: string; voice: string; speed: number };

  if (m.type === "voices") {
    chain = chain.then(async () => {
      try {
        const tts = await loadEngine();
        const keys = Object.keys(tts.voices);
        ctx.postMessage({ type: "voices", id: m.id, voices: keys.length ? keys : null });
      } catch (err) {
        ctx.postMessage({ type: "voices", id: m.id, voices: null, error: String(err) });
      }
    });
  } else if (m.type === "generate") {
    chain = chain.then(async () => {
      try {
        const blob = await synth(m.text, m.voice, m.speed);
        ctx.postMessage({ type: "result", id: m.id, ok: true, blob });
      } catch (err) {
        ctx.postMessage({ type: "result", id: m.id, ok: false, error: String(err) });
      }
    });
  }
};
