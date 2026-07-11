/** @type {import('next').NextConfig} */

// Where the FastAPI backend lives (server-side only). The browser never talks to
// it directly — the frontend proxies /api/* to it — so the app works over any
// host (localhost, LAN, Tailscale, a tunnel) with no per-host config, no CORS,
// and no rebuild when the address changes.
const backend = process.env.BACKEND_ORIGIN || "http://127.0.0.1:8000";

const nextConfig = {
  reactStrictMode: true,
  // Hide the floating Next.js dev-tools indicator (the "N" button) in dev.
  devIndicators: false,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
  experimental: {
    // EPUB uploads (/api/import) are proxied through this rewrite. Next's proxy
    // caps the request body at 10MB by default, silently truncating anything
    // larger — the upstream then resets and the client sees an opaque 500
    // ("Internal server error"), never reaching the backend. This limit gates
    // the WHOLE request, and a single import can carry many volumes at once
    // (e.g. a 26-EPUB series ≈ 380MB), so it must clear the largest realistic
    // batch, not just one file. The backend stays the real per-file gate (caps
    // each EPUB at 100MB, streams with a size check, returns a clean 413).
    middlewareClientMaxBodySize: "2gb",
  },
};

export default nextConfig;
