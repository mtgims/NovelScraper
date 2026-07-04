import type { MetadataRoute } from "next";

// Web app manifest — makes the app installable ("Install app" / "Add to Home
// screen") so it launches full-screen from a home-screen icon with no browser
// chrome. Next.js auto-links this at /manifest.webmanifest.
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "NovelScraper",
    short_name: "NovelScraper",
    description: "Your private, typeset web-novel library with narration.",
    start_url: "/",
    scope: "/",
    display: "standalone",
    background_color: "#0a0a0b",
    theme_color: "#0a0a0b",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      // Full-bleed variant so Android can mask it to any shape without clipping the mark.
      { src: "/icons/maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
