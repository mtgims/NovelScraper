import * as React from "react";

import { cn } from "@/lib/utils";

export const Input = React.forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement>
>(({ className, ...props }, ref) => (
  <input
    ref={ref}
    className={cn(
      "w-full h-10 px-3 text-sm bg-background text-foreground",
      "border border-border rounded-sm",
      "placeholder:text-muted-foreground/60",
      "transition-colors duration-150 ease-out",
      "focus-visible:border-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
      "disabled:opacity-50",
      className
    )}
    {...props}
  />
));
Input.displayName = "Input";
