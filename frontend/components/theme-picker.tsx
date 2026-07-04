"use client";

import { useTheme } from "next-themes";
import { useEffect, useState } from "react";

import { cn } from "@/lib/utils";

export const THEMES = [
  { name: "light", label: "Light", bg: "#ffffff", accent: "#4f46e5", ring: "#e4e4e7" },
  { name: "dark", label: "Dark", bg: "#0a0a0b", accent: "#818cf8", ring: "#2a2a2e" },
  { name: "purple", label: "Purple", bg: "#140f1c", accent: "#a855f7", ring: "#342843" },
  { name: "blue", label: "Blue", bg: "#0a1020", accent: "#3b82f6", ring: "#22314c" },
];

/** The row of theme swatch buttons used by the sidebar picker. */
function ThemeSwatches() {
  const { theme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  return (
    <div className="flex gap-2">
      {THEMES.map((t) => {
        const active = mounted && theme === t.name;
        return (
          <button
            key={t.name}
            type="button"
            onClick={() => setTheme(t.name)}
            aria-label={`${t.label} theme`}
            aria-pressed={active}
            title={t.label}
            className={cn(
              "h-7 w-7 rounded-full border-2 flex items-center justify-center transition-transform duration-150 ease-out cursor-pointer hover:scale-110",
              active ? "border-accent" : "border-transparent"
            )}
            style={{ backgroundColor: t.bg, boxShadow: `inset 0 0 0 1px ${t.ring}` }}
          >
            <span
              className="h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: t.accent }}
            />
          </button>
        );
      })}
    </div>
  );
}

/** Sidebar theme picker (labelled block). */
export function ThemePicker() {
  return (
    <div>
      <p className="kicker mb-2 px-1">Theme</p>
      <ThemeSwatches />
    </div>
  );
}
