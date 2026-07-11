"use client";

import { useQueryClient } from "@tanstack/react-query";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { AppShell } from "@/components/app-shell";
import { setUnauthorizedHandler } from "@/lib/api";
import { useMe } from "@/lib/queries";

// Pages reachable without a session; everything else requires login and is
// wrapped in the app chrome (AppShell).
const PUBLIC_ROUTES = ["/login", "/register"];

export function AuthGate({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const qc = useQueryClient();
  const isPublic = PUBLIC_ROUTES.includes(pathname);

  // Any 401 (session missing/expired mid-use) drops the cached user, so the
  // gate re-evaluates and bounces to /login.
  useEffect(() => {
    setUnauthorizedHandler(() => qc.setQueryData(["me"], null));
    return () => setUnauthorizedHandler(null);
  }, [qc]);

  const { data: me, isLoading } = useMe();

  useEffect(() => {
    if (!isPublic && !isLoading && !me) router.replace("/login");
  }, [isPublic, isLoading, me, router]);

  if (isPublic) return <>{children}</>;
  if (isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center text-sm text-muted-foreground">
        Loading…
      </div>
    );
  }
  if (!me) return null; // redirecting to /login
  return <AppShell>{children}</AppShell>;
}
