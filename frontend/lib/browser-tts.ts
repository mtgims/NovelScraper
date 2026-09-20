// In-browser Kokoro TTS (kokoro-js), driven from a Web Worker so synthesis never
// blocks / freezes the page. All model work runs in lib/browser-tts.worker.ts;
// this module is the main-thread client: it decides whether to *offer* on-device
// (a real WebGPU adapter must exist), forwards synth requests to the worker, and
// relays download progress. GPU vs CPU is chosen inside the worker (WebGPU on
// Chromium; CPU/wasm on Firefox and anywhere WebGPU can't run the model).

// --- WebGPU availability (main thread): decides whether on-device is offered ---
interface GpuLike {
  requestAdapter(): Promise<unknown | null>;
}
function gpu(): GpuLike | null {
  if (typeof navigator === "undefined" || !("gpu" in navigator)) return null;
  return (navigator as unknown as { gpu: GpuLike }).gpu;
}
let adapterPromise: Promise<unknown | null> | null = null;
function getAdapter(): Promise<unknown | null> {
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
 * Whether on-device synthesis is offered. Requires a real WebGPU adapter —
 * `navigator.gpu` can exist while `requestAdapter()` returns null (no/blocked
 * GPU). Synthesis itself may still run on CPU inside the worker (Firefox), but we
 * gate the *option* on there being a GPU-capable context to avoid surprising
 * everyone with a slow CPU-only engine.
 */
export async function browserTtsUsable(): Promise<boolean> {
  return (await getAdapter()) !== null;
}

/** The 53 voices of Kokoro v1.0 (the exact set the model/server expose), in the
 *  model's speaker order. Seeds the picker so every voice shows — grouped by
 *  language — before the model loads and even when the server engine is absent. */
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

// --- worker plumbing ------------------------------------------------------

let backend: "gpu" | "cpu" = "gpu";
/** The audio backend the worker last reported ("gpu"/"cpu") — for a UI note. */
export function browserTtsBackend(): "gpu" | "cpu" {
  return backend;
}

const progressListeners = new Set<(percent: number) => void>();
/** Subscribe to model-download progress (0..100). Returns an unsubscribe fn. */
export function onModelProgress(cb: (percent: number) => void): () => void {
  progressListeners.add(cb);
  return () => {
    progressListeners.delete(cb);
  };
}

let worker: Worker | null = null;
let reqId = 0;
const pending = new Map<number, { resolve: (v: unknown) => void; reject: (e: unknown) => void }>();

function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL("./browser-tts.worker.ts", import.meta.url));
    worker.onmessage = (e: MessageEvent) => {
      const m = e.data;
      if (m.type === "progress") {
        progressListeners.forEach((cb) => cb(m.pct));
        return;
      }
      if (m.type === "backend") {
        backend = m.backend;
        return;
      }
      const p = pending.get(m.id);
      if (!p) return;
      pending.delete(m.id);
      if (m.type === "voices") {
        p.resolve((m.voices as string[] | null) ?? BROWSER_VOICES);
      } else if (m.type === "result") {
        if (m.ok) p.resolve(m.blob as Blob);
        else p.reject(new Error(m.error));
      }
    };
    worker.onerror = () => {
      // Worker failed to load/run — reject everything so callers can fall back
      // (e.g. to the server engine) instead of hanging.
      const err = new Error("on-device TTS worker failed");
      for (const [, p] of pending) p.reject(err);
      pending.clear();
    };
  }
  return worker;
}

function call<T>(msg: Record<string, unknown>): Promise<T> {
  const id = ++reqId;
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
    getWorker().postMessage({ ...msg, id });
  });
}

/** Voice ids supported by the in-browser model (falls back to the static list). */
export function browserVoices(): Promise<string[]> {
  return call<string[]>({ type: "voices" });
}

/** Synthesize `text` to a WAV Blob in the worker (GPU on Chromium, CPU elsewhere). */
export function synthesizeBlob(text: string, voice: string, speed: number): Promise<Blob> {
  return call<Blob>({ type: "generate", text, voice, speed });
}
