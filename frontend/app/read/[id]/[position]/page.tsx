"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Minus, Plus } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { TtsPlayer, type TtsPlayerHandle } from "@/components/tts-player";
import { api, API_BASE } from "@/lib/api";
import { useChapter, useProgress } from "@/lib/queries";
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
  const { data: progress } = useProgress(bookId);
  const [clean, setClean] = useState<string | null>(null);
  const [scale, setScale] = useState(1);
  const [chapterPct, setChapterPct] = useState(0);
  // Read-along (TTS): sentence paragraphs to render + the sentence to highlight.
  const [segments, setSegments] = useState<string[][] | null>(null);
  const [highlight, setHighlight] = useState<number | null>(null);

  const playerRef = useRef<TtsPlayerHandle>(null);
  const articleRef = useRef<HTMLElement>(null);
  const markedRef = useRef(false);
  const restoredRef = useRef(false);
  const fracRef = useRef(0);            // current reading fraction (for TTS + save)
  const hadSegmentsRef = useRef(false); // previous read-along state (TTS close)
  // Saving is gated until the restore has run (or the user has scrolled), so a
  // scroll event fired at the top before restore can't overwrite the stored
  // position with 0.
  const saveEnabledRef = useRef(false);

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

  // Keep the currently-narrated sentence in view.
  useEffect(() => {
    if (highlight == null) return;
    document
      .querySelector<HTMLElement>(`[data-si="${highlight}"]`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [highlight]);

  // --- sanitize content (client-only); reset per-chapter state ---
  useEffect(() => {
    setClean(null);
    markedRef.current = false;
    restoredRef.current = false;
    saveEnabledRef.current = false;
    fracRef.current = 0;
    setChapterPct(0);
    if (!chapter?.content) return;
    let alive = true;
    import("dompurify").then((m) => {
      if (alive) setClean(prepareImages(m.default.sanitize(chapter.content)));
    });
    return () => {
      alive = false;
    };
  }, [chapter?.content]);

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

  // Restore the saved position for THIS chapter, once, after layout settles.
  useEffect(() => {
    if (restoredRef.current || clean === null) return;
    restoredRef.current = true;

    let target = 0;
    try {
      target = parseFloat(localStorage.getItem(posKey(bookId, position)) || "0") || 0;
    } catch {
      /* localStorage unavailable */
    }

    // Nothing to restore: enable saving immediately; if the chapter fits on one
    // screen (never scrolled), count it as read once laid out.
    if (!(target > 0)) {
      saveEnabledRef.current = true;
      waitForContentReady(articleRef.current, 1500).then(() => {
        const doc = document.documentElement;
        if (doc.scrollHeight - doc.clientHeight <= 4) markRead();
      });
      return;
    }

    // Restore, but never fight the user: if they start scrolling themselves
    // before the (async) restore fires, cancel it and let their position save.
    let cancelled = false;
    const cancel = () => {
      if (cancelled) return;
      cancelled = true;
      saveEnabledRef.current = true;
    };
    const onKey = (e: KeyboardEvent) => {
      if (
        ["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " ", "Spacebar"].includes(
          e.key
        )
      )
        cancel();
    };
    window.addEventListener("wheel", cancel, { passive: true });
    window.addEventListener("touchmove", cancel, { passive: true });
    window.addEventListener("keydown", onKey);

    waitForContentReady(articleRef.current).then(() => {
      if (!cancelled) scrollToFraction(target);
      // Enable saving only now, so the restore itself is what gets persisted
      // (idempotent) rather than a transient pre-restore position.
      saveEnabledRef.current = true;
    });

    return () => {
      cancelled = true;
      window.removeEventListener("wheel", cancel);
      window.removeEventListener("touchmove", cancel);
      window.removeEventListener("keydown", onKey);
    };
  }, [clean, bookId, position, markRead, scrollToFraction]);

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
        if (saveEnabledRef.current) {
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
          className="kicker inline-flex items-center gap-1.5 hover:text-foreground transition-colors"
        >
          <ChevronLeft size={14} /> Contents
        </Link>
        <div className="flex items-center gap-1.5">
          <div className="flex items-center gap-1">
            <Button variant="ghost" size="icon" aria-label="Decrease text size"
              onClick={() => setSize(scale - 0.1)}>
              <Minus size={14} />
            </Button>
            <Button variant="ghost" size="icon" aria-label="Increase text size"
              onClick={() => setSize(scale + 0.1)}>
              <Plus size={14} />
            </Button>
          </div>
          <span className="kicker hidden sm:inline">Ch. {chapter.number || position}</span>
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
      />

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
