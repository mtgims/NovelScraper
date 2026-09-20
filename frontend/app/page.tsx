"use client";

import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  MouseSensor,
  TouchSensor,
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
import { cn } from "@/lib/utils";

/**
 * A drag ends with a trailing `click` that the browser fires wherever the
 * pointer landed — which, after a reorder, is often a *different* card than the
 * one dragged. That click would open a novel. Swallow exactly the next click
 * (capture phase, anywhere) so a drop never navigates; the timeout releases it
 * if no click follows.
 */
function suppressNextClick() {
  const handler = (ev: MouseEvent) => {
    ev.preventDefault();
    ev.stopPropagation();
    cleanup();
  };
  const cleanup = () => {
    document.removeEventListener("click", handler, true);
    clearTimeout(timer);
  };
  const timer = window.setTimeout(cleanup, 350);
  document.addEventListener("click", handler, true);
}

export default function LibraryPage() {
  const { data: books, isLoading, isError } = useBooks();
  const { data: collections } = useCollections();
  const reorder = useReorderBooks();

  const [tab, setTab] = useState<TabValue>("all");
  const [slideDir, setSlideDir] = useState<"left" | "right">("right");
  const [orderIds, setOrderIds] = useState<number[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const dragging = useRef(false);

  const sensors = useSensors(
    // Mouse: a small drag threshold so a plain click still opens the novel.
    useSensor(MouseSensor, { activationConstraint: { distance: 8 } }),
    // Touch: press-and-hold to reorder, so a normal swipe still scrolls the
    // library. Moving past the tolerance before the delay cancels the drag.
    useSensor(TouchSensor, { activationConstraint: { delay: 220, tolerance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  // Keep local order synced with the server, except mid-drag. Keep the same
  // array reference when the order is unchanged so we don't needlessly
  // re-register the sortable cards (which would make the next drag feel laggy).
  useEffect(() => {
    if (!books || dragging.current) return;
    const serverOrder = books.map((b) => b.id);
    setOrderIds((prev) =>
      prev.length === serverOrder.length && prev.every((id, i) => id === serverOrder[i])
        ? prev
        : serverOrder
    );
  }, [books]);

  // If the active collection is deleted, fall back to All.
  useEffect(() => {
    if (tab !== "all" && collections && !collections.some((c) => c.id === tab)) {
      setTab("all");
    }
  }, [collections, tab]);

  // Order of the tab bar (All + collections); used to slide the grid in the
  // direction of the tab you pick — enter from the left when moving to a
  // left-of-current collection, from the right when moving right.
  const tabOrder = ["all", ...(collections ?? []).map((c) => String(c.id))];
  const selectTab = (next: TabValue) => {
    const from = tabOrder.indexOf(String(tab));
    const to = tabOrder.indexOf(String(next));
    setSlideDir(to < from ? "left" : "right");
    setTab(next);
  };

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
    suppressNextClick(); // a drag always ends with a trailing click — swallow it
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
          onSelect={selectTab}
          counts={counts}
        />
      )}

      {isLoading && (
        <div className="grid grid-cols-2 gap-3 sm:gap-5 lg:grid-cols-3">
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
            suppressNextClick();
          }}
        >
          <SortableContext items={visibleIds} strategy={rectSortingStrategy}>
            {/* overflow-x-clip contains the slide so it can't add a scrollbar,
                while keeping vertical overflow (card menus) visible. Keyed by
                tab so switching collections replays the directional slide. */}
            <div className="overflow-x-clip">
              <div
                key={String(tab)}
                className={cn(
                  "grid grid-cols-2 gap-3 sm:gap-5 lg:grid-cols-3",
                  slideDir === "left" ? "animate-slide-in-left" : "animate-slide-in-right"
                )}
              >
                {visible.map((book, i) => (
                  <BookCard
                    key={book.id}
                    book={book}
                    index={i}
                    collections={collections ?? []}
                  />
                ))}
              </div>
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
