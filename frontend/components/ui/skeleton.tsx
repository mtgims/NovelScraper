import { cn } from "@/lib/utils";

export function Skeleton({
  className,
}: {
  className?: string;
}) {
  return (
    <div className={cn("bg-muted rounded-sm animate-pulse", className)} />
  );
}

export function BookCardSkeleton() {
  return (
    <div className="border border-border rounded-sm overflow-hidden">
      <Skeleton className="aspect-[3/4] rounded-none" />
      <div className="p-4">
        <Skeleton className="h-5 w-3/4 mb-2" />
        <Skeleton className="h-4 w-1/2 mb-3" />
        <Skeleton className="h-3 w-2/5" />
      </div>
    </div>
  );
}
