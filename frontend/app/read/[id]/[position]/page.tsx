"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, LocateFixed, Minus, Plus } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { TtsPlayer, type TtsPlayerHandle } from "@/components/tts-player";
import { api, API_BASE, coverUrl } from "@/lib/api";
import { useBook, useChapter, useChapters, useProgress } from "@/lib/queries";
import { cn } from "@/lib/utils";

const SIZE_KEY = "ns-reading-scale";

// Per-chapter reading position, stored in localStorage as a 0..1 fraction.
// localStorage is synchronous and device-local, which is exactly right for a
// reading position: it survives every kind of exit (SPA nav, close, reload) with
// no network, no cache races, and each chapter remembers its own spot.
const posKey = (bookId: number, position: number) => `ns:pos:${bookId}:${position}`;

// Resolve chapter <img> sources:
//  - stored imported illustrations ("/api/books/{id}/images/…") -> absolute
//    backend URL so they load (API_BASE may be a different origin).
//  - already-absolute (http(s)/data) -> keep (e.g. scraped images).
//  - anything else (stray relative EPUB paths we don't store) -> drop.
function prepareImages(html: string): string {
  if (typeof window === "undefined" || !html.includes("<img")) return html;
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc.querySelectorAll("img").forEach((img) => {
    const src = img.getAttribute("src") || "";
    if (src.startsWith("/api/")) {
      img.setAttribute("src", `${API_BASE}${src}`);
    } else if (!/^(https?:|data:)/i.test(src)) {
      img.remove();
    }
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
  const [chapterPct, setChapterPct] = useState(0);
  // Set when we land on a chapter via TTS auto-advance, so narration resumes.
  const [autoStartTts, setAutoStartTts] = useState(false);
  // 0..1 progress of the "pull past the end for the next chapter" gesture.
  const [pullNext, setPullNext] = useState(0);
  // Read-along (TTS): sentence paragraphs to render + the sentence to highlight.
  const [segments, setSegments] = useState<string[][] | null>(null);
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

  // --- reading size (persisted) ---
  useEffect(() => {
    const saved = Number(localStorage.getItem(SIZE_KEY));
    if (saved) setScale(saved);
  }, []);
  const setSize = useCallback((next: number) => {
    const clamped = Math.min(1.4, Math.max(0.85, Number(next.toFixed(2))));
    setScale(clamped);
    localStorage.setItem(SIZE_KEY, String(clamped));
  }, []);

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
  }, [bookId, position, markRead]);

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

  if (isLoading) return <p className="text-muted-foreground">Loading…</p>;
  if (isError || !chapter)
    return <p className="text-destructive">Chapter not found.</p>;

  return (
    <article ref={articleRef} className="mx-auto max-w-reading pb-28">
      {/* Chapter reading progress (how far through this chapter). */}
      <div className="fixed left-0 top-0 z-40 h-1 w-full bg-transparent">
        <div
          className="h-full bg-accent transition-[width] duration-150 ease-out"
          style={{ width: `${chapterPct * 100}%` }}
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
          <select
            aria-label="Jump to chapter"
            value={position}
            onChange={(e) => {
              const p = Number(e.target.value);
              if (p !== position) {
                if (p > position) markRead();
                router.push(`/read/${bookId}/${p}`);
              }
            }}
            className="min-w-0 max-w-[8.5rem] truncate rounded-sm border border-border bg-background px-2 py-1 text-xs text-foreground outline-none transition-colors focus:border-accent sm:max-w-[15rem]"
          >
            {chapterList && chapterList.length > 0 ? (
              chapterList.map((c) => (
                <option key={c.position} value={c.position}>
                  {c.position}. {c.title || `Chapter ${c.number || c.position}`}
                </option>
              ))
            ) : (
              <option value={position}>Ch. {chapter.number || position}</option>
            )}
          </select>
          <Button variant="ghost" size="icon" aria-label="Next chapter"
            disabled={!chapter.has_next} onClick={() => go(1)}>
            <ChevronRight size={16} />
          </Button>
          <div className="mx-1 hidden h-4 w-px bg-border sm:block" />
          <Button variant="ghost" size="icon" aria-label="Decrease text size"
            className="hidden sm:inline-flex" onClick={() => setSize(scale - 0.1)}>
            <Minus size={14} />
          </Button>
          <Button variant="ghost" size="icon" aria-label="Increase text size"
            className="hidden sm:inline-flex" onClick={() => setSize(scale + 0.1)}>
            <Plus size={14} />
          </Button>
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
        mediaArtwork={book?.has_cover ? coverUrl(bookId) : undefined}
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
          paragraphs={segments}
          highlight={highlight}
          scale={scale}
          onSeek={(idx) => playerRef.current?.seekToSentence(idx)}
        />
      ) : clean === null ? (
        <p className="text-muted-foreground">Setting type…</p>
      ) : (
        <div
          className="prose-reading"
          style={{ fontSize: `${1.1875 * scale}rem` }}
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
  paragraphs,
  highlight,
  scale,
  onSeek,
}: {
  paragraphs: string[][];
  highlight: number | null;
  scale: number;
  onSeek?: (globalSentenceIndex: number) => void;
}) {
  // Precompute each paragraph's starting global sentence index.
  const offsets: number[] = [];
  let acc = 0;
  for (const para of paragraphs) {
    offsets.push(acc);
    acc += para.length;
  }
  return (
    <div className="prose-reading" style={{ fontSize: `${1.1875 * scale}rem` }}>
      {paragraphs.map((para, pi) => (
        <p key={pi}>
          {para.map((sentence, si) => {
            const idx = offsets[pi] + si;
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
      ))}
    </div>
  );
}
