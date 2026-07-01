"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { Button } from "@/components/ui/button";

export type ConfirmOptions = {
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
};

type Confirm = (opts: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<Confirm>(async () => false);

/** Themed replacement for the native `confirm()`. Usage:
 *   const confirm = useConfirm();
 *   if (await confirm({ title: "…", danger: true })) { … } */
export function useConfirm(): Confirm {
  return useContext(ConfirmContext);
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{
    opts: ConfirmOptions;
    resolve: (result: boolean) => void;
  } | null>(null);

  const confirm = useCallback<Confirm>(
    (opts) =>
      new Promise<boolean>((resolve) => {
        // If one is already open, dismiss it as cancelled first.
        setState((prev) => {
          prev?.resolve(false);
          return { opts, resolve };
        });
      }),
    []
  );

  const settle = useCallback(
    (result: boolean) => {
      setState((prev) => {
        prev?.resolve(result);
        return null;
      });
    },
    []
  );

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {state && (
        <ConfirmDialog
          opts={state.opts}
          onCancel={() => settle(false)}
          onConfirm={() => settle(true)}
        />
      )}
    </ConfirmContext.Provider>
  );
}

function ConfirmDialog({
  opts,
  onCancel,
  onConfirm,
}: {
  opts: ConfirmOptions;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const [mounted, setMounted] = useState(false);
  const [shown, setShown] = useState(false);
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    setMounted(true);
    // next frame → trigger the enter transition
    const id = requestAnimationFrame(() => setShown(true));
    confirmRef.current?.focus();
    return () => cancelAnimationFrame(id);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onCancel]);

  if (!mounted) return null;

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-title"
      className={`fixed inset-0 z-[70] flex items-center justify-center p-4 transition-opacity duration-150 ${
        shown ? "opacity-100" : "opacity-0"
      }`}
      onClick={onCancel}
    >
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />
      <div
        className={`relative w-full max-w-sm rounded-lg border border-border bg-card p-5 shadow-xl transition-all duration-150 ${
          shown ? "scale-100 opacity-100" : "scale-95 opacity-0"
        }`}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="confirm-title" className="font-display text-xl leading-tight">
          {opts.title}
        </h2>
        {opts.message && (
          <p className="mt-2 text-sm text-muted-foreground">{opts.message}</p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={onCancel}>
            {opts.cancelLabel ?? "Cancel"}
          </Button>
          <Button
            ref={confirmRef}
            variant={opts.danger ? "danger" : "primary"}
            size="sm"
            onClick={onConfirm}
          >
            {opts.confirmLabel ?? "Confirm"}
          </Button>
        </div>
      </div>
    </div>,
    document.body
  );
}
