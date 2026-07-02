"use client";

import {
  Activity,
  BarChart3,
  BookMarked,
  Library,
  PanelLeft,
  PanelLeftClose,
  PlusSquare,
  Settings,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";

import { ThemePicker } from "@/components/theme-picker";
import { cn } from "@/lib/utils";

const SIDEBAR_KEY = "ns-sidebar-collapsed";

const NAV = [
  { href: "/", label: "Library", icon: Library, exact: true },
  { href: "/new", label: "New Scrape", icon: PlusSquare },
  { href: "/jobs", label: "Progress", icon: Activity },
  { href: "/stats", label: "Statistics", icon: BarChart3 },
  { href: "/settings", label: "Settings", icon: Settings },
];

function NavLink({
  href,
  label,
  icon: Icon,
  active,
}: {
  href: string;
  label: string;
  icon: typeof Library;
  active: boolean;
}) {
  return (
    <Link
      href={href}
      className={cn(
        "group flex items-center gap-3 px-3 py-2 text-sm rounded-sm border-l-2 transition-colors duration-150 ease-out",
        active
          ? "bg-accent-soft text-foreground border-accent"
          : "text-muted-foreground hover:text-foreground hover:bg-muted/60 border-transparent"
      )}
    >
      <Icon
        size={17}
        className={cn(
          active
            ? "text-accent"
            : "text-muted-foreground group-hover:text-foreground"
        )}
      />
      <span>{label}</span>
    </Link>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [collapsed, setCollapsed] = useState(false);
  // `ready` gates the transitions so restoring a collapsed sidebar on load snaps
  // into place instead of animating open→closed on every refresh.
  const [ready, setReady] = useState(false);

  // Persisted across sessions. Read on mount (client-only) to avoid a hydration
  // mismatch; the sidebar shows by default until then.
  useEffect(() => {
    setCollapsed(localStorage.getItem(SIDEBAR_KEY) === "1");
    const id = requestAnimationFrame(() => setReady(true));
    return () => cancelAnimationFrame(id);
  }, []);

  const setSidebar = (next: boolean) => {
    setCollapsed(next);
    localStorage.setItem(SIDEBAR_KEY, next ? "1" : "0");
  };

  return (
    <div
      className={cn(
        "min-h-dvh md:grid",
        ready && "transition-[grid-template-columns] duration-300 ease-in-out",
        collapsed ? "md:grid-cols-[0rem_1fr]" : "md:grid-cols-[16rem_1fr]"
      )}
    >
      {/* Always mounted so the collapse can animate. The column width animates to
          0; the inner keeps a fixed 16rem width and is clipped by overflow-hidden
          so its content doesn't reflow mid-animation. */}
      <aside
        className={cn(
          "overflow-hidden border-border md:sticky md:top-0 md:h-dvh md:border-r",
          collapsed ? "hidden md:flex md:border-r-0" : "flex border-b md:border-b-0"
        )}
      >
        <div
          className={cn(
            "flex w-full flex-col md:w-64",
            collapsed && "pointer-events-none"
          )}
        >
          <div className="flex items-center justify-between gap-2 px-5 py-6 border-b border-border">
            <Link href="/" className="flex min-w-0 items-center gap-2">
              <BookMarked size={20} className="shrink-0 text-accent" />
              <span className="truncate font-display text-xl tracking-tight">
                NovelScraper
              </span>
            </Link>
            <button
              type="button"
              onClick={() => setSidebar(true)}
              aria-label="Hide sidebar"
              title="Hide sidebar"
              className="shrink-0 rounded-sm p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <PanelLeftClose size={18} />
            </button>
          </div>

          <nav className="flex-1 px-3 py-4 space-y-1">
            {NAV.map((item) => (
              <NavLink
                key={item.href}
                {...item}
                active={
                  item.exact
                    ? pathname === item.href
                    : pathname.startsWith(item.href)
                }
              />
            ))}
          </nav>

          <div className="p-4 border-t border-border">
            <ThemePicker />
          </div>
        </div>
      </aside>

      <main className="min-w-0">
        <button
          type="button"
          onClick={() => setSidebar(false)}
          aria-label="Show sidebar"
          title="Show sidebar"
          aria-hidden={!collapsed}
          tabIndex={collapsed ? 0 : -1}
          className={cn(
            "fixed left-3 top-3 z-50 rounded-md border border-border bg-card/95 p-1.5 text-muted-foreground shadow-sm backdrop-blur hover:text-foreground",
            ready && "transition-opacity duration-200",
            collapsed ? "opacity-100 delay-150" : "pointer-events-none opacity-0"
          )}
        >
          <PanelLeft size={18} />
        </button>
        <div
          className={cn(
            "mx-auto max-w-5xl px-6 py-10 md:px-12 md:py-14",
            collapsed && "pt-16 md:pt-14"
          )}
        >
          {children}
        </div>
      </main>
    </div>
  );
}
