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
      <div className="rule-accent flex flex-col items-start gap-3 pt-4 sm:flex-row sm:flex-wrap sm:items-end sm:justify-between sm:gap-4">
        <h1 className="min-w-0 font-display text-3xl sm:text-4xl md:text-5xl tracking-tight leading-none break-words sm:flex-1 sm:basis-96">
          {title}
        </h1>
        {children && <div className="w-full shrink-0 sm:w-auto sm:pb-1">{children}</div>}
      </div>
    </header>
  );
}
