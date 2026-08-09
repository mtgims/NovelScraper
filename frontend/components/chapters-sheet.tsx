"use client";

import { AnimatePresence, motion, useDragControls } from "framer-motion";
import { ArrowDownUp, Check, ChevronDown, Circle, X } from "lucide-react";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
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

/** Full-height (85vh) bottom sheet listing every chapter grouped into collapsible
 *  volumes. Opens from the bottom; closes on backdrop tap, the X, dragging the
 *  handle down, or pulling the list down while it's scrolled to the top. */
export function ChaptersSheet({
  open, onClose, bookId, title, hasCover, chapters, volumes, readSet,
  resumeAt, onToggleRead,
}: Props) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const [newestFirst, setNewestFirst] = useState(false);
  const [expanded, setExpanded] = useState<number | null>(null);
  const controls = useDragControls();
  const bodyRef = useRef<HTMLDivElement>(null);
  const pullStartY = useRef(0);

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

  // On open, expand the volume you're currently reading (or the first one).
  useEffect(() => {
    if (!open) return;
    const cur = chapters.find((c) => c.position === resumeAt);
    setExpanded(cur ? cur.volume : orderedVolumes[0]?.number ?? null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!mounted) return null;

  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[80] flex items-end md:hidden">
          <motion.div
            className="absolute inset-0 bg-black/50 backdrop-blur-[1px]"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
          />

          <motion.div
            data-testid="chapters-sheet"
            className="relative flex max-h-[85vh] w-full flex-col rounded-t-2xl border-t border-border bg-card shadow-2xl"
            initial={{ y: "100%" }}
            animate={{ y: 0 }}
            exit={{ y: "100%" }}
            transition={{ type: "spring", damping: 34, stiffness: 340 }}
            drag="y"
            dragControls={controls}
            dragListener={false}
            dragConstraints={{ top: 0, bottom: 0 }}
            dragElastic={{ top: 0, bottom: 0.4 }}
            onDragEnd={(_, info) => {
              if (info.offset.y > 120 || info.velocity.y > 600) onClose();
            }}
          >
            {/* Header + grabber — this area drags the sheet. */}
            <div
              onPointerDown={(e) => controls.start(e)}
              style={{ touchAction: "none" }}
              className="shrink-0 cursor-grab select-none px-4 pb-3 pt-2.5"
            >
              <div className="mx-auto mb-3 h-1.5 w-10 rounded-full bg-border" />
              <div className="flex items-center gap-3">
                <div className="h-14 w-10 shrink-0 overflow-hidden rounded-sm border border-border bg-muted">
                  {hasCover ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={coverUrl(bookId)}
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
                  onClick={() => setNewestFirst((v) => !v)}
                  className="flex shrink-0 items-center gap-1 rounded-full border border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
                >
                  <ArrowDownUp size={13} /> {newestFirst ? "Newest" : "Oldest"}
                </button>
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close"
                  className="shrink-0 rounded-full p-1 text-muted-foreground hover:text-foreground"
                >
                  <X size={18} />
                </button>
              </div>
            </div>

            {/* Scrollable volumes accordion. Pulling down while at the top closes. */}
            <div
              ref={bodyRef}
              className="min-h-0 flex-1 overflow-y-auto overscroll-contain pb-8"
              onTouchStart={(e) => (pullStartY.current = e.touches[0].clientY)}
              onTouchMove={(e) => {
                const dy = e.touches[0].clientY - pullStartY.current;
                if ((bodyRef.current?.scrollTop ?? 0) <= 0 && dy > 90) onClose();
              }}
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
                        <motion.div
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
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </div>
                );
              })}
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body
  );
}
