"use client";

import { BookUp, Settings, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { EPUB_ACCEPT, pickEpubs } from "@/lib/epub";
import { useDismiss } from "@/lib/hooks";
import { useCreateJob, useImportEpubs } from "@/lib/queries";
import { cn } from "@/lib/utils";

// Scrape defaults, matching the backend, so the number-input spinners increment
// from the real value instead of jumping to the minimum.
const DEFAULT_PER_VOLUME = 100;
const DEFAULT_DELAY = 0.1;
const DEFAULT_CONCURRENCY = 12;

export default function NewScrapePage() {
  const router = useRouter();
  const createJob = useCreateJob();

  const [url, setUrl] = useState("");
  const [perVolume, setPerVolume] = useState(DEFAULT_PER_VOLUME);
  const [delay, setDelay] = useState(DEFAULT_DELAY);
  const [concurrency, setConcurrency] = useState(DEFAULT_CONCURRENCY);

  // Speed settings popover (cogwheel) — absolutely positioned so it never
  // shifts the form / Start button.
  const [speedOpen, setSpeedOpen] = useState(false);
  const speedRef = useRef<HTMLDivElement>(null);
  useDismiss(speedOpen, () => setSpeedOpen(false), {
    refs: [speedRef],
    escape: false,
  });

  const speedCustomized =
    delay !== DEFAULT_DELAY || concurrency !== DEFAULT_CONCURRENCY;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    await createJob.mutateAsync({
      url: url.trim(),
      chapters_per_volume: perVolume,
      delay: Number.isFinite(delay) ? delay : DEFAULT_DELAY,
      concurrency: Number.isFinite(concurrency) ? concurrency : DEFAULT_CONCURRENCY,
    });
    router.push("/jobs");
  }

  return (
    <>
      <PageHeader title="New Scrape" kicker="paste a link" />

      <Card className="relative max-w-xl">
        {/* Cogwheel — speed settings */}
        <div className="absolute right-3 top-3" ref={speedRef}>
          <button
            type="button"
            onClick={() => setSpeedOpen((v) => !v)}
            aria-label="Speed settings"
            aria-expanded={speedOpen}
            className={cn(
              "flex h-9 w-9 items-center justify-center rounded-sm transition-colors cursor-pointer",
              speedOpen || speedCustomized
                ? "text-accent bg-accent-soft"
                : "text-muted-foreground hover:text-foreground hover:bg-muted"
            )}
          >
            <Settings size={16} />
          </button>

          {speedOpen && (
            <div className="absolute right-0 top-11 z-20 w-64 rounded-sm border border-border bg-card p-4 shadow-lg">
              <p className="kicker mb-3">Speed</p>
              <div className="space-y-4">
                <Field
                  label="Delay (s)"
                  htmlFor="delay"
                  helper="Per-request spacing. 0 = fastest."
                >
                  <Input
                    id="delay"
                    type="number"
                    step="0.1"
                    min={0}
                    className="font-mono"
                    value={Number.isNaN(delay) ? "" : delay}
                    onChange={(e) =>
                      setDelay(e.target.value === "" ? NaN : Number(e.target.value))
                    }
                  />
                </Field>
                <Field
                  label="Concurrency"
                  htmlFor="concurrency"
                  helper="Parallel requests. Higher = faster."
                >
                  <Input
                    id="concurrency"
                    type="number"
                    min={1}
                    className="font-mono"
                    value={Number.isNaN(concurrency) ? "" : concurrency}
                    onChange={(e) =>
                      setConcurrency(
                        e.target.value === "" ? NaN : Number(e.target.value)
                      )
                    }
                  />
                </Field>
              </div>
            </div>
          )}
        </div>

        <CardContent className="pt-6">
          <form onSubmit={onSubmit} className="space-y-5">
            <Field
              label="Novel URL"
              htmlFor="url"
              helper="Paste the link to a novel's page from a supported source."
            >
              <Input
                id="url"
                type="url"
                inputMode="url"
                className="font-mono"
                placeholder="https://example.com/book/the-novel"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                required
              />
            </Field>

            <Field label="Chapters per volume" htmlFor="perVolume">
              <Input
                id="perVolume"
                type="number"
                min={1}
                className="font-mono"
                value={perVolume}
                onChange={(e) => setPerVolume(Number(e.target.value))}
              />
            </Field>

            {createJob.isError && (
              <p className="text-sm text-destructive" role="alert">
                {(createJob.error as Error).message}
              </p>
            )}

            <Button
              type="submit"
              size="lg"
              disabled={createJob.isPending || !url.trim()}
            >
              {createJob.isPending ? "Starting…" : "Start scrape"}
            </Button>
          </form>
        </CardContent>
      </Card>

      <ImportCard />
    </>
  );
}

function ImportCard() {
  const router = useRouter();
  const importEpubs = useImportEpubs();
  const [files, setFiles] = useState<File[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  function addFiles(list: FileList | null) {
    const picked = pickEpubs(list);
    if (!picked.length) return;
    // De-dupe by name+size so re-picking the same file doesn't double it.
    setFiles((prev) => {
      const seen = new Set(prev.map((f) => `${f.name}:${f.size}`));
      return [...prev, ...picked.filter((f) => !seen.has(`${f.name}:${f.size}`))];
    });
  }

  async function onImport() {
    if (!files.length) return;
    const book = await importEpubs.mutateAsync(files);
    router.push(`/book/${book.id}`);
  }

  return (
    <Card className="mt-6 max-w-xl">
      <CardContent className="pt-6">
        <p className="kicker mb-1">Import EPUB</p>
        <p className="mb-4 text-sm text-muted-foreground">
          Read your own EPUBs here. Title, author and cover are read from the
          first file; each file becomes a volume. Add more later from the book
          page.
        </p>

        <input
          ref={inputRef}
          type="file"
          accept={EPUB_ACCEPT}
          multiple
          className="hidden"
          onChange={(e) => {
            addFiles(e.target.files);
            e.target.value = ""; // allow re-selecting the same file
          }}
        />

        <div className="space-y-4">
          <Button
            type="button"
            variant="outline"
            onClick={() => inputRef.current?.click()}
          >
            <BookUp size={16} className="mr-2" />
            Choose EPUB file(s)
          </Button>

          {files.length > 0 && (
            <ul className="space-y-1 text-sm">
              {files.map((f, i) => (
                <li
                  key={`${f.name}:${f.size}`}
                  className="flex items-center justify-between gap-2 rounded-sm bg-muted px-3 py-1.5"
                >
                  <span className="truncate font-mono">{f.name}</span>
                  <button
                    type="button"
                    aria-label={`Remove ${f.name}`}
                    className="shrink-0 text-muted-foreground hover:text-foreground cursor-pointer"
                    onClick={() =>
                      setFiles((prev) => prev.filter((_, j) => j !== i))
                    }
                  >
                    <X size={14} />
                  </button>
                </li>
              ))}
            </ul>
          )}

          {importEpubs.isError && (
            <p className="text-sm text-destructive" role="alert">
              {(importEpubs.error as Error).message}
            </p>
          )}

          <Button
            type="button"
            size="lg"
            disabled={!files.length || importEpubs.isPending}
            onClick={onImport}
          >
            {importEpubs.isPending
              ? "Importing…"
              : `Import ${files.length || ""} EPUB${files.length === 1 ? "" : "s"}`.trim()}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
