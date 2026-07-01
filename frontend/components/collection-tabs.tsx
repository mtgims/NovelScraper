"use client";

import { Plus } from "lucide-react";
import { useRef, useState } from "react";

import { useConfirm } from "@/components/confirm-dialog";
import {
  useCreateCollection,
  useDeleteCollection,
  useUpdateCollection,
} from "@/lib/queries";
import type { Collection } from "@/lib/types";
import { cn } from "@/lib/utils";

export type TabValue = number | "all";

/**
 * Horizontal, independently-scrolling collection tabs (Mihon-style categories).
 * - click a tab to filter the library
 * - "+" adds a collection
 * - double-click a collection tab to rename it
 * - right-click a collection tab to delete it (the novels are kept)
 */
export function CollectionTabs({
  collections,
  active,
  onSelect,
  counts,
}: {
  collections: Collection[];
  active: TabValue;
  onSelect: (v: TabValue) => void;
  counts: { all: number; byId: Record<number, number> };
}) {
  const create = useCreateCollection();
  const update = useUpdateCollection();
  const del = useDeleteCollection();
  const confirm = useConfirm();

  const [adding, setAdding] = useState(false);
  const [renamingId, setRenamingId] = useState<number | null>(null);
  const cancelRef = useRef(false);

  const finishAdd = async (value: string) => {
    setAdding(false);
    if (cancelRef.current) return void (cancelRef.current = false);
    const name = value.trim();
    // Create but stay on the current tab, so novels remain visible to add.
    if (name) await create.mutateAsync(name);
  };

  const finishRename = async (id: number, value: string) => {
    setRenamingId(null);
    if (cancelRef.current) return void (cancelRef.current = false);
    const name = value.trim();
    if (name) await update.mutateAsync({ id, name });
  };

  const removeCollection = async (c: Collection) => {
    const ok = await confirm({
      title: `Delete “${c.name}”?`,
      message: "The collection is removed. The novels in it are kept.",
      confirmLabel: "Delete",
      danger: true,
    });
    if (!ok) return;
    if (active === c.id) onSelect("all");
    del.mutate(c.id);
  };

  const tabCls = (isActive: boolean) =>
    cn(
      "shrink-0 whitespace-nowrap rounded-full px-3.5 py-1.5 text-sm transition-colors",
      isActive
        ? "bg-foreground text-background"
        : "text-muted-foreground hover:bg-muted hover:text-foreground"
    );

  return (
    <div className="mb-6 flex items-center gap-2 overflow-x-auto pb-2 [scrollbar-width:thin]">
      <button className={tabCls(active === "all")} onClick={() => onSelect("all")}>
        All <span className="tabular ml-0.5 opacity-60">{counts.all}</span>
      </button>

      {collections.map((c) =>
        renamingId === c.id ? (
          <TabInput
            key={c.id}
            initial={c.name}
            onCancel={() => (cancelRef.current = true)}
            onDone={(v) => finishRename(c.id, v)}
          />
        ) : (
          <button
            key={c.id}
            className={tabCls(active === c.id)}
            title="Double-click to rename · right-click to delete"
            onClick={() => onSelect(c.id)}
            onDoubleClick={() => setRenamingId(c.id)}
            onContextMenu={(e) => {
              e.preventDefault();
              removeCollection(c);
            }}
          >
            {c.name}
            <span className="tabular ml-0.5 opacity-60">{counts.byId[c.id] ?? 0}</span>
          </button>
        )
      )}

      {adding ? (
        <TabInput
          initial=""
          placeholder="New collection…"
          onCancel={() => (cancelRef.current = true)}
          onDone={finishAdd}
        />
      ) : (
        <button
          className="shrink-0 rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          onClick={() => setAdding(true)}
          title="New collection"
          aria-label="New collection"
        >
          <Plus size={16} />
        </button>
      )}
    </div>
  );
}

function TabInput({
  initial,
  placeholder,
  onDone,
  onCancel,
}: {
  initial: string;
  placeholder?: string;
  onDone: (value: string) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <input
      autoFocus
      value={value}
      placeholder={placeholder}
      onChange={(e) => setValue(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === "Enter") e.currentTarget.blur();
        if (e.key === "Escape") {
          onCancel();
          e.currentTarget.blur();
        }
      }}
      onBlur={() => onDone(value)}
      className="w-32 shrink-0 rounded-full border border-accent bg-background px-3 py-1 text-sm outline-none"
    />
  );
}
