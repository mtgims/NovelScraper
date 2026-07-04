import { cn } from "@/lib/utils";
import type { JobStatus } from "@/lib/types";

const STYLES: Record<JobStatus, { wrap: string; dot: string }> = {
  queued: {
    wrap: "border-muted-foreground/40 text-muted-foreground",
    dot: "bg-muted-foreground",
  },
  running: {
    wrap: "border-accent text-accent",
    dot: "bg-accent animate-pulse",
  },
  completed: {
    wrap: "border-foreground/30 text-foreground",
    dot: "bg-foreground",
  },
  failed: {
    wrap: "border-destructive text-destructive",
    dot: "bg-destructive",
  },
  cancelled: {
    wrap: "border-muted-foreground/40 text-muted-foreground",
    dot: "bg-muted-foreground",
  },
};

export function StatusBadge({ status }: { status: JobStatus }) {
  const s = STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 px-2 py-0.5 text-[11px] uppercase tracking-[0.15em] border rounded-sm tabular",
        s.wrap
      )}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full", s.dot)} aria-hidden />
      {status}
    </span>
  );
}
