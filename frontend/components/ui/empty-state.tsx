import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export function EmptyState({
  icon: Icon,
  title,
  description,
  children,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  children?: ReactNode;
}) {
  return (
    <div className="border border-dashed border-border rounded-sm px-8 py-16 text-center">
      <Icon size={26} className="mx-auto text-muted-foreground" />
      <h2 className="mt-5 font-display text-2xl">{title}</h2>
      {description && (
        <p className="mx-auto mt-2 max-w-sm text-muted-foreground">
          {description}
        </p>
      )}
      {children && <div className="mt-6 flex justify-center">{children}</div>}
    </div>
  );
}
