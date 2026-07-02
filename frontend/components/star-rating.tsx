"use client";

import { Star } from "lucide-react";
import { useState } from "react";

import { cn } from "@/lib/utils";

export function StarRating({
  value,
  onChange,
  size = 20,
  readOnly = false,
}: {
  value: number | null;
  onChange?: (rating: number) => void;
  size?: number;
  readOnly?: boolean;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const shown = hover ?? value ?? 0;

  return (
    <div
      className="inline-flex items-center gap-0.5"
      onMouseLeave={() => setHover(null)}
      role={readOnly ? "img" : "radiogroup"}
      aria-label={`Rating: ${value ?? "none"} of 5`}
    >
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          disabled={readOnly}
          aria-label={`${n} star${n > 1 ? "s" : ""}`}
          onMouseEnter={() => !readOnly && setHover(n)}
          onClick={() => onChange?.(value === n ? 0 : n)} // clicking the current rating clears it
          className={cn(
            "leading-none",
            readOnly
              ? "cursor-default"
              : "cursor-pointer transition-transform hover:scale-110"
          )}
        >
          <Star
            size={size}
            className={cn(
              "transition-colors",
              n <= shown ? "fill-amber-400 text-amber-400" : "text-muted-foreground/35"
            )}
          />
        </button>
      ))}
    </div>
  );
}
