"use client";

import { animate, m, useMotionValue, useTransform } from "framer-motion";
import { useEffect, useRef, useState, type ReactNode } from "react";

import { cn } from "@/lib/utils";

type Tab = { label: string; content: ReactNode };

/** Horizontally-swipeable tab panels with an underline that follows the finger.
 *  Drag left/right to switch (vertical scroll still works via pan-y). */
export function SwipeTabs({ tabs, className }: { tabs: Tab[]; className?: string }) {
  const n = tabs.length;
  const [active, setActive] = useState(0);
  const [w, setW] = useState(0);
  const [minHeight, setMinHeight] = useState(0);
  const wRef = useRef(0);
  const viewportRef = useRef<HTMLDivElement>(null);
  const x = useMotionValue(0);

  useEffect(() => {
    const el = viewportRef.current;
    if (!el) return;
    const measure = () => {
      const width = el.clientWidth;
      wRef.current = width;
      setW(width);
      x.set(-active * width); // keep the active panel aligned across resizes
      // Grow the panels down to the bottom of the screen so a swipe registers
      // anywhere in the region, not only where there's content to touch.
      const top = el.getBoundingClientRect().top;
      setMinHeight(Math.max(320, window.innerHeight - top));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const goTo = (i: number) => {
    const clamped = Math.max(0, Math.min(n - 1, i));
    setActive(clamped);
    animate(x, -clamped * wRef.current, { type: "spring", damping: 34, stiffness: 340 });
  };

  // Underline position tracks the live drag: progress 0..n-1 → x 0..w*(n-1)/n.
  const indicatorX = useTransform(x, (v) => {
    const width = wRef.current;
    if (!width) return 0;
    const p = Math.max(0, Math.min(n - 1, -v / width));
    return p * (width / n);
  });

  return (
    <div className={className}>
      <div className="relative flex border-b border-border">
        {tabs.map((t, i) => (
          <button
            key={i}
            type="button"
            onClick={() => goTo(i)}
            className={cn(
              "flex-1 py-2.5 text-sm font-medium transition-colors",
              active === i ? "text-foreground" : "text-muted-foreground"
            )}
          >
            {t.label}
          </button>
        ))}
        <m.div
          style={{ x: indicatorX, width: `${100 / n}%` }}
          className="absolute bottom-0 left-0 h-0.5 rounded-full bg-accent"
        />
      </div>

      <div ref={viewportRef} className="overflow-hidden">
        <m.div
          className="flex items-start"
          style={{ x, touchAction: "pan-y" }}
          drag="x"
          dragDirectionLock
          dragConstraints={{ left: -(n - 1) * w, right: 0 }}
          dragElastic={0.12}
          onDragEnd={(_, info) => {
            const width = wRef.current || 1;
            const pos = -x.get() / width; // fractional panel position
            let target = Math.round(pos);
            if (Math.abs(info.velocity.x) > 400) {
              target = info.velocity.x < 0 ? Math.ceil(pos) : Math.floor(pos);
            }
            goTo(target);
          }}
        >
          {tabs.map((t, i) => (
            <div key={i} className="w-full shrink-0" style={{ minHeight }}>
              {t.content}
            </div>
          ))}
        </m.div>
      </div>
    </div>
  );
}
