"use client";

import { Check, MoreVertical, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { useConfirm } from "@/components/confirm-dialog";
import { Card } from "@/components/ui/card";
import { coverUrl } from "@/lib/api";
import {
  useCreateCollection,
  useDeleteBook,
  useSetBookCollections,
} from "@/lib/queries";
import type { Book, Collection } from "@/lib/types";
import { cn } from "@/lib/utils";

export function BookCard({
  book,
  collections,
  dragging,
  onDragStart,
  onDragEnter,
  onDragEnd,
}: {
  book: Book;
  collections: Collection[];
  dragging: boolean;
  onDragStart: () => void;
  onDragEnter: () => void;
  onDragEnd: () => void;
}) {
  const setColls = useSetBookCollections();
  const createColl = useCreateCollection();
  const del = useDeleteBook();
  const confirm = useConfirm();

  const [menu, setMenu] = useState(false);
  const [newName, setNewName] = useState("");
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menu) return;
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setMenu(false);
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
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

  return (
    <div
      draggable
      onDragStart={(e) => {
        e.dataTransfer.effectAllowed = "move";
        onDragStart();
      }}
      onDragEnter={onDragEnter}
      onDragOver={(e) => e.preventDefault()}
      onDragEnd={onDragEnd}
      className={cn(
        "group relative transition-opacity",
        dragging && "opacity-40"
      )}
    >
      <Link href={`/book/${book.id}`} draggable={false} className="block min-w-0">
        <Card interactive className="h-full overflow-hidden">
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
            <h2 className="font-display text-lg leading-snug line-clamp-2 break-words transition-colors group-hover:text-accent">
              {book.title}
            </h2>
            <p className="mt-1 text-sm text-muted-foreground line-clamp-1 break-words">
              {book.author}
            </p>
            <p className="kicker mt-3">
              {book.volumes.length} vol{book.volumes.length === 1 ? "" : "s"} · {book.site}
            </p>
          </div>
        </Card>
      </Link>

      <button
        type="button"
        aria-label="Novel options"
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
          ref={menuRef}
          onClick={(e) => e.preventDefault()}
          className="absolute right-2 top-10 z-40 w-56 rounded-md border border-border bg-card p-1 shadow-xl"
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
