"use client";

import { Settings } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { useCreateJob, useSites } from "@/lib/queries";

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

export default function NewScrapePage() {
  const router = useRouter();
  const { data: sites } = useSites();
  const createJob = useCreateJob();

  const [url, setUrl] = useState("");
  const [perVolume, setPerVolume] = useState(100);
  // Real numeric defaults (matching the backend) so the number-input spinners
  // increment from the actual value instead of jumping to the minimum.
  const [delay, setDelay] = useState(0.1);
  const [concurrency, setConcurrency] = useState(12);

  // Speed settings popover (cogwheel) — absolutely positioned so it never
  // shifts the form / Start button.
  const [speedOpen, setSpeedOpen] = useState(false);
  const speedRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!speedOpen) return;
    function onDown(e: MouseEvent) {
      if (speedRef.current && !speedRef.current.contains(e.target as Node)) {
        setSpeedOpen(false);
      }
    }
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [speedOpen]);

  const supportedHosts = (sites ?? [])
    .map((s) => hostOf(s.base_url))
    .filter(Boolean);
  const speedCustomized = delay !== 0.1 || concurrency !== 12;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    await createJob.mutateAsync({
      url: url.trim(),
      chapters_per_volume: perVolume,
      delay: Number.isFinite(delay) ? delay : 0.1,
      concurrency: Number.isFinite(concurrency) ? concurrency : 12,
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
            className={cnBtn(speedOpen || speedCustomized)}
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
              helper={
                supportedHosts.length
                  ? `Supported sources: ${supportedHosts.join(", ")}`
                  : "Paste the link to a novel's page from a supported source."
              }
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
    </>
  );
}

function cnBtn(active: boolean): string {
  return [
    "flex h-9 w-9 items-center justify-center rounded-sm transition-colors cursor-pointer",
    active
      ? "text-accent bg-accent-soft"
      : "text-muted-foreground hover:text-foreground hover:bg-muted",
  ].join(" ");
}
