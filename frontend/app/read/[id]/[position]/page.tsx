"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, LocateFixed, Minus, Plus } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";

import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { TtsPlayer, type TtsPlayerHandle } from "@/components/tts-player";
import { api, API_BASE, coverUrl } from "@/lib/api";
import { useDismiss } from "@/lib/hooks";
import { useBook, useChapter, useChapters, useProgress } from "@/lib/queries";
import type { TtsBlock } from "@/lib/types";
import { cn } from "@/lib/utils";

const SIZE_KEY = "ns-reading-scale";
const FONT_KEY = "ns-reading-font";
const LEADING_KEY = "ns-reading-leading";

// Reader typography options (device-local, persisted). Font stacks reuse the
// app's fonts + system families, so no new webfonts are loaded.
const READER_FONTS: Record<string, { label: string; stack: string }> = {
  serif: { label: "Serif", stack: 'var(--font-serif), Georgia, "Times New Roman", serif' },
  sans: {
    label: "Sans",
    stack:
      'ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  },
  mono: {
    label: "Mono",
    stack: 'var(--font-mono), ui-monospace, "SFMono-Regular", Menlo, monospace',
  },
};
const LEADINGS: { label: string; value: number }[] = [
  { label: "Tight", value: 1.5 },
  { label: "Normal", value: 1.78 },
  { label: "Loose", value: 2.05 },
];

// Per-chapter reading position, stored in localStorage as a 0..1 fraction.
// localStorage is synchronous and device-local, which is exactly right for a
// reading position: it survives every kind of exit (SPA nav, close, reload) with
// no network, no cache races, and each chapter remembers its own spot.
const posKey = (bookId: number, position: number) => `ns:pos:${bookId}:${position}`;

// Resolve a chapter <img> source, or null if it can't be shown:
//  - stored imported illustrations ("/api/books/{id}/images/…") -> absolute
//    backend URL so they load (API_BASE may be a different origin).
//  - already-absolute (http(s)/data) -> keep (e.g. scraped images).
//  - anything else (stray relative EPUB paths we don't store) -> null (drop).
// Shared by prepareImages (normal HTML render) and ReadAlong (narration view).
function resolveImgSrc(src: string): string | null {
  if (src.startsWith("/api/")) return `${API_BASE}${src}`;
  if (/^(https?:|data:)/i.test(src)) return src;
  return null;
}

function prepareImages(html: string): string {
  if (typeof window === "undefined" || !html.includes("<img")) return html;
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.querySelectorAll("img").forEach((img) => {
    const resolved = resolveImgSrc(img.getAttribute("src") || "");
    if (resolved === null) img.remove();
    else img.setAttribute("src", resolved);
  });
  return doc.body.innerHTML;
}

// Resolve once the content is actually laid out — images and web fonts load
// asynchronously and change the page height for a while after first paint, so
// restoring before they finish lands in the wrong place. Capped so a slow/stuck
// image can never block the restore forever.
function waitForContentReady(el: HTMLElement | null, cap = 3000): Promise<void> {
  const imgs = el ? Array.from(el.querySelectorAll("img")) : [];
  const pending = imgs.filter((i) => !i.complete);
  const images =
    pending.length === 0
      ? Promise.resolve()
      : new Promise<void>((resolve) => {
          let left = pending.length;
          const one = () => {
            if (--left <= 0) resolve();
          };
          pending.forEach((i) => {
            i.addEventListener("load", one, { once: true });
            i.addEventListener("error", one, { once: true });
          });
        });
  const fonts =
    typeof document !== "undefined" && "fonts" in document
      ? (document as unknown as { fonts: { ready: Promise<unknown> } }).fonts.ready
      : Promise.resolve();
  const ready = Promise.all([images, fonts]).then(() => undefined);
  const timeout = new Promise<void>((r) => setTimeout(r, cap));
  return Promise.race([ready, timeout]);
}

export default function ReaderPage() {
  const params = useParams<{ id: string; position: string }>();
  const bookId = Number(params.id);
  const position = Number(params.position);
  const router = useRouter();
  const qc = useQueryClient();

  const { data: chapter, isLoading, isError } = useChapter(bookId, position);
  const { data: book } = useBook(bookId);
  const { data: progress } = useProgress(bookId);
  const { data: chapterList } = useChapters(bookId);
  const [clean, setClean] = useState<string | null>(null);
  const [scale, setScale] = useState(1);
  const [font, setFont] = useState("serif");
  const [leading, setLeading] = useState(1.78);
  const [showType, setShowType] = useState(false);
  const typeRef = useRef<HTMLDivElement>(null);
  useDismiss(showType, () => setShowType(false), { refs: [typeRef] });
  // The chapter progress bar is written straight to the DOM from the scroll
  // handler rather than held in state: as state it re-rendered this whole
  // component (and the TTS player) on every animation frame of every scroll.
  const progressBarRef = useRef<HTMLDivElement>(null);
  const setChapterPct = useCallback((frac: number) => {
    const el = progressBarRef.current;
    if (el) el.style.width = `${frac * 100}%`;
  }, []);
  // Set when we land on a chapter via TTS auto-advance, so narration resumes.
  const [autoStartTts, setAutoStartTts] = useState(false);
  // 0..1 progress of the "pull past the end for the next chapter" gesture.
  const [pullNext, setPullNext] = useState(0);
  // Read-along (TTS): blocks (text paragraphs + images) to render + the sentence
  // to highlight.
  const [segments, setSegments] = useState<TtsBlock[] | null>(null);
  const [highlight, setHighlight] = useState<number | null>(null);
  // TTS auto-follow: while narrating, the highlighted sentence is kept centered.
  // The user can scroll away (unfollow) and read freely; a button then jumps
  // back to the narration. `following` drives the button; the ref is read by the
  // (stable) scroll effect and the user-intent handler without stale closures.
  const [following, setFollowing] = useState(true);
  const followingRef = useRef(true);
  const ttsActiveRef = useRef(false);
  const highlightRef = useRef<number | null>(null);
  highlightRef.current = highlight;

  const playerRef = useRef<TtsPlayerHandle>(null);
  const articleRef = useRef<HTMLElement>(null);
  const markedRef = useRef(false);
  const fracRef = useRef(0);            // current reading fraction (for TTS + save)
  const hadSegmentsRef = useRef(false); // previous read-along state (TTS close)
  // The saved position to restore to, captured synchronously per chapter before
  // anything can overwrite it.
  const targetRef = useRef(0);
  // Timestamp of the last real user scroll input (wheel/touch/keys). Only
  // user-caused scrolls are persisted — this excludes the framework's
  // scroll-to-top on navigation, which otherwise overwrote the saved position
  // with 0 on the way out (the actual bug behind "only browser-back works").
  const lastUserIntentRef = useRef(0);
  // Set when the user scrolls themselves — cancels a pending restore so it never
  // fights active reading. A shared ref (not a per-effect flag) so it survives
  // StrictMode's double-invoke and effect re-runs.
  const restoreCancelledRef = useRef(false);

  const scrollToFraction = useCallback((frac: number) => {
    const doc = document.documentElement;
    const max = doc.scrollHeight - doc.clientHeight;
    if (max > 4) window.scrollTo(0, frac * max);
  }, []);

  // --- reading typography (persisted, device-local) ---
  useEffect(() => {
    const saved = Number(localStorage.getItem(SIZE_KEY));
    if (saved) setScale(saved);
    const f = localStorage.getItem(FONT_KEY);
    if (f && READER_FONTS[f]) setFont(f);
    const l = Number(localStorage.getItem(LEADING_KEY));
    if (l) setLeading(l);
  }, []);
  const setSize = useCallback((next: number) => {
    const clamped = Math.min(1.4, Math.max(0.85, Number(next.toFixed(2))));
    setScale(clamped);
    localStorage.setItem(SIZE_KEY, String(clamped));
  }, []);
  const setReaderFont = useCallback((f: string) => {
    setFont(f);
    localStorage.setItem(FONT_KEY, f);
  }, []);
  const setReaderLeading = useCallback((l: number) => {
    setLeading(l);
    localStorage.setItem(LEADING_KEY, String(l));
  }, []);
  // Shared typography for the chapter body (plain render + read-along view).
  // Memoized: a new object each render would change the `style` prop's identity
  // on every scroll frame, re-applying inline styles to the whole chapter body.
  const contentStyle: CSSProperties = useMemo(
    () => ({
      fontSize: `${1.1875 * scale}rem`,
      fontFamily: READER_FONTS[font]?.stack,
      lineHeight: leading,
    }),
    [scale, font, leading]
  );

  // Keep the currently-narrated sentence in view — but only while following.
  useEffect(() => {
    if (highlight == null || !followingRef.current) return;
    document
      .querySelector<HTMLElement>(`[data-si="${highlight}"]`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [highlight]);

  // Track whether TTS is narrating; each new session re-enables following.
  useEffect(() => {
    ttsActiveRef.current = segments !== null;
    if (segments !== null) {
      followingRef.current = true;
      setFollowing(true);
    }
  }, [segments]);

  // Re-follow the narration and jump to the current sentence.
  const followNarration = useCallback(() => {
    followingRef.current = true;
    setFollowing(true);
    const hi = highlightRef.current;
    if (hi != null)
      document
        .querySelector<HTMLElement>(`[data-si="${hi}"]`)
        ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, []);

  // The user starting to scroll themselves means: their position is now real
  // (enable saving, even if the async restore hasn't run yet) and any pending
  // restore should stand down. Mounted independently of content so it's active
  // immediately, before dompurify/restore.
  useEffect(() => {
    const takeOver = () => {
      restoreCancelledRef.current = true;
      lastUserIntentRef.current = Date.now();
      // Scrolling during narration means the user wants to read freely: stop
      // auto-following (which surfaces the "Follow narration" button).
      if (ttsActiveRef.current && followingRef.current) {
        followingRef.current = false;
        setFollowing(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (
        ["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " ", "Spacebar"].includes(
          e.key
        )
      )
        takeOver();
    };
    window.addEventListener("wheel", takeOver, { passive: true });
    window.addEventListener("touchmove", takeOver, { passive: true });
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("wheel", takeOver);
      window.removeEventListener("touchmove", takeOver);
      window.removeEventListener("keydown", onKey);
    };
  }, []);

  // --- sanitize content (client-only); reset per-chapter state ---
  useEffect(() => {
    setClean(null);
    markedRef.current = false;
    restoreCancelledRef.current = false;
    lastUserIntentRef.current = 0;
    fracRef.current = 0;
    // Capture the saved position for THIS chapter now, before any scroll event
    // can overwrite it.
    try {
      targetRef.current = parseFloat(localStorage.getItem(posKey(bookId, position)) || "0") || 0;
    } catch {
      targetRef.current = 0;
    }
    setChapterPct(0);
    if (!chapter?.content) return;
    let alive = true;
    import("dompurify").then((m) => {
      if (alive) setClean(prepareImages(m.default.sanitize(chapter.content)));
    });
    return () => {
      alive = false;
    };
  }, [chapter?.content, bookId, position]);

  const markRead = useCallback(() => {
    if (markedRef.current) return;
    markedRef.current = true;
    api
      .updateProgress(bookId, { mark_read: position })
      .then(() => qc.invalidateQueries({ queryKey: ["progress", bookId] }))
      .catch(() => {});
  }, [bookId, position, qc]);

  // Record which chapter we're on as the book's resume point (for the library /
  // book-page "Continue"). The in-chapter scroll lives in localStorage, not here.
  useEffect(() => {
    if (Number.isFinite(bookId) && Number.isFinite(position)) {
      api.updateProgress(bookId, { last_position: position }).catch(() => {});
    }
  }, [bookId, position]);

  // Restore the saved position for THIS chapter, after layout settles (images +
  // fonts change the page height for a while). Idempotent and cancel-aware, so
  // it's safe to run more than once (StrictMode / clean changes) and never
  // fights a user who has already started scrolling.
  useEffect(() => {
    if (clean === null) return;

    const target = targetRef.current;
    // Nothing to restore: mark short chapters read once laid out (they never
    // reach the scroll-to-bottom mark).
    if (!(target > 0)) {
      waitForContentReady(articleRef.current, 1500).then(() => {
        const doc = document.documentElement;
        if (doc.scrollHeight - doc.clientHeight <= 4) markRead();
      });
      return;
    }

    // Relentlessly re-assert the target for a short window. Deliberately robust
    // to timing: it beats the framework's scroll-to-top on navigation,
    // StrictMode's double-invoke, and late layout (re-applying the *fraction*
    // tracks the page growing as images load). Stops the instant the user
    // scrolls (restoreCancelledRef) so it never fights reading.
    let raf = 0;
    const start = performance.now();
    const tick = () => {
      if (restoreCancelledRef.current) return;
      scrollToFraction(target);
      if (performance.now() - start < 1500) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    // Slow images can finish after the loop window; apply once more when ready.
    let stale = false;
    waitForContentReady(articleRef.current).then(() => {
      if (!stale && !restoreCancelledRef.current) scrollToFraction(target);
    });
    return () => {
      cancelAnimationFrame(raf);
      stale = true;
    };
  }, [clean, markRead, scrollToFraction]);

  // When the TTS player closes, the content swaps from the read-along back to
  // the plain chapter (a different height); re-apply the current fraction so the
  // scroll doesn't jump/clamp.
  useEffect(() => {
    const had = hadSegmentsRef.current;
    hadSegmentsRef.current = segments !== null;
    if (!had || segments !== null) return;
    const frac = fracRef.current;
    scrollToFraction(frac);
    const raf = requestAnimationFrame(() => scrollToFraction(frac));
    return () => cancelAnimationFrame(raf);
  }, [segments, scrollToFraction]);

  // Track scroll: progress bar + save (localStorage) + mark-read at the bottom.
  useEffect(() => {
    let raf = 0;
    const onScroll = () => {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        const el = document.documentElement;
        const max = el.scrollHeight - el.clientHeight;
        const frac = max > 4 ? Math.min(1, el.scrollTop / max) : 0;
        fracRef.current = frac;
        setChapterPct(frac);
        // Persist only scrolls the user actually caused — a scroll event within
        // ~300ms of real wheel/touch/key input. Wheel/trackpad momentum keeps
        // firing wheel events, so this bridges the gap between them without being
        // wide enough to catch the framework's scroll-to-top on navigation
        // (which happens well after you've stopped scrolling and clicked a link).
        if (Date.now() - lastUserIntentRef.current < 300) {
          try {
            localStorage.setItem(posKey(bookId, position), frac.toFixed(4));
          } catch {
            /* ignore */
          }
        }
        if (frac >= 0.98) markRead();
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [bookId, position, markRead, setChapterPct]);

  const go = useCallback(
    (delta: number) => {
      if (delta > 0) markRead(); // moving on = read
      router.push(`/read/${bookId}/${position + delta}`);
    },
    [router, bookId, position, markRead]
  );

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "ArrowLeft" && chapter?.has_prev) go(-1);
      if (e.key === "ArrowRight" && chapter?.has_next) go(1);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [chapter?.has_prev, chapter?.has_next, go]);

  // On entering a chapter, consume the one-shot "auto-start narration" flag left
  // by TTS auto-advance from the previous chapter.
  useEffect(() => {
    let flag = false;
    try {
      if (sessionStorage.getItem("ns:tts-autostart") === "1") {
        sessionStorage.removeItem("ns:tts-autostart");
        flag = true;
      }
    } catch {
      /* ignore */
    }
    setAutoStartTts(flag);
  }, [position]);

  // Mobile: pulling up past the bottom of the chapter (an overscroll harder than
  // an ordinary swipe) advances to the next chapter — like "pull for more".
  useEffect(() => {
    if (!chapter?.has_next) return;
    const THRESH = 110; // px of overscroll drag to trigger
    let lastY = 0;
    let accum = 0;
    let tracking = false;
    const atBottom = () => {
      const d = document.documentElement;
      return d.scrollHeight - d.clientHeight - d.scrollTop <= 2;
    };
    const onStart = (e: TouchEvent) => {
      lastY = e.touches[0].clientY;
      accum = 0;
      tracking = atBottom();
      if (!tracking) setPullNext(0);
    };
    const onMove = (e: TouchEvent) => {
      const y = e.touches[0].clientY;
      if (!tracking) {
        // Only start measuring once the reader has reached the bottom.
        if (atBottom()) tracking = true;
        lastY = y;
        return;
      }
      if (!atBottom()) {
        tracking = false;
        accum = 0;
        setPullNext(0);
        lastY = y;
        return;
      }
      accum = Math.max(0, accum + (lastY - y)); // dragging up = wanting next
      lastY = y;
      setPullNext(Math.min(1, accum / THRESH));
    };
    const onEnd = () => {
      const trigger = tracking && accum >= THRESH;
      tracking = false;
      accum = 0;
      setPullNext(0);
      if (trigger) go(1);
    };
    window.addEventListener("touchstart", onStart, { passive: true });
    window.addEventListener("touchmove", onMove, { passive: true });
    window.addEventListener("touchend", onEnd, { passive: true });
    window.addEventListener("touchcancel", onEnd, { passive: true });
    return () => {
      window.removeEventListener("touchstart", onStart);
      window.removeEventListener("touchmove", onMove);
      window.removeEventListener("touchend", onEnd);
      window.removeEventListener("touchcancel", onEnd);
    };
  }, [chapter?.has_next, go]);

  // The chapter-jump list. Rebuilding it inline during render allocated one
  // object per chapter on EVERY render — and the scroll handler re-renders this
  // component on every animation frame, so scrolling a 2334-chapter novel was
  // allocating 2334 objects ~60x a second.
  const chapterOptions = useMemo(
    () =>
      chapterList && chapterList.length > 0
        ? chapterList.map((c) => ({
            value: String(c.position),
            label: `${c.position}. ${c.title || `Chapter ${c.number || c.position}`}`,
          }))
        : [{ value: String(position), label: `Ch. ${chapter?.number || position}` }],
    [chapterList, position, chapter?.number]
  );

  if (isLoading) return <p className="text-muted-foreground">Loading…</p>;
  if (isError || !chapter)
    return <p className="text-destructive">Chapter not found.</p>;

  return (
    <article ref={articleRef} className="mx-auto max-w-reading pb-28">
      {/* Chapter reading progress (how far through this chapter). */}
      <div className="fixed left-0 top-0 z-40 h-1 w-full bg-transparent">
        <div
          ref={progressBarRef}
          className="h-full bg-accent transition-[width] duration-150 ease-out"
          style={{ width: 0 }}
        />
      </div>

      {/* Sticky reader toolbar — keeps "Contents" and text size reachable
          anywhere in the chapter, not just at the top. */}
      <div className="sticky top-0 z-30 mb-8 flex items-center justify-between gap-2 border-b border-border/60 bg-background/85 py-2.5 backdrop-blur supports-[backdrop-filter]:bg-background/70">
        <Link
          href={`/book/${bookId}`}
          className="kicker inline-flex shrink-0 items-center gap-1.5 hover:text-foreground transition-colors"
        >
          <ChevronLeft size={14} /> <span className="hidden sm:inline">Contents</span>
        </Link>
        <div className="flex min-w-0 items-center gap-1">
          <Button variant="ghost" size="icon" aria-label="Previous chapter"
            disabled={!chapter.has_prev} onClick={() => go(-1)}>
            <ChevronLeft size={16} />
          </Button>
          {/* Jump to any chapter without leaving the reader. */}
          <Select
            aria-label="Jump to chapter"
            className="min-w-0 max-w-[8.5rem] sm:max-w-[15rem]"
            value={String(position)}
            onChange={(v) => {
              const p = Number(v);
              if (p !== position) {
                if (p > position) markRead();
                router.push(`/read/${bookId}/${p}`);
              }
            }}
            options={chapterOptions}
          />
          <Button variant="ghost" size="icon" aria-label="Next chapter"
            disabled={!chapter.has_next} onClick={() => go(1)}>
            <ChevronRight size={16} />
          </Button>
          <div className="mx-1 h-4 w-px bg-border" />
          <div className="relative" ref={typeRef}>
            <button
              type="button"
              onClick={() => setShowType((v) => !v)}
              aria-label="Typography"
              title="Font, size & spacing"
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-md hover:bg-muted",
                showType ? "text-foreground" : "text-muted-foreground"
              )}
            >
              <span className="font-display text-base leading-none">Aa</span>
            </button>
            {showType && (
              <div className="absolute right-0 top-full z-40 mt-2 flex w-60 flex-col gap-3 rounded-lg border border-border bg-card p-3 shadow-xl">
                <div>
                  <p className="kicker mb-1.5">Font</p>
                  <div className="flex overflow-hidden rounded-sm border border-border">
                    {Object.entries(READER_FONTS).map(([key, f]) => (
                      <button
                        key={key}
                        type="button"
                        onClick={() => setReaderFont(key)}
                        style={{ fontFamily: f.stack }}
                        className={cn(
                          "flex-1 px-2 py-1.5 text-sm",
                          font === key
                            ? "bg-foreground text-background"
                            : "bg-background text-muted-foreground hover:text-foreground"
                        )}
                      >
                        {f.label}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="kicker mb-1.5">Text size</p>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setSize(scale - 0.1)}
                      aria-label="Decrease text size"
                      className="flex h-8 w-8 items-center justify-center rounded-sm border border-border text-muted-foreground hover:text-foreground"
                    >
                      <Minus size={14} />
                    </button>
                    <span className="flex-1 text-center text-sm tabular">
                      {Math.round(scale * 100)}%
                    </span>
                    <button
                      type="button"
                      onClick={() => setSize(scale + 0.1)}
                      aria-label="Increase text size"
                      className="flex h-8 w-8 items-center justify-center rounded-sm border border-border text-muted-foreground hover:text-foreground"
                    >
                      <Plus size={14} />
                    </button>
                  </div>
                </div>
                <div>
                  <p className="kicker mb-1.5">Line spacing</p>
                  <div className="flex overflow-hidden rounded-sm border border-border">
                    {LEADINGS.map((l) => (
                      <button
                        key={l.value}
                        type="button"
                        onClick={() => setReaderLeading(l.value)}
                        className={cn(
                          "flex-1 px-2 py-1.5 text-xs",
                          Math.abs(leading - l.value) < 0.01
                            ? "bg-foreground text-background"
                            : "bg-background text-muted-foreground hover:text-foreground"
                        )}
                      >
                        {l.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      <h1 className="font-display text-3xl md:text-4xl mb-8 leading-tight">
        {chapter.title || `Chapter ${chapter.number || position}`}
      </h1>

      <TtsPlayer
        ref={playerRef}
        bookId={bookId}
        position={position}
        onSegments={setSegments}
        onHighlight={setHighlight}
        onComplete={markRead}
        getReadingFraction={() => fracRef.current}
        hasNext={chapter.has_next}
        onNextChapter={() => {
          try {
            sessionStorage.setItem("ns:tts-autostart", "1");
          } catch {
            /* ignore */
          }
          go(1);
        }}
        autoStart={autoStartTts}
        mediaTitle={chapter.title || `Chapter ${chapter.number || position}`}
        mediaSubtitle={book?.title}
        mediaArtwork={book?.has_cover ? coverUrl(bookId, 800) : undefined}
      />

      {/* Overscroll-to-next hint (mobile): fills as you pull past the end. */}
      {pullNext > 0.02 && (
        <div className="pointer-events-none fixed inset-x-0 bottom-28 z-40 flex justify-center px-4">
          <div
            className="flex items-center gap-2 rounded-full border border-border bg-card/95 px-4 py-2 text-sm font-medium text-foreground shadow-lg backdrop-blur"
            style={{ opacity: Math.min(1, 0.4 + pullNext * 0.6) }}
          >
            <ChevronRight
              size={15}
              className="text-accent"
              style={{ transform: `translateX(${pullNext * 4}px)` }}
            />
            {pullNext >= 1 ? "Release for next chapter" : "Keep pulling for next chapter"}
          </div>
        </div>
      )}

      {/* Only shown while narrating and the user has scrolled away from the
          highlight — click to snap back to it and resume following. */}
      {segments && highlight != null && !following && (
        <button
          type="button"
          onClick={followNarration}
          className="fixed bottom-24 left-1/2 z-40 flex -translate-x-1/2 items-center gap-2 rounded-full border border-border bg-card/95 px-4 py-2 text-sm font-medium text-foreground shadow-lg backdrop-blur transition-colors hover:bg-muted"
        >
          <LocateFixed size={15} className="text-accent" /> Follow narration
        </button>
      )}

      {segments ? (
        <ReadAlong
          blocks={segments}
          highlight={highlight}
          style={contentStyle}
          onSeek={(idx) => playerRef.current?.seekToSentence(idx)}
        />
      ) : clean === null ? (
        <p className="text-muted-foreground">Setting type…</p>
      ) : (
        <div
          className="prose-reading"
          style={contentStyle}
          dangerouslySetInnerHTML={{ __html: clean }}
        />
      )}

      <nav className="mt-14 flex items-center justify-between rule-accent pt-6">
        <Button variant="outline" size="sm" onClick={() => go(-1)}
          disabled={!chapter.has_prev}>
          <ChevronLeft size={15} /> Previous
        </Button>
        {progress && (
          <span className="kicker text-center">
            {position} / {progress.total_chapters} · {progress.percent_read}% read
            {progress.hours_left > 0 && ` · ~${progress.hours_left}h left`}
          </span>
        )}
        <Button variant="outline" size="sm" onClick={() => go(1)}
          disabled={!chapter.has_next}>
          Next <ChevronRight size={15} />
        </Button>
      </nav>

      {progress?.read_positions.includes(position) && (
        <p className="mt-4 flex items-center justify-center gap-1.5 kicker text-accent">
          <Check size={13} /> read
        </p>
      )}
    </article>
  );
}

function ReadAlong({
  blocks,
  highlight,
  style,
  onSeek,
}: {
  blocks: TtsBlock[];
  highlight: number | null;
  style?: CSSProperties;
  onSeek?: (globalSentenceIndex: number) => void;
}) {
  // Global sentence index at the start of each block — only text blocks advance
  // it (images carry no audio), so these indices match the audio chunk indices.
  const starts: number[] = [];
  let acc = 0;
  for (const block of blocks) {
    starts.push(acc);
    if (block.type === "text") acc += block.sentences.length;
  }
  return (
    <div className="prose-reading" style={style}>
      {blocks.map((block, bi) => {
        if (block.type === "image") {
          const src = resolveImgSrc(block.src);
          // eslint-disable-next-line @next/next/no-img-element
          return src ? <img key={bi} src={src} alt={block.alt || ""} /> : null;
        }
        return (
          <p key={bi}>
            {block.sentences.map((sentence, si) => {
              const idx = starts[bi] + si;
              return (
                <span
                  key={si}
                  data-si={idx}
                  onClick={
                    onSeek
                      ? () => {
                          // Don't hijack an active text selection.
                          const sel = window.getSelection();
                          if (sel && !sel.isCollapsed) return;
                          onSeek(idx);
                        }
                      : undefined
                  }
                  title={onSeek ? "Play from here" : undefined}
                  className={cn(
                    "transition-colors duration-150",
                    onSeek && "cursor-pointer hover:bg-muted rounded-sm",
                    idx === highlight &&
                      "bg-accent-soft rounded-sm box-decoration-clone"
                  )}
                >
                  {sentence}{" "}
                </span>
              );
            })}
          </p>
        );
      })}
    </div>
  );
}
