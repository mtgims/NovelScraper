"use client";

import { useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  BarChart3,
  BookMarked,
  Library,
  LogOut,
  Menu,
  PanelLeft,
  PanelLeftClose,
  PlusSquare,
  Settings,
  ShieldCheck,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";

import { ThemePicker } from "@/components/theme-picker";
import { api } from "@/lib/api";
import { useMe } from "@/lib/queries";
import { cn } from "@/lib/utils";

const SIDEBAR_KEY = "ns-sidebar-collapsed";

const NAV = [
  { href: "/", label: "Library", icon: Library, exact: true },
  { href: "/new", label: "New Scrape", icon: PlusSquare },
  { href: "/jobs", label: "Progress", icon: Activity },
  { href: "/stats", label: "Statistics", icon: BarChart3 },
  { href: "/settings", label: "Settings", icon: Settings },
];

// Shown only to admins (appended after the shared nav).
const ADMIN_NAV = { href: "/admin", label: "Admin", icon: ShieldCheck };

function NavLink({
  href,
  label,
  icon: Icon,
  active,
  onNavigate,
}: {
  href: string;
  label: string;
  icon: typeof Library;
  active: boolean;
  onNavigate?: () => void;
}) {
  return (
    <Link
      href={href}
      onClick={onNavigate}
      className={cn(
        "group flex items-center gap-3 rounded-sm border-l-2 px-3 py-2.5 text-sm transition-colors duration-150 ease-out",
        active
          ? "bg-accent-soft text-foreground border-accent"
          : "text-muted-foreground hover:text-foreground hover:bg-muted/60 border-transparent"
      )}
    >
      <Icon
        size={17}
        className={cn(
          active ? "text-accent" : "text-muted-foreground group-hover:text-foreground"
        )}
      />
      <span>{label}</span>
    </Link>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  // Desktop: collapsible grid sidebar. Mobile: off-canvas drawer.
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [ready, setReady] = useState(false);
  const router = useRouter();
  const qc = useQueryClient();
  const { data: me } = useMe();

  async function logout() {
    try {
      await api.logout();
    } catch {
      /* even if the request fails, drop local state and go to login */
    }
    qc.clear();
    router.replace("/login");
  }

  const inReader = pathname.startsWith("/read/");

  // On navigation: collapse the desktop sidebar inside the reader (else use the
  // saved preference), and always close the mobile drawer.
  useEffect(() => {
    const pref = localStorage.getItem(SIDEBAR_KEY) === "1";
    setCollapsed(inReader || pref);
    setMobileOpen(false);
    const id = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(id);
  }, [inReader, pathname]);

  const setSidebar = (next: boolean) => {
    setCollapsed(next);
    // Don't let expanding/collapsing inside the reader overwrite the user's
    // real sidebar preference for the rest of the app.
    if (!inReader) localStorage.setItem(SIDEBAR_KEY, next ? "1" : "0");
  };

  return (
    <div
      className={cn(
        "min-h-dvh md:grid",
        ready && "md:transition-[grid-template-columns] md:duration-300 md:ease-in-out",
        collapsed ? "md:grid-cols-[0rem_1fr]" : "md:grid-cols-[16rem_1fr]"
      )}
    >
      {/* Mobile drawer backdrop. */}
      <div
        aria-hidden
        onClick={() => setMobileOpen(false)}
        className={cn(
          "fixed inset-0 z-40 bg-black/50 transition-opacity duration-300 md:hidden",
          mobileOpen ? "opacity-100" : "pointer-events-none opacity-0"
        )}
      />

      {/* Sidebar: off-canvas drawer on mobile, sticky grid column on desktop. */}
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] border-r border-border bg-background shadow-xl transition-transform duration-300 ease-in-out",
          mobileOpen ? "translate-x-0" : "-translate-x-full",
          "md:sticky md:top-0 md:z-auto md:h-dvh md:w-auto md:max-w-none md:translate-x-0 md:overflow-hidden md:bg-transparent md:shadow-none md:transition-none",
          collapsed && "md:border-r-0"
        )}
      >
        <div
          className={cn(
            "flex h-full w-full flex-col md:w-64",
            collapsed && "md:pointer-events-none"
          )}
        >
          <div className="flex items-center justify-between gap-2 border-b border-border px-5 py-5 md:py-6">
            <Link
              href="/"
              onClick={() => setMobileOpen(false)}
              className="flex min-w-0 items-center gap-2"
            >
              <BookMarked size={20} className="shrink-0 text-accent" />
              <span className="truncate font-display text-xl tracking-tight">NovelScraper</span>
            </Link>
            {/* Mobile: close drawer. Desktop: collapse sidebar. */}
            <button
              type="button"
              onClick={() => setMobileOpen(false)}
              aria-label="Close menu"
              className="shrink-0 rounded-sm p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground md:hidden"
            >
              <X size={20} />
            </button>
            <button
              type="button"
              onClick={() => setSidebar(true)}
              aria-label="Hide sidebar"
              title="Hide sidebar"
              className="hidden shrink-0 rounded-sm p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground md:inline-flex"
            >
              <PanelLeftClose size={18} />
            </button>
          </div>

          <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
            {(me?.is_admin ? [...NAV, ADMIN_NAV] : NAV).map((item) => (
              <NavLink
                key={item.href}
                {...item}
                active={
                  item.href === "/"
                    ? pathname === "/"
                    : pathname.startsWith(item.href)
                }
                onNavigate={() => setMobileOpen(false)}
              />
            ))}
          </nav>

          <div className="space-y-3 border-t border-border p-4">
            {me && (
              <div className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate text-sm text-muted-foreground">
                  {me.username}
                  {me.is_admin && (
                    <span className="ml-1.5 text-xs text-accent">admin</span>
                  )}
                </span>
                <button
                  type="button"
                  onClick={logout}
                  title="Log out"
                  aria-label="Log out"
                  className="shrink-0 rounded-sm p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                >
                  <LogOut size={17} />
                </button>
              </div>
            )}
            <ThemePicker />
          </div>
        </div>
      </aside>

      <main className="min-w-0">
        {/* Mobile top bar (not in the reader, which has its own toolbar). */}
        {!inReader && (
          <header className="sticky top-0 z-30 flex items-center gap-3 border-b border-border bg-background/90 px-4 py-2.5 backdrop-blur md:hidden">
            <button
              type="button"
              onClick={() => setMobileOpen(true)}
              aria-label="Open menu"
              className="rounded-sm p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <Menu size={22} />
            </button>
            <Link href="/" className="flex items-center gap-2">
              <BookMarked size={18} className="text-accent" />
              <span className="font-display text-lg tracking-tight">NovelScraper</span>
            </Link>
          </header>
        )}

        {/* Desktop: show the collapsed sidebar again. */}
        <button
          type="button"
          onClick={() => setSidebar(false)}
          aria-label="Show sidebar"
          title="Show sidebar"
          aria-hidden={!collapsed}
          tabIndex={collapsed ? 0 : -1}
          className={cn(
            "fixed left-3 top-3 z-30 hidden rounded-md border border-border bg-card/95 p-1.5 text-muted-foreground shadow-sm backdrop-blur hover:text-foreground md:block",
            ready && "transition-opacity duration-200",
            collapsed ? "opacity-100 delay-150" : "pointer-events-none opacity-0"
          )}
        >
          <PanelLeft size={18} />
        </button>

        <div
          className={cn(
            "mx-auto max-w-5xl px-4 py-6 sm:px-6 md:px-12 md:py-14",
            collapsed && "md:pt-16"
          )}
        >
          {children}
        </div>
      </main>
    </div>
  );
}
