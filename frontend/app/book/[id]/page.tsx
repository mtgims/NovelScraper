"use client";

import {
  BookOpen,
  Check,
  CheckCheck,
  ChevronLeft,
  Circle,
  Download,
  DownloadCloud,
  Play,
  RotateCcw,
  Trash2,
} from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";

import { useConfirm } from "@/components/confirm-dialog";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { coverUrl, downloadAllUrl, downloadUrl } from "@/lib/api";
import {
  useBook,
  useChapters,
  useDeleteBook,
  useProgress,
  useUpdateProgress,
} from "@/lib/queries";
import { cn, formatBytes } from "@/lib/utils";

export default function BookDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const router = useRouter();
  const { data: book, isLoading, isError } = useBook(id);
  const { data: chapters } = useChapters(id);
  const { data: progress } = useProgress(id);
  const updateProgress = useUpdateProgress(id);
  const deleteBook = useDeleteBook();
  const confirm = useConfirm();

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

  function toggleRead(position: number, read: boolean) {
    updateProgress.mutate(
      read ? { unmark_read: position } : { mark_read: position }
    );
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
        <div className="flex gap-2">
          <Link href={`/read/${book.id}/${resumeAt}`}>
            <Button size="sm">
              {hasStarted ? <Play size={15} /> : <BookOpen size={15} />}
              {hasStarted ? "Continue" : "Read"}
            </Button>
          </Link>
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

      <section className="grid gap-10 md:grid-cols-[1fr_2fr]">
        {/* Recto: cover + volume downloads */}
        <div className="min-w-0 space-y-6">
          <div className="aspect-[2/3] border border-border bg-card overflow-hidden">
            {book.has_cover ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={coverUrl(book.id)}
                alt={`Cover of ${book.title}`}
                className="h-full w-full object-cover"
              />
            ) : (
              <div className="flex h-full flex-col items-center justify-center p-8 text-center">
                <div className="mb-5 h-1 w-12 bg-accent" />
                <p className="font-display text-2xl leading-tight break-words line-clamp-5">
                  {book.title}
                </p>
                <p className="mt-3 text-sm text-muted-foreground">
                  {book.author}
                </p>
                <p className="kicker mt-auto pt-6">{totalChapters} chapters</p>
              </div>
            )}
          </div>

          <div>
            <p className="kicker mb-2">Download volumes</p>
            <div className="space-y-2">
              {book.volumes.map((vol) => (
                <a
                  key={vol.id}
                  href={downloadUrl(book.id, vol.number)}
                  download
                  className="flex items-center justify-between border border-border rounded-sm px-3 py-2 text-sm hover:border-accent transition-colors"
                >
                  <span>
                    Vol {vol.number}{" "}
                    <span className="kicker">
                      · {formatBytes(vol.size_bytes)}
                    </span>
                  </span>
                  <Download size={15} className="text-muted-foreground" />
                </a>
              ))}
            </div>
          </div>
        </div>

        {/* Verso: chapter index */}
        <div className="min-w-0">
          <div className="rule-accent flex items-baseline justify-between pt-3 mb-4">
            <h2 className="font-display text-2xl">Chapters</h2>
            <span className="kicker">{totalChapters} total</span>
          </div>

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
                      onClick={() => toggleRead(ch.position, read)}
                      className="shrink-0 px-3 py-2.5 text-muted-foreground hover:text-accent transition-colors"
                      aria-label={read ? "Mark as unread" : "Mark as read"}
                      title={read ? "Mark as unread" : "Mark as read"}
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
      </section>
    </>
  );
}
