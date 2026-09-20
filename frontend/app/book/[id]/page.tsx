"use client";

import {
  BookOpen,
  BookUp,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Circle,
  Download,
  DownloadCloud,
  Play,
  RefreshCw,
  RotateCcw,
  Trash2,
} from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { type MouseEvent as ReactMouseEvent, useRef, useState } from "react";

import { ChaptersSheet } from "@/components/chapters-sheet";
import { useConfirm } from "@/components/confirm-dialog";
import { PageHeader } from "@/components/page-header";
import { SwipeTabs } from "@/components/swipe-tabs";
import { StarRating } from "@/components/star-rating";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { coverUrl, downloadAllUrl, downloadUrl } from "@/lib/api";
import { EPUB_ACCEPT, pickEpubs } from "@/lib/epub";
import { useDismiss, useEnterTransition } from "@/lib/hooks";
import {
  useAddEpubs,
  useBook,
  useChapters,
  useDeleteBook,
  useProgress,
  useSetRating,
  useUpdateBookChapters,
  useUpdateProgress,
} from "@/lib/queries";
import { cn } from "@/lib/utils";

export default function BookDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const router = useRouter();
  const { data: book, isLoading, isError } = useBook(id);
  const { data: chapters } = useChapters(id);
  const { data: progress } = useProgress(id);
  const updateProgress = useUpdateProgress(id);
  const deleteBook = useDeleteBook();
  const setRating = useSetRating(id);
  const updateChapters = useUpdateBookChapters(id);
  const confirm = useConfirm();
  const [updateMsg, setUpdateMsg] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);

  // Anchor for range selection: the last-toggled chapter and the state it was
  // set to. Shift/Ctrl-clicking another mark applies that state to the range.
  const rangeAnchor = useRef<{ position: number; read: boolean } | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; position: number } | null>(
    null
  );

  // Close the context menu on any click / scroll / Escape.
  useDismiss(!!menu, () => setMenu(null), {
    pointerEvent: "click",
    closeOnScroll: true,
  });

  async function onDelete() {
    const ok = await confirm({
      title: "Delete this book?",
      message: "This removes the book and its EPUB files. This can't be undone.",
      confirmLabel: "Delete",
      danger: true,
    });
    if (!ok) return;
    await deleteBook.mutateAsync(id);
    router.push("/");
  }

  if (isLoading) {
    return (
      <div className="grid gap-8 md:grid-cols-[1fr_2fr]">
        <Skeleton className="aspect-[2/3]" />
        <div className="space-y-3">
          <Skeleton className="h-8 w-32" />
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
        </div>
      </div>
    );
  }
  if (isError || !book)
    return <p className="text-destructive">Book not found.</p>;

  const totalChapters = book.volumes.reduce((n, v) => n + v.chapter_count, 0);
  const readSet = new Set(progress?.read_positions ?? []);
  const resumeAt = progress?.last_position ?? 1;
  const hasStarted = (progress?.read_count ?? 0) > 0 || resumeAt > 1;

  const orderedPositions = (chapters ?? []).map((c) => c.position);

  function applyRead(positions: number[], read: boolean) {
    if (positions.length === 0) return;
    updateProgress.mutate(
      read ? { mark_positions: positions } : { unmark_positions: positions }
    );
  }

  function onToggle(e: ReactMouseEvent, position: number, read: boolean) {
    const rangeKey = e.shiftKey || e.ctrlKey || e.metaKey;
    if (rangeKey && rangeAnchor.current) {
      const anchor = rangeAnchor.current;
      const lo = Math.min(anchor.position, position);
      const hi = Math.max(anchor.position, position);
      applyRead(
        orderedPositions.filter((p) => p >= lo && p <= hi),
        anchor.read
      );
      rangeAnchor.current = { position, read: anchor.read };
    } else {
      const next = !read;
      applyRead([position], next);
      rangeAnchor.current = { position, read: next };
    }
  }

  return (
    <>
      <Link
        href="/"
        className="kicker mb-5 inline-flex items-center gap-1.5 transition-colors hover:text-foreground"
      >
        <ChevronLeft size={13} /> Library
      </Link>

      <PageHeader title={book.title} kicker={`${book.author} · ${book.site}`}>
        <div className="hidden flex-wrap gap-2 md:flex">
          <Link href={`/read/${book.id}/${resumeAt}`}>
            <Button size="sm">
              {hasStarted ? <Play size={15} /> : <BookOpen size={15} />}
              {hasStarted ? "Continue" : "Read"}
            </Button>
          </Link>
          {book.can_update && (
            <Button
              variant="outline"
              size="sm"
              disabled={updateChapters.isPending}
              onClick={() => {
                // Optimistic: an update is now in progress either way. A 409
                // ("already running") is expected on a double-fire and is fine;
                // only a genuine failure replaces the message.
                setUpdateMsg("Checking for new chapters — see Progress.");
                updateChapters.mutate(undefined, {
                  onError: (e) => {
                    if (!/already running/i.test((e as Error).message)) {
                      setUpdateMsg("Couldn't start an update. Please try again.");
                    }
                  },
                });
              }}
              title="Re-scrape the source for new chapters"
            >
              <RefreshCw
                size={15}
                className={cn(updateChapters.isPending && "animate-spin")}
              />
              Update
            </Button>
          )}
          {book.imported && <AddEpubButton id={book.id} />}
          <a href={downloadAllUrl(book.id)}>
            <Button variant="outline" size="sm">
              <DownloadCloud size={15} /> All
            </Button>
          </a>
          <Button variant="danger" size="sm" onClick={onDelete}>
            <Trash2 size={15} /> Delete
          </Button>
        </div>
      </PageHeader>

      <div className="mb-8 flex flex-wrap items-center gap-x-4 gap-y-2">
        <StarRating
          value={book.rating}
          onChange={(r) => setRating.mutate(r)}
        />
        {updateMsg && (
          <span className="kicker text-muted-foreground">
            {updateMsg}{" "}
            <Link href="/jobs" className="text-accent hover:underline">
              Progress →
            </Link>
          </span>
        )}
      </div>

      {/* Novel reading progress + bulk controls */}
      {progress && progress.total_chapters > 0 && (
        <div className="mb-8">
          <div className="mb-1.5 flex items-center justify-between kicker">
            <span>
              {progress.read_count} / {progress.total_chapters} chapters ·{" "}
              {progress.percent_read}%
            </span>
            <span>
              {progress.hours_left > 0
                ? `~${progress.hours_left}h left`
                : "finished"}
            </span>
          </div>
          <Progress done={progress.read_count} total={progress.total_chapters} />
          <div className="mt-2.5 flex gap-2">
            <Button
              variant="ghost"
              size="sm"
              disabled={updateProgress.isPending}
              onClick={() => updateProgress.mutate({ mark_all: true })}
            >
              <CheckCheck size={14} /> Mark all read
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={updateProgress.isPending}
              onClick={async () => {
                const ok = await confirm({
                  title: "Reset reading progress?",
                  message:
                    "This clears every read chapter and your current position for this book.",
                  confirmLabel: "Reset",
                  danger: true,
                });
                if (ok) updateProgress.mutate({ reset: true });
              }}
            >
              <RotateCcw size={14} /> Reset progress
            </Button>
          </div>
        </div>
      )}

      {/* ---- Mobile layout (phones) ---- */}
      <div className="md:hidden">
        {/* Smaller centered cover */}
        <div className="mx-auto w-40 max-w-full">
          <div className="aspect-[2/3] overflow-hidden border border-border bg-card">
            {book.has_cover ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={coverUrl(book.id, 800)}
                alt={`Cover of ${book.title}`}
                decoding="async"
                className="h-full w-full object-cover"
              />
            ) : (
              <div className="flex h-full flex-col items-center justify-center p-4 text-center">
                <div className="mb-3 h-1 w-8 bg-accent" />
                <p className="font-display text-base leading-tight break-words line-clamp-4">
                  {book.title}
                </p>
                <p className="kicker mt-auto pt-4">{totalChapters} ch</p>
              </div>
            )}
          </div>
        </div>

        {/* Continue directly under the cover */}
        <div className="mx-auto mt-4 w-full max-w-[16rem]">
          <Link href={`/read/${book.id}/${resumeAt}`} className="block">
            <Button className="w-full">
              {hasStarted ? <Play size={15} /> : <BookOpen size={15} />}
              {hasStarted ? "Continue" : "Read"}
            </Button>
          </Link>
        </div>

        {/* Secondary actions */}
        <div className="mt-3 flex flex-wrap items-center justify-center gap-2">
          {book.can_update && (
            <Button
              variant="outline"
              size="sm"
              disabled={updateChapters.isPending}
              onClick={() => {
                setUpdateMsg("Checking for new chapters — see Progress.");
                updateChapters.mutate(undefined, {
                  onError: (e) => {
                    if (!/already running/i.test((e as Error).message)) {
                      setUpdateMsg("Couldn't start an update. Please try again.");
                    }
                  },
                });
              }}
            >
              <RefreshCw
                size={15}
                className={cn(updateChapters.isPending && "animate-spin")}
              />
              Update
            </Button>
          )}
          {book.imported && <AddEpubButton id={book.id} />}
          <a href={downloadAllUrl(book.id)}>
            <Button variant="outline" size="sm">
              <DownloadCloud size={15} /> All
            </Button>
          </a>
          <Button variant="danger" size="sm" onClick={onDelete}>
            <Trash2 size={15} /> Delete
          </Button>
        </div>

        {/* Chapters preview / Downloading — swipeable tabs */}
        <SwipeTabs
          className="mt-8"
          tabs={[
            {
              label: "Chapters",
              content: !chapters ? (
                <div className="space-y-2 pt-4">
                  {Array.from({ length: 5 }).map((_, i) => (
                    <Skeleton key={i} className="h-9" />
                  ))}
                </div>
              ) : (
                <div className="pt-4">
                  <div className="relative">
                    <Card className="divide-y divide-border">
                      {chapters.slice(0, 5).map((ch) => {
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
                              href={`/read/${book.id}/${ch.position}`}
                              className="flex min-w-0 flex-1 items-center gap-3 px-4 py-2.5 text-sm"
                            >
                              <span className="tabular w-8 shrink-0 text-xs text-muted-foreground/60">
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
                              onClick={(e) => onToggle(e, ch.position, read)}
                              className="shrink-0 px-3 py-2.5 text-muted-foreground transition-colors hover:text-accent"
                              aria-label={read ? "Mark as unread" : "Mark as read"}
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
                    </Card>
                    {chapters.length > 5 && (
                      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-14 rounded-b-md bg-gradient-to-t from-card to-transparent" />
                    )}
                  </div>
                  <Button
                    variant="outline"
                    className="mt-3 w-full"
                    onClick={() => setSheetOpen(true)}
                  >
                    View all chapters{" "}
                    <span className="text-muted-foreground">({totalChapters})</span>
                    <ChevronRight size={15} />
                  </Button>
                </div>
              ),
            },
            {
              label: "Downloading",
              content: (
                <div className="space-y-2 pt-4">
                  {book.volumes.map((vol) => (
                    <a
                      key={vol.id}
                      href={downloadUrl(book.id, vol.number)}
                      download
                      className="flex items-center justify-between rounded-sm border border-border px-3 py-2.5 text-sm transition-colors hover:border-accent"
                    >
                      <span>
                        Vol {vol.number}{" "}
                        <span className="kicker">· {vol.chapter_count} ch</span>
                      </span>
                      <Download size={15} className="text-muted-foreground" />
                    </a>
                  ))}
                </div>
              ),
            },
          ]}
        />
      </div>

      {/* ---- Desktop layout ---- */}
      <section className="hidden gap-x-10 gap-y-8 md:grid md:grid-cols-[1fr_2fr] md:items-start">
        {/* Cover — desktop: top of the left column. */}
        <div className="aspect-[2/3] overflow-hidden border border-border bg-card md:col-start-1 md:row-start-1">
          {book.has_cover ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={coverUrl(book.id, 800)}
              alt={`Cover of ${book.title}`}
              decoding="async"
              className="h-full w-full object-cover"
            />
          ) : (
            <div className="flex h-full flex-col items-center justify-center p-8 text-center">
              <div className="mb-5 h-1 w-12 bg-accent" />
              <p className="font-display text-2xl leading-tight break-words line-clamp-5">
                {book.title}
              </p>
              <p className="mt-3 text-sm text-muted-foreground">{book.author}</p>
              <p className="kicker mt-auto pt-6">{totalChapters} chapters</p>
            </div>
          )}
        </div>

        {/* Chapters — on mobile these come right after the cover (before the
            volume downloads); on desktop they fill the right column. */}
        <div className="min-w-0 md:col-start-2 md:row-start-1 md:row-span-2">
          <div className="rule-accent flex items-baseline justify-between pt-3 mb-1.5">
            <h2 className="font-display text-2xl">Chapters</h2>
            <span className="kicker">{totalChapters} total</span>
          </div>
          <p className="mb-4 text-xs text-muted-foreground">
            Shift-click a mark to set a range · right-click a chapter for more
          </p>

          {!chapters ? (
            <div className="space-y-2">
              {Array.from({ length: 8 }).map((_, i) => (
                <Skeleton key={i} className="h-9" />
              ))}
            </div>
          ) : (
            <Card className="max-h-[32rem] overflow-y-auto divide-y divide-border">
              {chapters.map((ch) => {
                const read = readSet.has(ch.position);
                const current = ch.position === resumeAt;
                return (
                  <div
                    key={ch.position}
                    onContextMenu={(e) => {
                      e.preventDefault();
                      setMenu({ x: e.clientX, y: e.clientY, position: ch.position });
                    }}
                    className={cn(
                      "flex items-center transition-colors hover:bg-muted/60",
                      current && "bg-accent-soft"
                    )}
                  >
                    <Link
                      href={`/read/${book.id}/${ch.position}`}
                      className="flex flex-1 items-center gap-3 px-4 py-2.5 text-sm min-w-0"
                    >
                      <span className="tabular w-8 shrink-0 text-xs text-muted-foreground/60">
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
                      onClick={(e) => onToggle(e, ch.position, read)}
                      className="shrink-0 px-3 py-2.5 text-muted-foreground hover:text-accent transition-colors"
                      aria-label={read ? "Mark as unread" : "Mark as read"}
                      title={
                        read
                          ? "Mark as unread (Shift-click for range)"
                          : "Mark as read (Shift-click for range)"
                      }
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
            </Card>
          )}
        </div>

        {/* Download volumes — desktop: below the cover; mobile: after chapters. */}
        <div className="min-w-0 md:col-start-1 md:row-start-2">
          <p className="kicker mb-2">Download volumes</p>
          <div className="space-y-2">
            {book.volumes.map((vol) => (
              <a
                key={vol.id}
                href={downloadUrl(book.id, vol.number)}
                download
                className="flex items-center justify-between rounded-sm border border-border px-3 py-2 text-sm transition-colors hover:border-accent"
              >
                <span>
                  Vol {vol.number}{" "}
                  <span className="kicker">· {vol.chapter_count} ch</span>
                </span>
                <Download size={15} className="text-muted-foreground" />
              </a>
            ))}
          </div>
        </div>
      </section>

      {menu && (
        <ChapterMenu
          menu={menu}
          positions={orderedPositions}
          onApply={applyRead}
          onClose={() => setMenu(null)}
        />
      )}

      <ChaptersSheet
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        bookId={book.id}
        title={book.title}
        hasCover={book.has_cover}
        chapters={chapters ?? []}
        volumes={book.volumes}
        readSet={readSet}
        resumeAt={resumeAt}
        onToggleRead={(pos, read) => applyRead([pos], !read)}
      />
    </>
  );
}

function AddEpubButton({ id }: { id: number }) {
  const addEpubs = useAddEpubs(id);
  const inputRef = useRef<HTMLInputElement>(null);
  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept={EPUB_ACCEPT}
        multiple
        className="hidden"
        onChange={(e) => {
          const files = pickEpubs(e.target.files);
          e.target.value = "";
          if (files.length) addEpubs.mutate(files);
        }}
      />
      <Button
        variant="outline"
        size="sm"
        disabled={addEpubs.isPending}
        onClick={() => inputRef.current?.click()}
        title={
          addEpubs.isError
            ? (addEpubs.error as Error).message
            : "Add more EPUB volumes to this imported novel"
        }
      >
        <BookUp size={15} className={cn(addEpubs.isPending && "animate-pulse")} />
        {addEpubs.isPending ? "Adding…" : "Add EPUB"}
      </Button>
    </>
  );
}

function ChapterMenu({
  menu,
  positions,
  onApply,
  onClose,
}: {
  menu: { x: number; y: number; position: number };
  positions: number[];
  onApply: (positions: number[], read: boolean) => void;
  onClose: () => void;
}) {
  const pos = menu.position;
  const thisAndAbove = positions.filter((p) => p <= pos);
  const others = positions.filter((p) => p !== pos);
  const items = [
    { label: "Mark read — this & above", fn: () => onApply(thisAndAbove, true) },
    { label: "Mark unread — this & above", fn: () => onApply(thisAndAbove, false) },
    { label: "Mark read — all others", fn: () => onApply(others, true) },
    { label: "Mark unread — all others", fn: () => onApply(others, false) },
  ];
  const W = 224;
  const vw = typeof window !== "undefined" ? window.innerWidth : 9999;
  const vh = typeof window !== "undefined" ? window.innerHeight : 9999;

  // Grow-in from the click point.
  const shown = useEnterTransition();

  return (
    <div
      role="menu"
      className={cn(
        "fixed z-[60] w-56 origin-top-left overflow-hidden rounded-md border border-border bg-card py-1 shadow-xl",
        "transition-[opacity,transform] duration-150 ease-out",
        shown ? "scale-100 opacity-100" : "scale-95 opacity-0"
      )}
      style={{ top: Math.min(menu.y, vh - 190), left: Math.min(menu.x, vw - W - 8) }}
      onClick={(e) => e.stopPropagation()}
    >
      <p className="kicker border-b border-border px-3 pb-1.5 pt-1">Chapter {pos}</p>
      <div className="pt-1">
        {items.map((it) => (
          <button
            key={it.label}
            type="button"
            role="menuitem"
            onClick={() => {
              it.fn();
              onClose();
            }}
            className="block w-full px-3 py-1.5 text-left text-sm text-foreground transition-colors hover:bg-muted"
          >
            {it.label}
          </button>
        ))}
      </div>
    </div>
  );
}
