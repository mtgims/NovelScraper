"use client";

import { AnimatePresence, animate, m, useMotionValue, useTransform } from "framer-motion";
import { ArrowDownUp, Check, ChevronDown, Circle, X } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { coverUrl } from "@/lib/api";
import type { ChapterListItem, Volume } from "@/lib/types";
import { cn } from "@/lib/utils";

type Props = {
  open: boolean;
  onClose: () => void;
  bookId: number;
  title: string;
  hasCover: boolean;
  chapters: ChapterListItem[];
  volumes: Volume[];
  readSet: Set<number>;
  resumeAt: number;
  /** Toggle one chapter's read state (single-tap; no range select on mobile). */
  onToggleRead: (position: number, read: boolean) => void;
};

const SPRING = { type: "spring", damping: 34, stiffness: 340 } as const;

/** Full-height (85vh) bottom sheet listing every chapter grouped into collapsible
 *  volumes. The sheet's vertical position is a single finger-driven value: dragging
 *  the handle *or* the list (when scrolled to the top) tracks your finger 1:1, and
 *  releasing decides — past ~30% of its height (or a fast flick) it finishes closing,
 *  otherwise it springs back. Also closes on backdrop tap, the X, or Escape. */
export function ChaptersSheet({
  open, onClose, bookId, title, hasCover, chapters, volumes, readSet,
  resumeAt, onToggleRead,
}: Props) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const [newestFirst, setNewestFirst] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);

  const sheetRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const heightRef = useRef(0); // last measured sheet height (px)

  // Sheet offset from its resting (open) position; 0 = fully open, H = off-screen.
  const y = useMotionValue(1000);
  const backdropOpacity = useTransform(y, (v) => {
    const h = heightRef.current || (typeof window !== "undefined" ? window.innerHeight : 1000);
    return Math.max(0, Math.min(1, 1 - v / h));
  });

  // Gesture bookkeeping shared by the handle (pointer) and body (touch) drags.
  const velRef = useRef(0);

  const sheetHeight = () =>
    sheetRef.current?.offsetHeight ||
    (typeof window !== "undefined" ? Math.round(window.innerHeight * 0.85) : 600);

  const close = useCallback(() => {
    const h = heightRef.current || sheetHeight();
    animate(y, h, { duration: 0.24, ease: "easeIn", onComplete: () => onClose() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onClose, y]);

  const release = useCallback(() => {
    const h = heightRef.current || sheetHeight();
    if (y.get() > h * 0.3 || velRef.current > 800) close();
    else animate(y, 0, SPRING);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [close, y]);

  const byVolume = useMemo(() => {
    const m = new Map<number, ChapterListItem[]>();
    for (const ch of chapters) {
      const arr = m.get(ch.volume) ?? [];
      arr.push(ch);
      m.set(ch.volume, arr);
    }
    return m;
  }, [chapters]);

  const orderedVolumes = useMemo(() => {
    const vs = [...volumes].sort((a, b) => a.number - b.number);
    return newestFirst ? vs.reverse() : vs;
  }, [volumes, newestFirst]);

  // On open: measure, park off-screen, then slide in; expand the current volume.
  useEffect(() => {
    if (!open) return;
    const h = sheetHeight();
    heightRef.current = h;
    y.set(h);
    const controls = animate(y, 0, SPRING);
    const cur = chapters.find((c) => c.position === resumeAt);
    setExpanded(cur ? cur.volume : orderedVolumes[0]?.number ?? null);
    return () => controls.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, close]);

  // Body drag: follow the finger only while the list is scrolled to the top and the
  // gesture heads downward; otherwise leave native scrolling untouched. Non-passive
  // so we can preventDefault the browser's competing overscroll once we take over.
  useEffect(() => {
    const el = bodyRef.current;
    if (!open || !el) return;
    let startY = 0, lastY = 0, lastT = 0, v = 0;
    let tracking = false, active = false;

    const onStart = (e: TouchEvent) => {
      startY = e.touches[0].clientY;
      lastY = startY;
      lastT = performance.now();
      v = 0;
      tracking = el.scrollTop <= 0;
      active = false;
    };
    const onMove = (e: TouchEvent) => {
      const cy = e.touches[0].clientY;
      if (!active) {
        if (!tracking) {
          if (el.scrollTop <= 0) { tracking = true; startY = cy; }
          else { lastY = cy; return; }
        }
        if (cy - startY > 6 && el.scrollTop <= 0) active = true; // confirmed pull-down
        else { lastY = cy; return; } // upward / tiny → native scroll
      }
      e.preventDefault();
      y.set(Math.max(0, cy - startY));
      const now = performance.now();
      const dt = now - lastT;
      if (dt > 0) v = ((cy - lastY) / dt) * 1000;
      lastY = cy;
      lastT = now;
    };
    const onEnd = () => {
      if (active) { velRef.current = v; release(); }
      tracking = false;
      active = false;
    };
    el.addEventListener("touchstart", onStart, { passive: true });
    el.addEventListener("touchmove", onMove, { passive: false });
    el.addEventListener("touchend", onEnd, { passive: true });
    el.addEventListener("touchcancel", onEnd, { passive: true });
    return () => {
      el.removeEventListener("touchstart", onStart);
      el.removeEventListener("touchmove", onMove);
      el.removeEventListener("touchend", onEnd);
      el.removeEventListener("touchcancel", onEnd);
    };
  }, [open, release, y]);

  // Handle drag (pointer): the grabber + header area moves the whole sheet.
  const startYRef = useRef(0);
  const draggingRef = useRef(false);
  const lastYRef = useRef(0);
  const lastTRef = useRef(0);

  const onHandleDown = (e: React.PointerEvent) => {
    // Don't hijack taps on the header's buttons (X / sort toggle) — capturing the
    // pointer here would swallow their click.
    if ((e.target as HTMLElement).closest("button")) return;
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
    startYRef.current = e.clientY;
    lastYRef.current = e.clientY;
    lastTRef.current = performance.now();
    velRef.current = 0;
    draggingRef.current = true;
  };
  const onHandleMove = (e: React.PointerEvent) => {
    if (!draggingRef.current) return;
    y.set(Math.max(0, e.clientY - startYRef.current));
    const now = performance.now();
    const dt = now - lastTRef.current;
    if (dt > 0) velRef.current = ((e.clientY - lastYRef.current) / dt) * 1000;
    lastYRef.current = e.clientY;
    lastTRef.current = now;
  };
  const onHandleUp = () => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    release();
  };

  if (!mounted || !open) return null;

  return createPortal(
    <div className="fixed inset-0 z-[80] flex items-end md:hidden">
      <m.div
        className="absolute inset-0 bg-black/50 backdrop-blur-[1px]"
        style={{ opacity: backdropOpacity }}
        onClick={close}
      />

      <m.div
        ref={sheetRef}
        data-testid="chapters-sheet"
        style={{ y }}
        className="relative flex max-h-[85vh] w-full flex-col rounded-t-2xl border-t border-border bg-card shadow-2xl"
      >
        {/* Header + grabber — this area drags the sheet. */}
        <div
          onPointerDown={onHandleDown}
          onPointerMove={onHandleMove}
          onPointerUp={onHandleUp}
          onPointerCancel={onHandleUp}
          style={{ touchAction: "none" }}
          className="shrink-0 cursor-grab select-none px-4 pb-3 pt-2.5"
        >
          <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-border" />
          <div className="flex items-center gap-3">
            <div className="h-14 w-10 shrink-0 overflow-hidden rounded-sm border border-border bg-muted">
              {hasCover ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={coverUrl(bookId, 200)}
                  alt=""
                  draggable={false}
                  className="h-full w-full object-cover"
                />
              ) : (
                <div className="flex h-full w-full items-center justify-center">
                  <div className="h-1 w-4 bg-accent" />
                </div>
              )}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-display text-base leading-tight">{title}</p>
              <p className="kicker mt-0.5">{chapters.length} chapters</p>
            </div>
            <button
              type="button"
              onClick={() => setNewestFirst((val) => !val)}
              className="flex shrink-0 items-center gap-1 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
            >
              <ArrowDownUp size={13} /> {newestFirst ? "Newest" : "Oldest"}
            </button>
            <button
              type="button"
              onClick={close}
              aria-label="Close"
              className="shrink-0 rounded-full p-1 text-muted-foreground hover:text-foreground"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Scrollable volumes accordion. Pulling down while at the top drags/closes. */}
        <div
          ref={bodyRef}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain pb-8"
        >
          {orderedVolumes.map((vol) => {
            const base = byVolume.get(vol.number) ?? [];
            const chs = newestFirst ? [...base].reverse() : base;
            const isOpen = expanded === vol.number;
            return (
              <div key={vol.id} className="border-b border-border/60">
                <button
                  type="button"
                  onClick={() => setExpanded(isOpen ? null : vol.number)}
                  className="flex w-full items-center justify-between px-4 py-3 text-left"
                >
                  <span className="min-w-0 truncate">
                    <span className="font-medium">Vol {vol.number}</span>{" "}
                    <span className="kicker">· {vol.chapter_count} ch</span>
                  </span>
                  <ChevronDown
                    size={16}
                    className={cn(
                      "shrink-0 text-muted-foreground transition-transform duration-200",
                      isOpen && "rotate-180"
                    )}
                  />
                </button>
                <AnimatePresence initial={false}>
                  {isOpen && (
                    <m.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: "auto", opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{ duration: 0.22, ease: "easeOut" }}
                      className="overflow-hidden"
                    >
                      <div className="divide-y divide-border/40 pb-1">
                        {chs.map((ch) => {
                          const read = readSet.has(ch.position);
                          const current = ch.position === resumeAt;
                          return (
                            <div
                              key={ch.position}
                              className={cn(
                                "flex items-center",
                                current && "bg-accent-soft"
                              )}
                            >
                              <Link
                                href={`/read/${bookId}/${ch.position}`}
                                onClick={onClose}
                                className="flex min-w-0 flex-1 items-center gap-3 px-4 py-2.5 text-sm"
                              >
                                <span className="tabular w-9 shrink-0 text-xs text-muted-foreground/60">
                                  {ch.position}
                                </span>
                                <span
                                  className={cn(
                                    "min-w-0 flex-1 truncate",
                                    read ? "text-muted-foreground" : "text-foreground"
                                  )}
                                >
                                  {ch.title || `Chapter ${ch.number || ch.position}`}
                                </span>
                                {current && !read && (
                                  <span className="kicker shrink-0 text-accent">here</span>
                                )}
                              </Link>
                              <button
                                type="button"
                                onClick={() => onToggleRead(ch.position, read)}
                                aria-label={read ? "Mark as unread" : "Mark as read"}
                                className="shrink-0 px-3 py-2.5 text-muted-foreground transition-colors hover:text-accent"
                              >
                                {read ? (
                                  <Check size={16} className="text-accent" />
                                ) : (
                                  <Circle size={16} className="opacity-40" />
                                )}
                              </button>
                            </div>
                          );
                        })}
                      </div>
                    </m.div>
                  )}
                </AnimatePresence>
              </div>
            );
          })}
        </div>
      </m.div>
    </div>,
    document.body
  );
}
