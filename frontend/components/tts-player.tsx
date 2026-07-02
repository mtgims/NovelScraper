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
import { cn } from "@/lib/utils";

type Mode = "idle" | "loading" | "playing" | "paused";
type Engine = "browser" | "server";

const DEFAULT_VOICE = "af_heart";
// Fallback narration rate (seconds of audio per character, at 1× speed) used to
// estimate a chapter's total duration before any chunk has actually loaded. Once
// real chunk durations are known, they replace this estimate.
const DEFAULT_SEC_PER_CHAR = 0.06;

function formatTime(sec: number): string {
  if (!isFinite(sec) || sec < 0) sec = 0;
  const s = Math.floor(sec % 60);
  const m = Math.floor(sec / 60) % 60;
  const h = Math.floor(sec / 3600);
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return (h > 0 ? `${h}:` : "") + `${mm}:${String(s).padStart(2, "0")}`;
}

/** Imperative handle so the reader can jump narration to a clicked sentence. */
export type TtsPlayerHandle = {
  seekToSentence: (globalSentenceIndex: number) => void;
};

type Props = {
  bookId: number;
  position: number;
  onSegments: (paragraphs: string[][] | null) => void;
  onHighlight: (globalSentenceIndex: number | null) => void;
  onComplete?: () => void;
  // Current reading position as a 0..1 fraction of the chapter, so narration can
  // start where the reader is rather than at the top.
  getReadingFraction?: () => number;
};

export const TtsPlayer = forwardRef<TtsPlayerHandle, Props>(function TtsPlayer(
  { bookId, position, onSegments, onHighlight, onComplete, getReadingFraction },
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
  const pendingFrac = useRef<number | null>(null); // intra-chunk seek target
  const lastHi = useRef<number | null>(null);
  const playRef = useRef<() => void>(() => {});
  const finishRef = useRef<() => void>(() => {});

  const serverAvailable = !!info?.available;
  voiceRef.current = voice || info?.default || DEFAULT_VOICE;
  speedRef.current = speed;
  engineRef.current = engine ?? "server";
  serverAvailableRef.current = serverAvailable;

  useEffect(() => {
    let cancelled = false;
    // Only surface on-device as an option if a real WebGPU adapter exists.
    browserTtsUsable().then((usable) => {
      if (cancelled) return;
      setBrowserSupported(usable);
      setEngine((prev) => prev ?? (usable ? "browser" : "server"));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => onModelProgress((p) => setModelPct(p)), []);

  const revokeBrowserCache = useCallback(() => {
    for (const u of browserCache.current.values()) URL.revokeObjectURL(u);
    browserCache.current.clear();
  }, []);

  const chunkText = useCallback(
    (i: number) => chunksRef.current[i].map((k) => flatRef.current[k]).join(" "),
    []
  );

  const srcForChunk = useCallback(
    async (i: number): Promise<string> => {
      if (engineRef.current === "server") {
        return audioChunkUrl(bookId, position, i, voiceRef.current, speedRef.current);
      }
      const cached = browserCache.current.get(i);
      if (cached) return cached;
      const blob = await synthesizeBlob(chunkText(i), voiceRef.current, speedRef.current);
      const objUrl = URL.createObjectURL(blob);
      browserCache.current.set(i, objUrl);
      return objUrl;
    },
    [bookId, position, chunkText]
  );

  const prefetch = useCallback(
    (i: number) => {
      if (i < 0 || i >= chunksRef.current.length) return;
      if (engineRef.current === "server") {
        fetch(
          audioChunkUrl(bookId, position, i, voiceRef.current, speedRef.current)
        ).catch(() => {});
      } else if (!browserCache.current.has(i)) {
        srcForChunk(i).catch(() => {});
      }
    },
    [bookId, position, srcForChunk]
  );

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
    prefetch(chunkIdx.current + 1);
  }, [srcForChunk, prefetch]);

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

  const start = useCallback(async () => {
    setMode("loading");
    try {
      const m = await api.ttsManifest(bookId, position, voiceRef.current, speedRef.current);
      chunksRef.current = m.chunks;
      flatRef.current = m.paragraphs.flat();
      lensRef.current = flatRef.current.map((s) => s.length + 1);
      chunkCharsRef.current = m.chunks.map((ch) =>
        ch.reduce((sum, k) => sum + (lensRef.current[k] || 1), 0)
      );
      onSegments(m.paragraphs);
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
    <div className="fixed inset-x-0 bottom-5 z-50 flex justify-center px-4 print:hidden">
      {!active ? (
        <button
          type="button"
          onClick={start}
          className="flex items-center gap-2 rounded-full border border-border bg-card/95 px-4 py-2.5 text-sm font-medium text-foreground shadow-lg backdrop-blur transition-colors hover:border-foreground/30 hover:bg-muted"
        >
          <Headphones size={16} className="text-accent" /> Listen
        </button>
      ) : (
        <div className="relative w-full max-w-md">
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
                    reload();
                  }}
                >
                  {voiceOptions.length === 0 ? (
                    <option value={voice || DEFAULT_VOICE}>{voice || DEFAULT_VOICE}</option>
                  ) : (
                    voiceOptions.map((v) => (
                      <option key={v} value={v}>
                        {v}
                      </option>
                    ))
                  )}
                </select>
              </label>
              <label className="flex items-center gap-2">
                <span className="kicker w-14">Speed</span>
                <select
                  aria-label="Speed"
                  className="h-8 flex-1 rounded-sm border border-border bg-background px-2 text-xs tabular"
                  value={speed}
                  onChange={(e) => {
                    const s = Number(e.target.value);
                    setSpeed(s);
                    speedRef.current = s;
                    reload();
                  }}
                >
                  {[0.75, 1, 1.25, 1.5, 1.75, 2].map((s) => (
                    <option key={s} value={s}>
                      {s}×
                    </option>
                  ))}
                </select>
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
