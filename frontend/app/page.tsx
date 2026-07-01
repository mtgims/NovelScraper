"use client";

import { Library, Plus } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { BookCard } from "@/components/book-card";
import { CollectionTabs, type TabValue } from "@/components/collection-tabs";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { BookCardSkeleton } from "@/components/ui/skeleton";
import { useBooks, useCollections, useReorderBooks } from "@/lib/queries";
import type { Book } from "@/lib/types";

export default function LibraryPage() {
  const { data: books, isLoading, isError } = useBooks();
  const { data: collections } = useCollections();
  const reorder = useReorderBooks();

  const [tab, setTab] = useState<TabValue>("all");
  const [orderIds, setOrderIds] = useState<number[]>([]);
  const [dragId, setDragId] = useState<number | null>(null);
  const draggingId = useRef<number | null>(null);
  const orderRef = useRef<number[]>([]);
  orderRef.current = orderIds;

  // Keep local order synced with the server, except mid-drag.
  useEffect(() => {
    if (books && draggingId.current == null) setOrderIds(books.map((b) => b.id));
  }, [books]);

  // If the active collection is deleted, fall back to All.
  useEffect(() => {
    if (tab !== "all" && collections && !collections.some((c) => c.id === tab)) {
      setTab("all");
    }
  }, [collections, tab]);

  const byId = new Map((books ?? []).map((b) => [b.id, b]));
  const ordered = orderIds
    .map((id) => byId.get(id))
    .filter((b): b is Book => b != null);
  const visible =
    tab === "all" ? ordered : ordered.filter((b) => b.collection_ids.includes(tab));

  const counts = {
    all: books?.length ?? 0,
    byId: (collections ?? []).reduce<Record<number, number>>((acc, c) => {
      acc[c.id] = (books ?? []).filter((b) => b.collection_ids.includes(c.id)).length;
      return acc;
    }, {}),
  };

  const move = (overId: number) => {
    const dId = draggingId.current;
    if (dId == null || dId === overId) return;
    setOrderIds((prev) => {
      const next = prev.filter((x) => x !== dId);
      next.splice(next.indexOf(overId), 0, dId);
      return next;
    });
  };

  const endDrag = () => {
    if (draggingId.current != null) reorder.mutate(orderRef.current);
    draggingId.current = null;
    setDragId(null);
  };

  return (
    <>
      <PageHeader title="Library" kicker="collected volumes">
        <Link href="/new">
          <Button>
            <Plus size={16} /> New Scrape
          </Button>
        </Link>
      </PageHeader>

      {books && books.length > 0 && (
        <CollectionTabs
          collections={collections ?? []}
          active={tab}
          onSelect={setTab}
          counts={counts}
        />
      )}

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

      {books && books.length > 0 && visible.length === 0 && (
        <p className="text-sm text-muted-foreground">
          Nothing here yet. Open a novel’s <span className="font-medium">⋮</span> menu to
          add it to this collection.
        </p>
      )}

      {visible.length > 0 && (
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {visible.map((book) => (
            <BookCard
              key={book.id}
              book={book}
              collections={collections ?? []}
              dragging={dragId === book.id}
              onDragStart={() => {
                draggingId.current = book.id;
                setDragId(book.id);
              }}
              onDragEnter={() => move(book.id)}
              onDragEnd={endDrag}
            />
          ))}
        </div>
      )}
    </>
  );
}
