"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Minus, Plus } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { TtsPlayer, type TtsPlayerHandle } from "@/components/tts-player";
import { api } from "@/lib/api";
import { useChapter, useProgress } from "@/lib/queries";
import { cn } from "@/lib/utils";

const SIZE_KEY = "ns-reading-scale";

// Drop images whose src isn't absolute (http(s)/data). Imported EPUB chapters
// reference intra-EPUB image paths (e.g. "images/foo.jpg") that we don't store,
// so they render as broken icons and — having no dimensions — keep resizing the
// page as they 404, which destabilizes scrolling. Scraped chapters use absolute
// URLs and are kept.
function stripBrokenImages(html: string): string {
  if (typeof window === "undefined" || !html.includes("<img")) return html;
  const doc = new DOMParser().parseFromString(html, "text/html");
  let removed = false;
  doc.querySelectorAll("img").forEach((img) => {
    if (!/^(https?:|data:)/i.test(img.getAttribute("src") || "")) {
      img.remove();
      removed = true;
    }
  });
  return removed ? doc.body.innerHTML : html;
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
  const markedRef = useRef(false);
  const restoredRef = useRef(false);
  const fracRef = useRef(0);
  const lastSaveRef = useRef(0);
  const hadSegmentsRef = useRef(false);
  // Guards the scroll save from writing a premature 0 (e.g. React StrictMode's
  // mount→unmount→mount in dev) that would clobber the stored position before a
  // real scroll/restore has happened.
  const movedRef = useRef(false);
  // True once the user has initiated scrolling themselves (wheel/touch/keys), so
  // restore never fights them (e.g. while broken images/fonts grow the page).
  const userScrolledRef = useRef(false);

  // Scroll the document to a fraction of its scrollable height. Used for both
  // restore-on-open and preserving position when the TTS read-along closes.
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

  // Note when the user starts scrolling themselves, so restore backs off.
  useEffect(() => {
    const intent = () => {
      userScrolledRef.current = true;
    };
    const onKey = (e: KeyboardEvent) => {
      if (
        ["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " ", "Spacebar"].includes(
          e.key
        )
      )
        userScrolledRef.current = true;
    };
    window.addEventListener("wheel", intent, { passive: true });
    window.addEventListener("touchmove", intent, { passive: true });
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("wheel", intent);
      window.removeEventListener("touchmove", intent);
      window.removeEventListener("keydown", onKey);
    };
  }, []);

  // Keep the currently-narrated sentence in view.
  useEffect(() => {
    if (highlight == null) return;
    document
      .querySelector<HTMLElement>(`[data-si="${highlight}"]`)
      ?.scrollIntoView({ block: "center", behavior: "smooth" });
  }, [highlight]);

  // --- sanitize content (client-only) ---
  useEffect(() => {
    setClean(null);
    markedRef.current = false;
    restoredRef.current = false;
    movedRef.current = false;
    userScrolledRef.current = false;
    fracRef.current = 0;
    setChapterPct(0);
    if (!chapter?.content) return;
    let alive = true;
    import("dompurify").then((m) => {
      if (alive) setClean(stripBrokenImages(m.default.sanitize(chapter.content)));
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

  // Set the resume point when a chapter opens.
  useEffect(() => {
    if (Number.isFinite(bookId) && Number.isFinite(position)) {
      api.updateProgress(bookId, { last_position: position }).catch(() => {});
    }
  }, [bookId, position]);

  // Restore scroll (once) when returning to the last-read chapter; if the
  // chapter fits on screen (not scrollable), count it as read.
  //
  // Re-apply the saved *fraction* a few times as the page settles: measuring
  // scrollHeight the instant `clean` renders often reads a too-small height
  // (web fonts / layout still growing), so a single scrollTo lands near the top.
  // Re-applying the fraction against the final height puts us back in place.
  useEffect(() => {
    if (restoredRef.current || clean === null || !progress) return;
    restoredRef.current = true;
    const doc = document.documentElement;
    if (doc.scrollHeight - doc.clientHeight <= 4) {
      markRead();
      return;
    }
    if (position !== progress.last_position || !(progress.scroll > 0)) return;
    if (userScrolledRef.current) return; // user is already reading — don't yank
    const frac = progress.scroll;
    // Re-apply only while the user hasn't taken over, so a growing page (fonts,
    // late images) can't turn the restore into an auto-scroll that fights them.
    const apply = () => {
      if (!userScrolledRef.current) scrollToFraction(frac);
    };
    apply();
    const raf = requestAnimationFrame(apply);
    const timers = [60, 250, 600].map((ms) => window.setTimeout(apply, ms));
    return () => {
      cancelAnimationFrame(raf);
      timers.forEach(clearTimeout);
    };
  }, [clean, progress, position, markRead, scrollToFraction]);

  // When the TTS player closes, the content swaps from the read-along back to
  // the plain chapter (a different height), which would otherwise shift or
  // clamp the scroll to the bottom. Preserve the reading position by re-applying
  // the current fraction against the new layout.
  useEffect(() => {
    const had = hadSegmentsRef.current;
    hadSegmentsRef.current = segments !== null;
    if (!had || segments !== null) return;
    const frac = fracRef.current;
    scrollToFraction(frac);
    const raf = requestAnimationFrame(() => scrollToFraction(frac));
    return () => cancelAnimationFrame(raf);
  }, [segments, scrollToFraction]);

  // Track scroll: chapter progress bar, throttled save, mark-read at the bottom.
  useEffect(() => {
    function onScroll() {
      const el = document.documentElement;
      const max = el.scrollHeight - el.clientHeight;
      const frac = max > 0 ? Math.min(1, el.scrollTop / max) : 0;
      fracRef.current = frac;
      movedRef.current = true;
      setChapterPct(frac);
      const now = Date.now();
      if (now - lastSaveRef.current > 3000) {
        lastSaveRef.current = now;
        api
          .updateProgress(bookId, { last_position: position, scroll: frac })
          .catch(() => {});
      }
      if (frac >= 0.98) markRead();
    }
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      // Only persist scroll once something real has moved it, so a transient
      // unmount can't overwrite the saved position with 0.
      if (movedRef.current) {
        api
          .updateProgress(bookId, { last_position: position, scroll: fracRef.current })
          .catch(() => {});
      }
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
    <article className="mx-auto max-w-reading pb-28">
      {/* Chapter reading progress (how far through this chapter). */}
      <div className="fixed left-0 top-0 z-40 h-1 w-full bg-transparent">
        <div
          className="h-full bg-accent transition-[width] duration-150 ease-out"
          style={{ width: `${chapterPct * 100}%` }}
        />
      </div>

      <div className="mb-10 flex items-center justify-between">
        <Link
          href={`/book/${bookId}`}
          className="kicker inline-flex items-center gap-1.5 hover:text-foreground transition-colors"
        >
          <ChevronLeft size={14} /> Contents
        </Link>
        <div className="flex items-center gap-3">
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
          <span className="kicker">Ch. {chapter.number || position}</span>
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
