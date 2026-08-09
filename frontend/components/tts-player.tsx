"use client";

import {
  Cpu,
  Headphones,
  MonitorSmartphone,
  Pause,
  Play,
  RotateCcw,
  RotateCw,
  Settings2,
  X,
} from "lucide-react";
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from "react";

import { api, audioChunkUrl } from "@/lib/api";
import {
  browserTtsUsable,
  browserVoices,
  onModelProgress,
  synthesizeBlob,
} from "@/lib/browser-tts";
import { useVoices } from "@/lib/queries";
import { groupVoices } from "@/lib/voices";
import type { TtsBlock } from "@/lib/types";
import { cn } from "@/lib/utils";

type Mode = "idle" | "loading" | "playing" | "paused";
type Engine = "browser" | "server";

const DEFAULT_VOICE = "af_heart";
// Fallback narration rate (seconds of audio per character, at 1× speed) used to
// estimate a chapter's total duration before any chunk has actually loaded. Once
// real chunk durations are known, they replace this estimate.
const DEFAULT_SEC_PER_CHAR = 0.06;

// How many upcoming chunks to keep fetched ahead of the playhead — engine-specific.
//
// Server engine: a generous buffer so narration keeps going with the screen off /
// tab backgrounded, where the browser throttles new *network* requests. These
// chunks are just downloads, so buffering many is cheap.
//
// On-device engine: each chunk is a neural-model synthesis on THIS device's GPU.
// Prefetching many would queue a pile of GPU work and pin (overheat / freeze) a
// phone — so keep just the next one ready. Synthesis overlaps playback of the
// current chunk, which is enough for gapless audio on a capable device.
const SERVER_PREFETCH_AHEAD = 12;
const DEVICE_PREFETCH_AHEAD = 1;

// A server chunk can transiently 5xx mid-chapter (e.g. a GPU hiccup while the
// backend rebuilds its model). Retry a few times so one failed chunk doesn't end
// playback; 4xx (e.g. out-of-range) fails fast since retrying won't help.
const SERVER_CHUNK_ATTEMPTS = 3;

function formatTime(sec: number): string {
  if (!isFinite(sec) || sec < 0) sec = 0;
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60) % 60;
  const h = Math.floor(sec / 3600);
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return (h > 0 ? `${h}:` : "") + `${mm}:${String(s).padStart(2, "0")}`;
}

/** Coarse pointer ≈ phone/tablet. Even where WebGPU works, we don't DEFAULT to
 *  on-device synthesis on these: running the neural model on a mobile GPU can
 *  overheat or freeze the device. On-device stays available as an explicit
 *  opt-in via the engine toggle. */
function isTouchDevice(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(pointer: coarse)").matches
  );
}

/** Imperative handle so the reader can jump narration to a clicked sentence. */
export type TtsPlayerHandle = {
  seekToSentence: (globalSentenceIndex: number) => void;
};

type Props = {
  bookId: number;
  position: number;
  onSegments: (blocks: TtsBlock[] | null) => void;
  onHighlight: (globalSentenceIndex: number | null) => void;
  onComplete?: () => void;
  // Current reading position as a 0..1 fraction of the chapter, so narration can
  // start where the reader is rather than at the top.
  getReadingFraction?: () => number;
  // Whether a next chapter exists, and how to move to it. When "auto-advance" is
  // on, finishing the chapter calls onNextChapter so narration rolls into it.
  hasNext?: boolean;
  onNextChapter?: () => void;
  // Begin narrating automatically on mount (set when we arrived here via
  // auto-advance from the previous chapter).
  autoStart?: boolean;
  // Metadata for the OS media session (lock screen / notification controls).
  mediaTitle?: string;
  mediaSubtitle?: string;
  mediaArtwork?: string;
};

