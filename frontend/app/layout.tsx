import type { Metadata, Viewport } from "next";
import {
  JetBrains_Mono,
  Playfair_Display,
  Source_Serif_4,
} from "next/font/google";

import { AppShell } from "@/components/app-shell";
import { Providers } from "./providers";
import "./globals.css";

const display = Playfair_Display({
  subsets: ["latin"],
  variable: "--font-display",
  weight: ["400", "700", "900"],
});
const serif = Source_Serif_4({
  subsets: ["latin"],
  variable: "--font-serif",
  weight: ["300", "400", "600"],
});
const mono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  weight: ["400", "500"],
});

export const metadata: Metadata = {
  title: "NovelScraper",
  description: "Scrape web novels into a private, typeset library.",
  // Behave like an installable app on mobile (full-screen when added to home).
  appleWebApp: { capable: true, statusBarStyle: "black-translucent", title: "NovelScraper" },
  // The app ships its own themes (next-themes). Tell the Dark Reader extension
  // to leave the page alone — otherwise it rewrites the DOM before React
  // hydrates, causing hydration-mismatch errors (the dev overlay's red badge).
  other: { "darkreader-lock": "1" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  // Draw under the notch / rounded corners on phones.
  viewportFit: "cover",
  // Status-bar tint when installed (matches the manifest theme_color).
  themeColor: "#0a0a0b",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body
        className={`${display.variable} ${serif.variable} ${mono.variable} font-serif`}
      >
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
