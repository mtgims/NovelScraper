import type { ReactNode } from "react";

export function PageHeader({
  title,
  kicker,
  children,
}: {
  title: string;
  kicker?: string;
  children?: ReactNode;
}) {
  return (
    <header className="mb-10">
      {kicker && <p className="kicker mb-3">{kicker}</p>}
      <div className="rule-accent flex items-end justify-between gap-4 pt-4">
        <h1 className="min-w-0 font-display text-4xl md:text-5xl tracking-tight leading-none break-words">
          {title}
        </h1>
        {children && <div className="shrink-0 pb-1">{children}</div>}
      </div>
    </header>
  );
}
