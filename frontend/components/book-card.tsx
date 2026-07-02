"use client";

import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Check, MoreVertical, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { useConfirm } from "@/components/confirm-dialog";
import { StarRating } from "@/components/star-rating";
import { Card } from "@/components/ui/card";
import { coverUrl } from "@/lib/api";
import {
  useCreateCollection,
  useDeleteBook,
  useSetBookCollections,
} from "@/lib/queries";
import type { Book, Collection } from "@/lib/types";
import { cn } from "@/lib/utils";

/** Purely visual card — reused by the sortable item and the drag overlay. */
export function BookCardView({
  book,
  className,
}: {
  book: Book;
  className?: string;
}) {
  return (
    <Card interactive className={cn("h-full overflow-hidden", className)}>
      <div className="relative aspect-[3/4] bg-muted">
        {book.has_cover ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={coverUrl(book.id)}
            alt={`Cover of ${book.title}`}
            draggable={false}
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
        {/* Fixed 2-line title height so short and long titles take the same
            space and every card is the same size. */}
        <h2 className="min-h-[3.1rem] font-display text-lg leading-snug line-clamp-2 break-words">
          {book.title}
        </h2>
        <p className="mt-1 text-sm text-muted-foreground line-clamp-1 break-words">
          {book.author}
        </p>
        <p className="kicker mt-3">
          {book.volumes.length} vol{book.volumes.length === 1 ? "" : "s"} · {book.site}
        </p>
        {/* Row is always reserved so rated and unrated cards match in height. */}
        <div className="mt-2 h-4">
          {book.rating ? (
            <StarRating value={book.rating} readOnly size={13} />
          ) : null}
        </div>
      </div>
    </Card>
  );
}

/** Sortable, interactive library card: click to open, drag to reorder, ⋮ menu
 *  to assign collections / delete. */
export function BookCard({
  book,
  collections,
}: {
  book: Book;
  collections: Collection[];
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: book.id });

  const setColls = useSetBookCollections();
  const createColl = useCreateCollection();
  const del = useDeleteBook();
  const confirm = useConfirm();

  const [menu, setMenu] = useState(false);
  const [shown, setShown] = useState(false); // popover enter animation
  const [newName, setNewName] = useState("");
  const cardRef = useRef<HTMLDivElement | null>(null);
  // Compose dnd-kit's node ref with our own so we can measure the card.
  const setRefs = (el: HTMLDivElement | null) => {
    setNodeRef(el);
    cardRef.current = el;
  };

  useEffect(() => {
    if (!menu) return;
    const onDown = (e: MouseEvent) => {
      // Ignore any interaction with THIS card — the popover, the ⋮ button, and
      // a right-click that (re)opens the menu all live inside it, so the
      // outside-handler never closes-then-reopens. Clicking a different card or
      // elsewhere on the page still closes it.
      if (cardRef.current?.contains(e.target as Node)) return;
      setMenu(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setMenu(false);
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [menu]);

  // Grow-in the popover when it opens.
  useEffect(() => {
    if (!menu) return void setShown(false);
    const id = requestAnimationFrame(() => setShown(true));
    return () => cancelAnimationFrame(id);
  }, [menu]);

  const inSet = new Set(book.collection_ids);
  const toggle = (id: number) =>
    setColls.mutate({
      id: book.id,
      collectionIds: inSet.has(id)
        ? book.collection_ids.filter((x) => x !== id)
        : [...book.collection_ids, id],
    });

  const addNew = async () => {
    const name = newName.trim();
    if (!name) return;
    setNewName("");
    const c = await createColl.mutateAsync(name);
    setColls.mutate({ id: book.id, collectionIds: [...book.collection_ids, c.id] });
  };

  const remove = async () => {
    setMenu(false);
    const ok = await confirm({
      title: "Delete this novel?",
      message: `“${book.title}” and its downloaded files will be removed. This can't be undone.`,
      confirmLabel: "Delete",
      danger: true,
    });
    if (ok) del.mutate(book.id);
  };

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setRefs}
      style={style}
      {...attributes}
      {...listeners}
      onContextMenu={(e) => {
        // Right-click opens the same options menu as the ⋮ button.
        e.preventDefault();
        setMenu(true);
      }}
      className={cn(
        // select-none: the card is a drag handle, so never let a fast
        // press-and-drag start a text selection instead of a drag.
        "group relative touch-none select-none",
        // While dragging, this stays as a dimmed placeholder; the DragOverlay
        // renders the lifted card that follows the cursor.
        isDragging && "opacity-40"
      )}
    >
      <Link href={`/book/${book.id}`} draggable={false} className="block min-w-0">
        <BookCardView
          book={book}
          className="transition-colors group-hover:[&_h2]:text-accent"
        />
      </Link>

      <button
        type="button"
        aria-label="Novel options"
        onPointerDown={(e) => e.stopPropagation()}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setMenu((v) => !v);
        }}
        className={cn(
          "absolute right-2 top-2 rounded-md bg-background/85 p-1 text-muted-foreground shadow-sm backdrop-blur transition-opacity hover:text-foreground",
          menu ? "opacity-100" : "opacity-0 focus-visible:opacity-100 group-hover:opacity-100"
        )}
      >
        <MoreVertical size={16} />
      </button>

      {menu && (
        <div
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => e.preventDefault()}
          className={cn(
            "absolute right-2 top-10 z-40 w-56 origin-top-right select-text rounded-md border border-border bg-card p-1 shadow-xl",
            "transition-[opacity,transform] duration-150 ease-out",
            shown ? "scale-100 opacity-100" : "scale-95 opacity-0"
          )}
        >
          <p className="kicker px-2 pb-1 pt-1.5">Collections</p>
          <div className="max-h-44 overflow-y-auto">
            {collections.length === 0 ? (
              <p className="px-2 py-1 text-xs text-muted-foreground">No collections yet</p>
            ) : (
              collections.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => toggle(c.id)}
                  className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-muted"
                >
                  <span
                    className={cn(
                      "flex h-4 w-4 shrink-0 items-center justify-center rounded border",
                      inSet.has(c.id)
                        ? "border-accent bg-accent text-accent-foreground"
                        : "border-border"
                    )}
                  >
                    {inSet.has(c.id) && <Check size={12} />}
                  </span>
                  <span className="truncate">{c.name}</span>
                </button>
              ))
            )}
          </div>
          <div className="flex items-center gap-1 px-1 py-1">
            <input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  addNew();
                }
              }}
              placeholder="New collection…"
              className="min-w-0 flex-1 rounded-sm border border-border bg-background px-2 py-1 text-xs outline-none"
            />
            <button
              type="button"
              onClick={addNew}
              aria-label="Create collection"
              className="rounded-sm p-1 text-muted-foreground hover:text-accent"
            >
              <Plus size={14} />
            </button>
          </div>
          <div className="my-1 border-t border-border" />
          <button
            type="button"
            onClick={remove}
            className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm text-destructive hover:bg-destructive/10"
          >
            <Trash2 size={14} /> Delete novel
          </button>
        </div>
      )}
    </div>
  );
}
