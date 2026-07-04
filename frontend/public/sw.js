/* NovelScraper service worker.
 *
 * Purpose: satisfy the PWA installability criteria (a fetch handler) and provide
 * a small offline app shell. Deliberately conservative so it can never "brick"
 * the app by serving stale/broken content:
 *   - /api/* is NEVER intercepted (dynamic data always hits the network).
 *   - page navigations are network-first (you never get a stale page); the cache
 *     is only a fallback when offline.
 *   - only hashed, immutable build assets are cache-first.
 * Bump CACHE to roll every client onto a new version.
 */
const CACHE = "ns-cache-v1";
const SHELL = "/";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.add(SHELL)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
      )
      .then(() => self.clients.claim())
  );
});

function cachePut(request, response) {
  // Only cache our own successful, non-opaque responses.
  if (response && response.ok && response.type === "basic") {
    const copy = response.clone();
    caches.open(CACHE).then((c) => c.put(request, copy)).catch(() => {});
  }
  return response;
}

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return; // third-party: leave to browser
  if (url.pathname.startsWith("/api/")) return; // dynamic data: never cache

  // Hashed, immutable build assets → cache-first (filenames change on deploy).
  if (url.pathname.startsWith("/_next/static/")) {
    event.respondWith(
      caches.match(req).then((hit) => hit || fetch(req).then((r) => cachePut(req, r)))
    );
    return;
  }

  // Page navigations → network-first (fresh), cache/shell only as offline fallback.
  if (req.mode === "navigate") {
    event.respondWith(
      fetch(req)
        .then((r) => cachePut(req, r))
        .catch(() => caches.match(req).then((hit) => hit || caches.match(SHELL)))
    );
    return;
  }

  // Everything else same-origin (icons, manifest, fonts) → stale-while-revalidate.
  event.respondWith(
    caches.match(req).then((hit) => {
      const net = fetch(req)
        .then((r) => cachePut(req, r))
        .catch(() => hit);
      return hit || net;
    })
  );
});
