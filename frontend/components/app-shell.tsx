"use client";

import {
  Activity,
  BarChart3,
  BookMarked,
  Library,
  PlusSquare,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { ThemePicker } from "@/components/theme-picker";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/", label: "Library", icon: Library, exact: true },
  { href: "/new", label: "New Scrape", icon: PlusSquare },
  { href: "/jobs", label: "Progress", icon: Activity },
  { href: "/stats", label: "Statistics", icon: BarChart3 },
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

  return (
    <div className="min-h-dvh md:grid md:grid-cols-[16rem_1fr]">
      <aside className="flex flex-col border-b md:border-b-0 md:border-r border-border md:h-dvh md:sticky md:top-0">
        <div className="px-5 py-6 border-b border-border">
          <Link href="/" className="flex items-center gap-2">
            <BookMarked size={20} className="text-accent" />
            <span className="font-display text-xl tracking-tight">
              NovelScraper
            </span>
          </Link>
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
      </aside>

      <main className="min-w-0">
        <div className="mx-auto max-w-5xl px-6 py-10 md:px-12 md:py-14">
          {children}
        </div>
      </main>
    </div>
  );
}
