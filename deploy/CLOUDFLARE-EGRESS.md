# Cloudflare-gated sources & the datacenter-IP problem

## The constraint (why some sources 403 only on the server)

Several sources sit behind **Cloudflare bot-fight / managed rules** that block by
**IP reputation at the ASN level**, not by request shape. Known-affected:
`novelphoenix` (and therefore `novelfire`, which we route through it),
`freewebnovel`, `novelhall`.

- From a **residential IP** (a home connection, e.g. the local dev instance) these
  sites return `200` — the app's `curl_cffi` Chrome impersonation already presents a
  full browser TLS fingerprint + UA, which is enough.
- From the **Hetzner VPS** the *same code, same fingerprint* gets `403`. The only
  variable is the IP: Cloudflare distrusts the entire Hetzner datacenter range.

Consequences, verified:
- **It is not an appealable ban.** There's no per-site appeal; only the site owner
  could allowlist us. The `403` is against the datacenter ASN, not our specific address.
- **Changing the server IP does not help.** A new Hetzner IP is another address in
  the same flagged ASN → same `403`. No cloud/VPS provider issues residential IPs;
  every datacenter range is recognisable as non-residential.
- **Fingerprint tweaks do not help.** We already send full Chrome impersonation and
  still `403` from the VPS, so it's the IP class, full stop.
- WARP (exit is still Cloudflare/non-residential), consumer/free VPNs (datacenter
  IPs), and "fresh" IPs (ASN weighting dominates) are **not** reliable fixes either.

**Only a residential IP passes.** So the real question is always *where the
residential IP comes from.* Non-Cloudflare sources (e.g. `novelbuddy`, `royalroad`)
are unaffected and keep scraping server-side normally.

## Current workaround (zero-code): scrape locally, import the EPUB

Until per-user egress exists, add Cloudflare-gated books like this:

1. On your **local instance** (residential IP → these sites work), scrape the novel
   normally.
2. **Download the EPUB(s)** for it from the local instance.
3. On the **live server**, go to **New → import EPUB** (`POST /import`, wired to
   `api.importEpubs` on `/new`) and upload them. Imported books read/TTS/track
   progress just like scraped ones; chapter bodies are re-sanitized on import.

Non-destructive and per-book — no DB surgery, nothing clobbered. Caveat: an imported
book isn't linked to a `source_url`, so "Update" won't fetch new chapters; to update,
re-scrape locally and re-import (use `POST /books/{id}/import` to append volumes).

## Future feature: per-user-IP scraping via a browser extension

The only **free** way to let *any* user scrape Cloudflare sources self-serve is to
fetch from **each user's own residential IP**. A web page can't do this (the
same-origin policy forbids page JS from reading a cross-origin response, and novel
sites don't send CORS headers). The one browser context that *can* is a **browser
extension** with host permissions (or a Tampermonkey userscript's `GM_xmlhttpRequest`,
which bypasses CORS the same way).

Design sketch:
- User installs the extension and logs in as normal; the extension opens a
  **WebSocket** to the backend.
- When the scraper needs a URL from a `use_proxy`/Cloudflare source **and the
  requesting user has the extension connected**, the backend delegates the fetch to
  that user's browser over the socket (correlation id + timeout).
- The extension `fetch()`es the URL (real browser, residential IP → passes bot-fight)
  and posts the HTML back; the server parses it exactly as today. Server-side pacing,
  robots and the SSRF/host allowlist still gate *which* URLs may be dispatched.
- Fall back to a direct server-side fetch when no client is connected (fine for
  non-Cloudflare sources; a `403` for the gated ones, same as today).

Constraints to accept before building: a **one-time install** per user, and the
user's **tab must stay connected during their scrape** (a first scrape of a
thousands-of-chapters novel is sequential and slow; updates are quick). This is a
substantial feature (extension for 2 browsers + a WebSocket relay + a new
"fetch-via-connected-client" transport in the scraper), not a config change.

Rejected alternatives: paid residential-proxy service (works + automatic, but costs
money); routing all scraping through the operator's own home IP (operator declined to
share their IP, and it wouldn't scale to other users).
