/* NovelScraper service worker — minimal, no-cache.
 *
 * This app is a live client to a self-hosted server (it can't do anything
 * offline — no server means no content), so caching app code buys nothing and
 * actively causes harm: it can serve STALE JavaScript (masking deploys) and it
 * makes an offline phone look like "the backend is down."
 *
 * So this worker exists only to keep the app installable (PWA installability
 * wants a fetch handler) and to PURGE any caches left by earlier caching
 * versions. It never caches — every request goes straight to the network.
 */
self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    // Delete every cache from older versions so no stale code can survive.
    caches
      .keys()
      .then((keys) => Promise.all(keys.map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

// A fetch listener is present (satisfies installability) but never calls
// respondWith → the browser performs its normal network fetch. Nothing cached.
self.addEventListener("fetch", () => {});
