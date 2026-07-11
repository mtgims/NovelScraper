"use client";

import { Check } from "lucide-react";

import { AdminPanel } from "@/components/admin-panel";
import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useMe, useSettings, useUpdateSettings } from "@/lib/queries";
import { cn } from "@/lib/utils";

const INTERVALS: { label: string; hours: number }[] = [
  { label: "Off", hours: 0 },
  { label: "Every 6 hours", hours: 6 },
  { label: "Every 12 hours", hours: 12 },
  { label: "Daily", hours: 24 },
  { label: "Every 3 days", hours: 72 },
  { label: "Weekly", hours: 168 },
];

export default function SettingsPage() {
  const { data: settings, isLoading } = useSettings();
  const { data: me } = useMe();
  const update = useUpdateSettings();
  const current = settings?.auto_update_hours ?? 0;

  return (
    <>
      <PageHeader title="Settings" kicker="preferences" />

      <section className="max-w-xl">
        <div className="rule-accent pt-3">
          <h2 className="font-display text-2xl">Auto-update</h2>
          <p className="mt-1 mb-4 text-sm text-muted-foreground">
            Periodically re-check your novels for new chapters in the background.
            The per-novel “Update” button always works regardless of this setting.
          </p>
        </div>

        {isLoading ? (
          <Skeleton className="h-40" />
        ) : (
          <Card className="divide-y divide-border overflow-hidden">
            {INTERVALS.map((opt) => {
              const active = current === opt.hours;
              return (
                <button
                  key={opt.hours}
                  type="button"
                  disabled={update.isPending}
                  onClick={() => update.mutate({ auto_update_hours: opt.hours })}
                  className={cn(
                    "flex w-full items-center justify-between px-4 py-3 text-left text-sm transition-colors hover:bg-muted/60",
                    active && "bg-accent-soft"
                  )}
                >
                  <span className={cn(active ? "text-foreground" : "text-muted-foreground")}>
                    {opt.label}
                  </span>
                  {active && <Check size={16} className="text-accent" />}
                </button>
              );
            })}
          </Card>
        )}

        <p className="mt-3 text-xs text-muted-foreground">
          Checks run at most every 15 minutes; a novel is only re-scraped once its
          interval has elapsed. Re-scraping reuses the cache, so mostly new chapters
          hit the network.
        </p>
      </section>

      {me?.is_admin && <AdminPanel />}
    </>
  );
}