export const TtsPlayer = forwardRef<TtsPlayerHandle, Props>(function TtsPlayer(
  { bookId, position, onSegments, onHighlight, onComplete, getReadingFraction,
    hasNext, onNextChapter, autoStart,
    mediaTitle, mediaSubtitle, mediaArtwork },
  ref
) {
  const { data: info } = useVoices();
  const [mode, setMode] = useState<Mode>("idle");
  const [elapsedSec, setElapsedSec] = useState(0);
  const [totalSec, setTotalSec] = useState(0);
  const [totalExact, setTotalExact] = useState(false); // false while total is still estimated
  const [voice, setVoice] = useState("");
  const [speed, setSpeed] = useState(1);
  const [modelPct, setModelPct] = useState<number | null>(null);
  const [showSettings, setShowSettings] = useState(false);
  const [autoNext, setAutoNext] = useState(false);

  const [browserSupported, setBrowserSupported] = useState(false);
  const [engine, setEngine] = useState<Engine | null>(null);
  const [browserVoiceList, setBrowserVoiceList] = useState<string[]>([]);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const chunkIdx = useRef(0);
  const chunksRef = useRef<number[][]>([]); // chunk -> [flat sentence indices]
  const flatRef = useRef<string[]>([]); // flattened sentence texts
  const lensRef = useRef<number[]>([]); // char length (+1) per flat sentence
  const chunkCharsRef = useRef<number[]>([]); // total chars per chunk (for time estimate)
  const chunkDurRef = useRef<Map<number, number>>(new Map()); // measured seconds per chunk
  const voiceRef = useRef("");
  const speedRef = useRef(1);
  const engineRef = useRef<Engine>("server");
  const serverAvailableRef = useRef(false);
  const browserCache = useRef<Map<number, string>>(new Map()); // chunk -> object URL
  const inFlightRef = useRef<Map<number, Promise<string>>>(new Map()); // dedupe concurrent fetches
  const pendingFrac = useRef<number | null>(null); // intra-chunk seek target
  const lastHi = useRef<number | null>(null);
  const playRef = useRef<() => void>(() => {});
  const finishRef = useRef<() => void>(() => {});
  const autoStartedRef = useRef(false); // guard: auto-start narration at most once

  const serverAvailable = !!info?.available;
  voiceRef.current = voice || info?.default || DEFAULT_VOICE;
  speedRef.current = speed;
  engineRef.current = engine ?? "server";
  serverAvailableRef.current = serverAvailable;

  useEffect(() => {
    if (info === undefined) return; // wait until server availability is known
    let cancelled = false;
    // Only surface on-device as an option if a real WebGPU adapter exists.
    browserTtsUsable().then((usable) => {
      if (cancelled) return;
      setBrowserSupported(usable);
      setEngine((prev) => {
        if (prev) return prev;
        if (!usable) return "server"; // no WebGPU → server (normal case)
        if (!info.available) return "browser"; // on-device is the only option
        // Both work: default to on-device on desktop (offloads the server), but
        // to the server on phones/tablets — synthesizing on a mobile GPU can
        // overheat/freeze the device. On-device stays available via the toggle.
        return isTouchDevice() ? "server" : "browser";
      });
    });
    return () => {
      cancelled = true;
    };
  }, [info]);

  useEffect(() => onModelProgress((p) => setModelPct(p)), []);

  // Remember the voice per book: entering a novel restores the voice you last
  // used for it (localStorage, device-local). Auto-advance is a single global
  // preference. Voice is keyed on bookId so switching novels swaps it.
  useEffect(() => {
    try {
      const v = localStorage.getItem(`ns:tts-voice:${bookId}`);
      if (v) {
        setVoice(v);
        voiceRef.current = v;
      }
      setAutoNext(localStorage.getItem("ns:tts-auto-next") === "1");
    } catch {
      /* ignore */
    }
  }, [bookId]);

  const revokeBrowserCache = useCallback(() => {
    for (const u of browserCache.current.values()) URL.revokeObjectURL(u);
    browserCache.current.clear();
    inFlightRef.current.clear();
  }, []);

  const chunkText = useCallback(
    (i: number) => chunksRef.current[i].map((k) => flatRef.current[k]).join(" "),
    []
  );

  // Resolve a chunk to a playable object URL, fetching (server) or synthesizing
  // (on-device) its audio into memory and caching it. Cached blobs play without
  // any network, which is what keeps playback alive when backgrounded.
  // Concurrent requests for the same chunk share one fetch.
  const srcForChunk = useCallback(
    async (i: number): Promise<string> => {
      const cached = browserCache.current.get(i);
      if (cached) return cached;
      const inflight = inFlightRef.current.get(i);
      if (inflight) return inflight;
      const promise = (async () => {
        let blob: Blob;
        if (engineRef.current === "server") {
          blob = await (async (): Promise<Blob> => {
            let lastErr: unknown;
            for (let attempt = 0; attempt < SERVER_CHUNK_ATTEMPTS; attempt++) {
              if (attempt > 0) await new Promise((r) => setTimeout(r, 500 * attempt));
              try {
                const res = await fetch(
                  audioChunkUrl(bookId, position, i, voiceRef.current, speedRef.current)
                );
                if (res.ok) return await res.blob();
                lastErr = new Error(`chunk ${i} failed: ${res.status}`);
                if (res.status < 500) break; // client error — retrying won't help
              } catch (e) {
                lastErr = e; // network error — retry
              }
            }
            throw lastErr ?? new Error(`chunk ${i} failed`);
          })();
        } else {
          blob = await synthesizeBlob(chunkText(i), voiceRef.current, speedRef.current);
        }
        const objUrl = URL.createObjectURL(blob);
        browserCache.current.set(i, objUrl);
        return objUrl;
      })();
      inFlightRef.current.set(i, promise);
      try {
        return await promise;
      } finally {
        inFlightRef.current.delete(i);
      }
    },
    [bookId, position, chunkText]
  );

  const prefetch = useCallback(
    (i: number) => {
      if (i < 0 || i >= chunksRef.current.length) return;
      if (browserCache.current.has(i) || inFlightRef.current.has(i)) return;
      srcForChunk(i).catch(() => {});
    },
    [srcForChunk]
  );

  // Keep a window of upcoming chunks cached ahead of the playhead, and free
  // blobs left well behind it so memory stays bounded.
  const prefetchWindow = useCallback(() => {
    const cur = chunkIdx.current;
    const len = chunksRef.current.length;
    const ahead =
      engineRef.current === "server" ? SERVER_PREFETCH_AHEAD : DEVICE_PREFETCH_AHEAD;
    for (let i = cur + 1; i <= cur + ahead && i < len; i++) prefetch(i);
    for (const [i, url] of browserCache.current) {
      if (i < cur - 1) {
        URL.revokeObjectURL(url);
        browserCache.current.delete(i);
      }
    }
  }, [prefetch]);

  const play = useCallback(async () => {
    const a = audioRef.current;
    if (!a) return;
    let src: string;
    try {
      src = await srcForChunk(chunkIdx.current);
    } catch {
      // On-device synthesis can fail at runtime (WebGPU adapter lost, OOM).
      // Fall back to the server engine when it's available rather than dying.
      if (engineRef.current === "browser" && serverAvailableRef.current) {
        engineRef.current = "server";
        setEngine("server");
        try {
          src = await srcForChunk(chunkIdx.current);
        } catch {
          setMode("idle");
          return;
        }
      } else {
        setMode("idle");
        return;
      }
    }
    if (audioRef.current !== a) return; // stopped/unmounted while synthesizing
    a.src = src;
    const frac = pendingFrac.current;
    pendingFrac.current = null;
    const startPlayback = () => {
      a.play()
        .then(() => setMode("playing"))
        .catch(() => setMode("idle"));
    };
    if (frac && frac > 0) {
      const onMeta = () => {
        a.removeEventListener("loadedmetadata", onMeta);
        if (isFinite(a.duration)) {
          a.currentTime = Math.min(a.duration - 0.05, frac * a.duration);
        }
        startPlayback();
      };
      a.addEventListener("loadedmetadata", onMeta);
    } else {
      startPlayback();
    }
    prefetchWindow();
  }, [srcForChunk, prefetchWindow]);

  const stop = useCallback(() => {
    const a = audioRef.current;
    if (a) {
      a.pause();
      a.removeAttribute("src");
    }
    chunkIdx.current = 0;
    pendingFrac.current = null;
    revokeBrowserCache();
    chunkDurRef.current.clear();
    setMode("idle");
    setElapsedSec(0);
    setTotalSec(0);
    setTotalExact(false);
    setShowSettings(false);
    onSegments(null);
    onHighlight(null);
  }, [onSegments, onHighlight, revokeBrowserCache]);

  // Jump to a specific chunk (used by rewind/forward).
  const goChunk = useCallback(
    (index: number) => {
      const len = chunksRef.current.length;
      if (!len) return;
      const next = Math.min(len - 1, Math.max(0, index));
      chunkIdx.current = next;
      pendingFrac.current = null;
      void play();
    },
    [play]
  );

  // Point narration at a global sentence index: set the chunk + intra-chunk
  // offset (without starting playback). Returns false if the index isn't found.
  const locateSentence = useCallback((globalIndex: number): boolean => {
    const chunks = chunksRef.current;
    for (let i = 0; i < chunks.length; i++) {
      if (chunks[i].includes(globalIndex)) {
        const ch = chunks[i];
        const totalLen = ch.reduce((s, k) => s + (lensRef.current[k] || 1), 0);
        let before = 0;
        for (const k of ch) {
          if (k === globalIndex) break;
          before += lensRef.current[k] || 1;
        }
        pendingFrac.current = totalLen > 0 ? before / totalLen : 0;
        chunkIdx.current = i;
        return true;
      }
    }
    return false;
  }, []);

  // Jump narration to a clicked sentence.
  const seekToSentence = useCallback(
    (globalIndex: number) => {
      if (locateSentence(globalIndex)) void play();
    },
    [locateSentence, play]
  );

  useImperativeHandle(ref, () => ({ seekToSentence }), [seekToSentence]);

  playRef.current = () => {
    void play();
  };
  finishRef.current = () => {
    setMode("idle");
    setElapsedSec(totalSec);
    onHighlight(null);
    onComplete?.();
    // Reassigned every render, so `autoNext`/`hasNext` here are always current.
    if (autoNext && hasNext) onNextChapter?.();
  };

  // Audio element + handlers created once.
  useEffect(() => {
    const a = new Audio();
    a.ontimeupdate = () => {
      const nc = chunksRef.current.length;
      if (a.duration && isFinite(a.duration) && nc) {
        // Record this chunk's true duration, then estimate elapsed/total across
        // the whole chapter (measured chunks are exact; the rest are estimated
        // from character count at the observed narration rate).
        chunkDurRef.current.set(chunkIdx.current, a.duration);
        let knownDur = 0;
        let knownChars = 0;
        chunkDurRef.current.forEach((d, i) => {
          knownDur += d;
          knownChars += chunkCharsRef.current[i] || 0;
        });
        const rate =
          knownChars > 0
            ? knownDur / knownChars
            : DEFAULT_SEC_PER_CHAR / (speedRef.current || 1);
        const durOf = (i: number) =>
          chunkDurRef.current.get(i) ?? (chunkCharsRef.current[i] || 0) * rate;
        let elapsed = 0;
        for (let i = 0; i < chunkIdx.current; i++) elapsed += durOf(i);
        elapsed += a.currentTime || 0;
        let tot = 0;
        for (let i = 0; i < nc; i++) tot += durOf(i);
        setElapsedSec(elapsed);
        setTotalSec(Math.max(tot, elapsed));
        // Total is exact only once every chunk's real duration has been measured.
        setTotalExact(chunkDurRef.current.size >= nc);
      }
      const ch = chunksRef.current[chunkIdx.current];
      if (!ch || !a.duration) return;
      const lens = ch.map((i) => lensRef.current[i] || 1);
      const totalLen = lens.reduce((s, x) => s + x, 0);
      const target = (a.currentTime / a.duration) * totalLen;
      let cum = 0;
      let hi = ch[ch.length - 1];
      for (let j = 0; j < ch.length; j++) {
        cum += lens[j];
        if (target <= cum) {
          hi = ch[j];
          break;
        }
      }
      if (hi !== lastHi.current) {
        lastHi.current = hi;
        onHighlight(hi);
      }
    };
    a.onended = () => {
      const next = chunkIdx.current + 1;
      if (next < chunksRef.current.length) {
        chunkIdx.current = next;
        playRef.current();
      } else {
        finishRef.current();
      }
    };
    a.onerror = () => setMode("idle");
    audioRef.current = a;
    return () => {
      a.pause();
      a.removeAttribute("src");
      audioRef.current = null;
    };
  }, [onHighlight]);

  // Stop when the chapter changes.
  useEffect(() => stop, [position, stop]);

  // --- OS media session: lock screen / notification controls + background ---
  // Actions are read through a ref so the (once-registered) handlers always call
  // the latest state without re-binding.
  const mediaActionsRef = useRef<Record<string, () => void>>({});
  mediaActionsRef.current = {
    play: () => {
      const a = audioRef.current;
      if (a && mode !== "playing") a.play().then(() => setMode("playing")).catch(() => {});
    },
    pause: () => {
      const a = audioRef.current;
      if (a && mode === "playing") {
        a.pause();
        setMode("paused");
      }
    },
    prev: () => goChunk(chunkIdx.current - 1),
    next: () => goChunk(chunkIdx.current + 1),
  };

  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const bind = (action: MediaSessionAction, key: string) => {
      try {
        ms.setActionHandler(action, () => mediaActionsRef.current[key]?.());
      } catch {
        /* action unsupported */
      }
    };
    bind("play", "play");
    bind("pause", "pause");
    bind("previoustrack", "prev");
    bind("nexttrack", "next");
    bind("seekbackward", "prev");
    bind("seekforward", "next");
    return () => {
      (
        ["play", "pause", "previoustrack", "nexttrack", "seekbackward", "seekforward"] as MediaSessionAction[]
      ).forEach((a) => {
        try {
          ms.setActionHandler(a, null);
        } catch {
          /* ignore */
        }
      });
    };
  }, []);

  // Metadata + playback state (drives what the lock screen shows).
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const active = mode !== "idle";
    if (active) {
      try {
        ms.metadata = new MediaMetadata({
          title: mediaTitle || "Narration",
          artist: mediaSubtitle || "NovelScraper",
          album: mediaSubtitle || "NovelScraper",
          artwork: mediaArtwork
            ? [
                { src: mediaArtwork, sizes: "256x384", type: "image/jpeg" },
                { src: mediaArtwork, sizes: "512x768", type: "image/jpeg" },
              ]
            : [],
        });
      } catch {
        /* MediaMetadata unsupported */
      }
    } else {
      ms.metadata = null;
    }
    ms.playbackState = mode === "playing" ? "playing" : mode === "paused" ? "paused" : "none";
  }, [mode, mediaTitle, mediaSubtitle, mediaArtwork]);

  // Progress bar on the lock screen (from the estimated elapsed/total).
  useEffect(() => {
    if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    if (mode === "idle" || totalSec <= 0 || typeof ms.setPositionState !== "function") return;
    try {
      ms.setPositionState({
        duration: Math.max(totalSec, elapsedSec, 0.001),
        position: Math.max(0, Math.min(elapsedSec, totalSec)),
        playbackRate: speed || 1,
      });
    } catch {
      /* setPositionState unsupported */
    }
  }, [mode, elapsedSec, totalSec, speed]);

  const start = useCallback(async () => {
    setMode("loading");
    try {
      const m = await api.ttsManifest(bookId, position, voiceRef.current, speedRef.current);
      chunksRef.current = m.chunks;
      // Sentence stream = text blocks flattened in order (images carry no audio),
      // which matches the server's chunk indices exactly.
      flatRef.current = m.blocks.flatMap((b) => (b.type === "text" ? b.sentences : []));
      lensRef.current = flatRef.current.map((s) => s.length + 1);
      chunkCharsRef.current = m.chunks.map((ch) =>
        ch.reduce((sum, k) => sum + (lensRef.current[k] || 1), 0)
      );
      onSegments(m.blocks);
      // Begin narration from roughly where the reader is (mapping the scroll
      // fraction to a sentence), falling back to the start of the chapter.
      const total = flatRef.current.length;
      const frac = Math.max(0, Math.min(1, getReadingFraction?.() ?? 0));
      const target = total > 0 ? Math.min(total - 1, Math.round(frac * total)) : 0;
      if (!(frac > 0.001) || !locateSentence(target)) {
        chunkIdx.current = 0;
        pendingFrac.current = null;
      }
      revokeBrowserCache();
      chunkDurRef.current.clear();
      setElapsedSec(0);
      setTotalSec(0);
      setTotalExact(false);
      await play();
      if (engineRef.current === "browser") {
        browserVoices().then(setBrowserVoiceList).catch(() => {});
      }
    } catch {
      setMode("idle");
    }
  }, [bookId, position, play, onSegments, revokeBrowserCache, locateSentence, getReadingFraction]);

  // Each chapter gets one shot at auto-starting.
  useEffect(() => {
    autoStartedRef.current = false;
  }, [position]);

  // Roll narration straight into the chapter when we arrived via auto-advance,
  // once the engine is ready. Runs at most once per chapter.
  useEffect(() => {
    if (!autoStart || autoStartedRef.current) return;
    if (info === undefined || engine === null) return;
    if (!serverAvailable && !browserSupported) return;
    if (mode !== "idle") return;
    autoStartedRef.current = true;
    void start();
  }, [autoStart, info, engine, serverAvailable, browserSupported, mode, start]);

  if (info === undefined) return null;
  if (!serverAvailable && !browserSupported) return null;

  const active = mode !== "idle";
  const bothAvailable = serverAvailable && browserSupported;
  const voiceOptions =
    info?.voices && info.voices.length ? info.voices : browserVoiceList;

  const reload = () => {
    if (mode === "playing" || mode === "paused") {
      revokeBrowserCache();
      chunkDurRef.current.clear(); // speed changes chunk durations
      void play();
    }
  };

  const switchEngine = (e: Engine) => {
    if (e === engine) return;
    stop();
    setEngine(e);
    engineRef.current = e;
  };

  const togglePlay = () => {
    const a = audioRef.current;
    if (!a) return;
    if (mode === "playing") {
      a.pause();
      setMode("paused");
    } else {
      a.play().then(() => setMode("playing")).catch(() => {});
    }
  };

  return (
    // The container spans the full width to center the pill, but must NOT eat
    // clicks across the bottom of the screen (it would block e.g. the sidebar's
    // theme buttons). Only the actual controls get pointer events back.
    <div className="pointer-events-none fixed inset-x-0 bottom-5 z-50 flex justify-center px-4 print:hidden">
      {!active ? (
        <button
          type="button"
          onClick={start}
          className="pointer-events-auto flex items-center gap-2 rounded-full border border-border bg-card/95 px-4 py-2.5 text-sm font-medium text-foreground shadow-lg backdrop-blur transition-colors hover:border-foreground/30 hover:bg-muted"
        >
          <Headphones size={16} className="text-accent" /> Listen
        </button>
      ) : (
        <div className="pointer-events-auto relative w-full max-w-md">
          {showSettings && (
            <div className="absolute bottom-full right-0 mb-2 flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-xl">
              {bothAvailable && (
                <div className="flex items-center gap-2">
                  <span className="kicker w-14">Engine</span>
                  <div className="flex overflow-hidden rounded-sm border border-border">
                    <button
                      type="button"
                      onClick={() => switchEngine("browser")}
                      title="Synthesize on this device's GPU (WebGPU)"
                      className={cn(
                        "flex items-center gap-1 px-2 py-1 text-xs",
                        engineRef.current === "browser"
                          ? "bg-foreground text-background"
                          : "bg-background text-muted-foreground"
                      )}
                    >
                      <MonitorSmartphone size={13} /> On-device
                    </button>
                    <button
                      type="button"
                      onClick={() => switchEngine("server")}
                      title={`Synthesize on the server${info?.device ? ` (${info.device})` : ""}`}
                      className={cn(
                        "flex items-center gap-1 px-2 py-1 text-xs",
                        engineRef.current === "server"
                          ? "bg-foreground text-background"
                          : "bg-background text-muted-foreground"
                      )}
                    >
                      <Cpu size={13} /> Server
                    </button>
                  </div>
                </div>
              )}
              <label className="flex items-center gap-2">
                <span className="kicker w-14">Voice</span>
                <select
                  aria-label="Voice"
                  className="h-8 flex-1 rounded-sm border border-border bg-background px-2 text-xs"
                  value={voice || info?.default || DEFAULT_VOICE}
                  onChange={(e) => {
                    setVoice(e.target.value);
                    voiceRef.current = e.target.value;
                    try {
                      localStorage.setItem(`ns:tts-voice:${bookId}`, e.target.value);
                    } catch {
                      /* ignore */
                    }
                    reload();
                  }}
                >
                  {voiceOptions.length === 0 ? (
                    <option value={voice || DEFAULT_VOICE}>{voice || DEFAULT_VOICE}</option>
                  ) : (
                    // Grouped by language + gender, with the af_/am_ prefix
                    // dropped in favour of clean names.
                    groupVoices(voiceOptions).map((g) => (
                      <optgroup key={g.label} label={g.label}>
                        {g.voices.map((v) => (
                          <option key={v.id} value={v.id}>
                            {v.name}
                          </option>
                        ))}
                      </optgroup>
                    ))
                  )}
                </select>
              </label>
              <label className="flex items-center gap-2">
                <span className="kicker w-14">Speed</span>
                <input
                  type="range"
                  aria-label="Speed"
                  min={0.5}
                  max={2}
                  step={0.05}
                  value={speed}
                  // Update the readout live while dragging, but only re-synthesize
                  // at the new speed on release (pointer/key up) — re-synthesizing
                  // on every drag tick would thrash the TTS engine.
                  onChange={(e) => {
                    const s = Number(e.target.value);
                    setSpeed(s);
                    speedRef.current = s;
                  }}
                  onPointerUp={reload}
                  onKeyUp={reload}
                  className="h-8 flex-1 cursor-pointer accent-accent"
                />
                <span className="w-9 shrink-0 text-right text-xs tabular">
                  {parseFloat(speed.toFixed(2))}×
                </span>
              </label>
              <label className="flex cursor-pointer items-center gap-2">
                <span className="kicker w-14">Auto-next</span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={autoNext}
                  aria-label="Auto-advance to next chapter"
                  onClick={() => {
                    const next = !autoNext;
                    setAutoNext(next);
                    try {
                      localStorage.setItem("ns:tts-auto-next", next ? "1" : "0");
                    } catch {
                      /* ignore */
                    }
                  }}
                  className={cn(
                    "relative h-5 w-9 rounded-full transition-colors",
                    autoNext ? "bg-accent" : "bg-muted"
                  )}
                >
                  <span
                    className={cn(
                      "absolute top-0.5 h-4 w-4 rounded-full bg-background shadow transition-all",
                      autoNext ? "left-[1.125rem]" : "left-0.5"
                    )}
                  />
                </button>
                <span className="text-xs text-muted-foreground">
                  Continue into next chapter
                </span>
              </label>
            </div>
          )}

          <div className="flex flex-col gap-1.5 rounded-2xl border border-border bg-card/95 px-3 py-2 shadow-xl backdrop-blur">
            <div className="flex items-center gap-1">
              {mode === "loading" ? (
                <span className="px-2 py-1 text-sm text-muted-foreground">
                  {engineRef.current === "browser" && modelPct !== null && modelPct < 100
                    ? `Loading voice… ${modelPct}%`
                    : "Preparing…"}
                </span>
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => goChunk(chunkIdx.current - 1)}
                    aria-label="Rewind"
                    title="Rewind"
                    className="rounded-full p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <RotateCcw size={17} />
                  </button>
                  <button
                    type="button"
                    onClick={togglePlay}
                    aria-label={mode === "playing" ? "Pause" : "Play"}
                    className="rounded-full bg-accent p-2 text-accent-foreground hover:opacity-90"
                  >
                    {mode === "playing" ? <Pause size={18} /> : <Play size={18} />}
                  </button>
                  <button
                    type="button"
                    onClick={() => goChunk(chunkIdx.current + 1)}
                    aria-label="Forward"
                    title="Forward"
                    className="rounded-full p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <RotateCw size={17} />
                  </button>
                  <span className="kicker ml-1 tabular">
                    {formatTime(elapsedSec)} / {totalExact ? "" : "~"}
                    {formatTime(totalSec)}
                  </span>
                </>
              )}

              <div className="ml-auto flex items-center gap-0.5">
                <button
                  type="button"
                  onClick={() => setShowSettings((s) => !s)}
                  aria-label="Playback settings"
                  title="Voice & speed"
                  className={cn(
                    "rounded-full p-1.5 hover:bg-muted",
                    showSettings ? "text-foreground" : "text-muted-foreground"
                  )}
                >
                  <Settings2 size={16} />
                </button>
                <button
                  type="button"
                  onClick={stop}
                  aria-label="Stop narration"
                  title="Stop"
                  className="rounded-full p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            <div className="h-1 overflow-hidden rounded-full bg-muted">
              <div
                className="h-full bg-accent transition-[width] duration-150 ease-out"
                style={{
                  width: `${totalSec > 0 ? Math.min(100, (elapsedSec / totalSec) * 100) : 0}%`,
                }}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
});
