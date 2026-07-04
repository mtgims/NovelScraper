"use client";

import { BarChart3 } from "lucide-react";
import Link from "next/link";

import { PageHeader } from "@/components/page-header";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useStats } from "@/lib/queries";

function Stat({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <Card className="p-5">
      <p className="kicker">{label}</p>
      <p className="mt-2 font-display text-4xl tabular leading-none">{value}</p>
      {sub && <p className="mt-1.5 text-sm text-muted-foreground">{sub}</p>}
    </Card>
  );
}

export default function StatsPage() {
  const { data: stats, isLoading, isError } = useStats();

  return (
    <>
      <PageHeader title="Statistics" kicker="your reading" />

      {isLoading && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-28" />
          ))}
        </div>
      )}
      {isError && (
        <p className="text-destructive">Could not reach the backend.</p>
      )}

      {stats && stats.total_books === 0 && (
        <EmptyState
          icon={BarChart3}
          title="No reading yet"
          description="Scrape a novel and start reading to build up your statistics."
        />
      )}

      {stats && stats.total_books > 0 && (
        <>
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            <Stat
              label="Books"
              value={String(stats.total_books)}
              sub={`${stats.books_finished} finished · ${stats.books_started} started`}
            />
            <Stat
              label="Chapters read"
              value={stats.chapters_read.toLocaleString()}
              sub={`of ${stats.total_chapters.toLocaleString()} · ${stats.percent_read}%`}
            />
            <Stat
              label="Hours read"
              value={`${stats.hours_read}`}
              sub={`~${stats.hours_remaining}h remaining`}
            />
            <Stat
              label="Words read"
              value={
                stats.words_read >= 1000
                  ? `${Math.round(stats.words_read / 1000)}k`
                  : String(stats.words_read)
              }
              sub={`of ${Math.round(stats.total_words / 1000)}k total`}
            />
          </div>

          <div className="mt-10">
            <div className="rule-accent flex items-baseline justify-between pt-3 mb-4">
              <h2 className="font-display text-2xl">By book</h2>
              <span className="kicker">{stats.books.length} total</span>
            </div>
            <div className="space-y-3">
              {stats.books.map((b) => (
                <Link key={b.book_id} href={`/book/${b.book_id}`}>
                  <Card interactive className="p-4">
                    <div className="mb-2 flex items-baseline justify-between gap-4">
                      <span className="min-w-0 truncate font-medium">{b.title}</span>
                      <span className="kicker shrink-0">
                        {b.read_count}/{b.total_chapters} · {b.percent_read}%
                      </span>
                    </div>
                    <Progress done={b.read_count} total={b.total_chapters} />
                  </Card>
                </Link>
              ))}
            </div>
          </div>
        </>
      )}
    </>
  );
}
