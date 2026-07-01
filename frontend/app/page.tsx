"use client";

import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";
import { Library, Plus } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { BookCard, BookCardView } from "@/components/book-card";
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
  const [activeId, setActiveId] = useState<number | null>(null);
  const dragging = useRef(false);

  const sensors = useSensors(
    // A small drag threshold so a plain click still opens the novel.
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  // Keep local order synced with the server, except mid-drag.
  useEffect(() => {
    if (books && !dragging.current) setOrderIds(books.map((b) => b.id));
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
  const visibleIds = visible.map((b) => b.id);

  const counts = {
    all: books?.length ?? 0,
    byId: (collections ?? []).reduce<Record<number, number>>((acc, c) => {
      acc[c.id] = (books ?? []).filter((b) => b.collection_ids.includes(c.id)).length;
      return acc;
    }, {}),
  };

  const onDragStart = (e: DragStartEvent) => {
    dragging.current = true;
    setActiveId(Number(e.active.id));
  };

  const onDragEnd = (e: DragEndEvent) => {
    dragging.current = false;
    setActiveId(null);
    const { active, over } = e;
    if (!over || active.id === over.id) return;
    const from = visibleIds.indexOf(Number(active.id));
    const to = visibleIds.indexOf(Number(over.id));
    if (from < 0 || to < 0) return;
    // Reorder the visible subset, then weave it back into the full order so
    // novels hidden by the current tab keep their relative positions.
    const newVisible = arrayMove(visibleIds, from, to);
    const visSet = new Set(visibleIds);
    const queue = [...newVisible];
    const newFull = orderIds.map((id) => (visSet.has(id) ? queue.shift()! : id));
    setOrderIds(newFull);
    reorder.mutate(newFull);
  };

  const activeBook = activeId != null ? byId.get(activeId) : undefined;

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
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragStart={onDragStart}
          onDragEnd={onDragEnd}
          onDragCancel={() => {
            dragging.current = false;
            setActiveId(null);
          }}
        >
          <SortableContext items={visibleIds} strategy={rectSortingStrategy}>
            {/* keyed by tab so switching collections replays the fade/slide */}
            <div
              key={String(tab)}
              className="grid animate-fade-in-up gap-5 sm:grid-cols-2 lg:grid-cols-3"
            >
              {visible.map((book) => (
                <BookCard key={book.id} book={book} collections={collections ?? []} />
              ))}
            </div>
          </SortableContext>
          <DragOverlay dropAnimation={{ duration: 200, easing: "cubic-bezier(0.2,0,0,1)" }}>
            {activeBook ? (
              <div className="rotate-2 cursor-grabbing shadow-2xl">
                <BookCardView book={activeBook} />
              </div>
            ) : null}
          </DragOverlay>
        </DndContext>
      )}
    </>
  );
}
