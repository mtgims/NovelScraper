"use client";

import { useEffect, useRef, useState, type RefObject } from "react";

/**
 * Drive an enter ("grow-in") transition for a popover/menu/dialog. Returns a
 * `shown` flag that flips to true on the frame after `active` becomes true, so a
 * CSS transition from the initial (scaled/faded) state actually animates. Flips
 * back to false when `active` is false.
 *
 * Replaces the hand-rolled `useState(false)` + `requestAnimationFrame` effect
 * that was duplicated across the confirm dialog and every floating menu.
 */
export function useEnterTransition(active = true): boolean {
  const [shown, setShown] = useState(false);
  useEffect(() => {
    if (!active) {
      setShown(false);
      return;
    }
    const id = requestAnimationFrame(() => setShown(true));
    return () => cancelAnimationFrame(id);
  }, [active]);
  return shown;
}

type DismissOptions = {
  /** Pointer events inside any of these elements do NOT dismiss. */
  refs?: RefObject<HTMLElement | null>[];
  /** Dismiss on Escape (default: true). */
  escape?: boolean;
  /** Dismiss on any scroll (capture phase) — e.g. anchored context menus. */
  closeOnScroll?: boolean;
  /** Which pointer event closes it (default: "mousedown"). */
  pointerEvent?: "mousedown" | "click";
};

/**
 * Dismiss an open overlay on an outside pointer press, Escape, and optionally
 * scroll. `onDismiss` and `refs` are read through refs, so passing fresh
 * closures/array literals each render is fine — listeners only (re)bind when
 * `active` or a primitive option changes.
 *
 * Replaces three near-identical outside-click effects (book card menu, chapter
 * context menu, new-scrape speed popover).
 */
export function useDismiss(
  active: boolean,
  onDismiss: () => void,
  options: DismissOptions = {}
): void {
  const { escape = true, closeOnScroll = false, pointerEvent = "mousedown" } = options;
  const onDismissRef = useRef(onDismiss);
  onDismissRef.current = onDismiss;
  const refsRef = useRef(options.refs);
  refsRef.current = options.refs;

  useEffect(() => {
    if (!active) return;
    const dismiss = () => onDismissRef.current();
    const onPointer = (e: MouseEvent) => {
      const target = e.target as Node;
      if (refsRef.current?.some((r) => r.current?.contains(target))) return;
      dismiss();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") dismiss();
    };
    document.addEventListener(pointerEvent, onPointer);
    if (escape) window.addEventListener("keydown", onKey);
    if (closeOnScroll) window.addEventListener("scroll", dismiss, true);
    return () => {
      document.removeEventListener(pointerEvent, onPointer);
      if (escape) window.removeEventListener("keydown", onKey);
      if (closeOnScroll) window.removeEventListener("scroll", dismiss, true);
    };
  }, [active, escape, closeOnScroll, pointerEvent]);
}
