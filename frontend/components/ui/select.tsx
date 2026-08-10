"use client";

import { Check, ChevronsUpDown } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { useDismiss, useEnterTransition } from "@/lib/hooks";
import { cn } from "@/lib/utils";

export type SelectOption = { value: string; label: string };

/** Themed drop-down replacing the native <select> (whose option list is drawn by
 *  the OS and can't be styled). Trigger button + a portaled, scrollable popover
 *  that matches the app: current option highlighted and scrolled into view. */
export function Select({
  value,
  options,
  onChange,
  className,
  "aria-label": ariaLabel,
}: {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  className?: string;
  "aria-label"?: string;
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null);
  const [mounted, setMounted] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => setMounted(true), []);
  useDismiss(open, () => setOpen(false), { refs: [triggerRef, menuRef] });
  const shown = useEnterTransition(open);

  const selected = options.find((o) => o.value === value);

  const toggle = () => {
    if (!open && triggerRef.current) {
      const r = triggerRef.current.getBoundingClientRect();
      setPos({ top: r.bottom + 4, left: r.left, width: r.width });
    }
    setOpen((o) => !o);
  };

  // Once open: keep the menu on-screen and scroll the current option into view.
  useLayoutEffect(() => {
    if (!open || !menuRef.current || !pos) return;
    const m = menuRef.current.getBoundingClientRect();
    const vw = window.innerWidth;
    let left = pos.left;
    if (left + m.width > vw - 8) left = Math.max(8, vw - 8 - m.width);
    if (Math.abs(left - pos.left) > 0.5) setPos({ ...pos, left });
    const cur = menuRef.current.querySelector('[data-selected="true"]') as HTMLElement | null;
    cur?.scrollIntoView({ block: "center" });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={toggle}
        className={cn(
          "inline-flex items-center gap-1 rounded-sm border border-border bg-background px-2 py-1 text-xs text-foreground outline-none transition-colors hover:border-accent focus:border-accent",
          className
        )}
      >
        <span className="min-w-0 flex-1 truncate text-left">{selected?.label ?? ""}</span>
        <ChevronsUpDown size={13} className="shrink-0 text-muted-foreground" />
      </button>

      {mounted &&
        open &&
        pos &&
        createPortal(
          <div
            ref={menuRef}
            role="listbox"
            className={cn(
              "fixed z-[70] max-h-[60vh] overflow-y-auto overscroll-contain rounded-md border border-border bg-card py-1 shadow-xl",
              "origin-top transition-[opacity,transform] duration-150 ease-out",
              shown ? "scale-100 opacity-100" : "scale-95 opacity-0"
            )}
            style={{
              top: pos.top,
              left: pos.left,
              minWidth: Math.max(pos.width, 180),
              maxWidth: "min(92vw, 22rem)",
            }}
          >
            {options.map((o) => {
              const isSel = o.value === value;
              return (
                <button
                  key={o.value}
                  type="button"
                  role="option"
                  aria-selected={isSel}
                  data-selected={isSel}
                  onClick={() => {
                    onChange(o.value);
                    setOpen(false);
                  }}
                  className={cn(
                    "flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm transition-colors hover:bg-muted",
                    isSel ? "text-accent" : "text-foreground"
                  )}
                >
                  <Check
                    size={14}
                    className={cn("shrink-0", isSel ? "text-accent opacity-100" : "opacity-0")}
                  />
                  <span className="min-w-0 flex-1 truncate">{o.label}</span>
                </button>
              );
            })}
          </div>,
          document.body
        )}
    </>
  );
}
