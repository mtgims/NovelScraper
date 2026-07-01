"use client";

import { Library, Plus } from "lucide-react";
import Link from "next/link";

import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { BookCardSkeleton } from "@/components/ui/skeleton";
import { coverUrl } from "@/lib/api";
import { useBooks } from "@/lib/queries";

export default function LibraryPage() {
  const { data: books, isLoading, isError } = useBooks();

  return (
    <>
      <PageHeader title="Library" kicker="collected volumes">
        <Link href="/new">
          <Button>
            <Plus size={16} /> New Scrape
          </Button>
        </Link>
      </PageHeader>

      {isLoading && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <BookCardSkeleton key={i} />
          ))}
        </div>
      )}

      {isError && (
        <p className="text-destructive">
          Could not reach the backend. Is the API running on{" "}
          <span className="tabular">:8000</span>?
        </p>
      )}

      {books && books.length === 0 && (
        <EmptyState
          icon={Library}
          title="The shelf is empty"
          description="Scrape your first novel to begin your private press."
        >
          <Link href="/new">
            <Button>Start a scrape</Button>
          </Link>
        </EmptyState>
      )}

      {books && books.length > 0 && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {books.map((book) => (
            <Link key={book.id} href={`/book/${book.id}`} className="min-w-0">
              <Card interactive className="group h-full overflow-hidden">
                <div className="aspect-[3/4] bg-muted relative">
                  {book.has_cover ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={coverUrl(book.id)}
                      alt={`Cover of ${book.title}`}
                      className="h-full w-full object-cover"
                    />
                  ) : (
                    <div className="flex h-full w-full flex-col items-center justify-center p-4 text-center">
                      <div className="mb-3 h-1 w-8 bg-accent" />
                      <span className="font-display text-lg leading-tight line-clamp-4 break-words">
                        {book.title}
                      </span>
                    </div>
                  )}
                </div>
                <div className="min-w-0 p-4">
                  <h2 className="font-display text-lg leading-snug group-hover:text-accent transition-colors line-clamp-2 break-words">
                    {book.title}
                  </h2>
                  <p className="mt-1 text-sm text-muted-foreground line-clamp-1 break-words">
                    {book.author}
                  </p>
                  <p className="mt-3 kicker">
                    {book.volumes.length} vol
                    {book.volumes.length === 1 ? "" : "s"} · {book.site}
                  </p>
                </div>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}
