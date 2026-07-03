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
};

export default nextConfig;
