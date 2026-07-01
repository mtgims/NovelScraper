"use client";

import { Activity, Trash2, X } from "lucide-react";
import Link from "next/link";

import { useConfirm } from "@/components/confirm-dialog";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  useCancelJob,
  useClearJobs,
  useDeleteJob,
  useJobStream,
  useJobs,
} from "@/lib/queries";
import type { Job } from "@/lib/types";

function JobRow({ job }: { job: Job }) {
  const cancelJob = useCancelJob();
  const deleteJob = useDeleteJob();
  // Live SSE only while running; otherwise fall back to the persisted row.
  const live = useJobStream(job.id, job.status === "running");

  const total = live?.total ?? job.total_chapters;
  const fetched = live?.fetched ?? job.fetched_chapters;
  const phase = live?.phase ?? job.phase;
  const active = job.status === "running" || job.status === "queued";

  return (
    <Card className="p-4">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <p className="font-mono text-sm truncate">{job.book_slug}</p>
          <p className="kicker mt-1">
            {job.site} · {phase}
          </p>
        </div>
        <div className="flex items-center gap-3 shrink-0">
          <StatusBadge status={job.status} />
          {job.status === "running" && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => cancelJob.mutate(job.id)}
              disabled={cancelJob.isPending}
            >
              Cancel
            </Button>
          )}
          {job.status === "completed" && job.book_id && (
            <Link href={`/book/${job.book_id}`}>
              <Button variant="ghost" size="sm">
                View
              </Button>
            </Link>
          )}
          {!active && (
            <button
              type="button"
              onClick={() => deleteJob.mutate(job.id)}
              disabled={deleteJob.isPending}
              aria-label="Delete this job record"
              title="Delete"
              className="text-muted-foreground hover:text-destructive transition-colors disabled:opacity-40"
            >
              <X size={16} />
            </button>
          )}
        </div>
      </div>

      {active && (
        <div className="mt-3">
          <Progress done={fetched} total={total} />
          <p className="mt-1.5 tabular text-xs text-muted-foreground">
            {fetched} / {total || "—"} chapters
            {job.skipped_chapters > 0 && ` · ${job.skipped_chapters} skipped`}
          </p>
        </div>
      )}

      {job.status === "completed" && (
        <p className="mt-2 tabular text-xs text-muted-foreground">
          {job.total_chapters} chapters
          {job.skipped_chapters > 0 && ` · ${job.skipped_chapters} skipped`}
        </p>
      )}

      {job.status === "failed" && job.error && (
        <p className="mt-2 text-xs text-destructive">{job.error}</p>
      )}
    </Card>
  );
}

export default function JobsPage() {
  const { data: jobs, isLoading, isError } = useJobs();
  const clearJobs = useClearJobs();
  const confirm = useConfirm();

  const finishedCount = (jobs ?? []).filter(
    (j) => j.status !== "running" && j.status !== "queued"
  ).length;

  return (
    <>
      <PageHeader title="Progress" kicker="scrape jobs">
        {finishedCount > 0 && (
          <Button
            variant="outline"
            size="sm"
            disabled={clearJobs.isPending}
            onClick={async () => {
              const ok = await confirm({
                title: "Clear finished jobs?",
                message: `This removes ${finishedCount} finished job record(s) from the list.`,
                confirmLabel: "Clear",
              });
              if (ok) clearJobs.mutate();
            }}
          >
            <Trash2 size={15} /> Clear finished
          </Button>
        )}
      </PageHeader>

      {isLoading && (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-20" />
          ))}
        </div>
      )}
      {isError && <p className="text-destructive">Could not reach the backend.</p>}

      {jobs && jobs.length === 0 && (
        <EmptyState
          icon={Activity}
          title="No jobs yet"
          description="Start a scrape to see its progress here."
        >
          <Link href="/new">
            <Button>New Scrape</Button>
          </Link>
        </EmptyState>
      )}

      <div className="space-y-3">
        {jobs?.map((job) => (
          <JobRow key={job.id} job={job} />
        ))}
      </div>
    </>
  );
}
