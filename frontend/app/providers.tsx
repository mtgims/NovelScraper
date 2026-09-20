"use client";

import { LazyMotion, domMax } from "framer-motion";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { useEffect, useState, type ReactNode } from "react";

import { ConfirmProvider } from "@/components/confirm-dialog";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: { queries: { staleTime: 5_000, retry: 1 } },
      })
  );

  // Register the service worker so the app is installable (PWA). Production only:
  // a dev-mode SW would cache Next's dev assets and cause confusing stale reloads.
  useEffect(() => {
    if (
      process.env.NODE_ENV === "production" &&
      typeof navigator !== "undefined" &&
      "serviceWorker" in navigator
    ) {
      // updateViaCache: "none" → always revalidate sw.js, so a new worker
      // (and its cache purge) rolls out on the next visit instead of being
      // pinned by the HTTP cache.
      navigator.serviceWorker.register("/sw.js", { updateViaCache: "none" }).catch(() => {});
    }
  }, []);

  return (
    <ThemeProvider
      attribute="class"
      defaultTheme="light"
      enableSystem={false}
      themes={["light", "dark", "purple", "blue"]}
    >
      {/* framer-motion's `motion` component statically pulls in every feature
          it has, which put the whole library in the FIRST LOAD of the reader
          and the book page. LazyMotion + the `m` component load the feature
          bundle separately instead (-26.6kB gzip on the reader, -14.8kB on the
          book page).

          `domMax`, NOT `domAnimation`: swipe-tabs.tsx uses framer's built-in
          `drag="x"`, which domAnimation does not include. Dropping to
          domAnimation saves a further 0.2kB and silently breaks the swipe
          gesture — no error, it just stops dragging. Not worth it.

          `strict` makes any remaining `motion.*` throw, so the conversion
          can't be left half-done. */}
      <LazyMotion features={domMax} strict>
        <QueryClientProvider client={queryClient}>
          <ConfirmProvider>{children}</ConfirmProvider>
        </QueryClientProvider>
      </LazyMotion>
    </ThemeProvider>
  );
}
