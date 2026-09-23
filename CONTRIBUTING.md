# Contributing

Notes for working on this repo, mostly hard-won constraints that aren't obvious
from the code, and conventions worth keeping.

## Before calling something done

After any non-trivial change, read the diff and check for:

- **Security**, injection (SQL, command, and SSRF especially, since this thing
  fetches arbitrary URLs), unsafe deserialisation, secrets in code, missing input
  validation, careless handling of fetched remote content, and authz gaps in the
  backend.
- **Bugs**, logic errors, unhandled edge cases, error handling, resource leaks,
  concurrency.
- **Performance**, N+1 queries, blocking I/O on hot paths, unbounded memory or
  request counts, missing rate limiting or caching in the scraper.

State what was actually checked, not "reviewed".

## Scraping conduct

Be mindful of target sites: respect `robots.txt` where applicable, rate-limit
requests, and set sensible timeouts/retries. Don't write anything that hammers a
source.

**Cloudflare-gated sources & datacenter-IP blocks.** Some sources (novelphoenix →
novelfire, freewebnovel, novelhall) 403 from the VPS but not from a residential IP, Cloudflare blocks the Hetzner datacenter ASN by reputation, and no fingerprint/IP
change on a cloud host fixes it (only a residential IP passes). Don't burn effort on
impersonation tweaks or new server IPs for these. The workarounds are importing
an EPUB exported from a machine on a normal connection, or letting the Android
app relay the fetch through the phone.

## App conventions

- **Shared code first.** Anything that isn't tied to a platform belongs in
  `jvmSharedMain`; `androidMain` and `desktopMain` hold only what genuinely
  differs (audio sinks, file pickers, the browser, window handling). A platform
  difference that leaks into shared code is a bug waiting for the other target.
- **Sentence indices are a contract.** The reader's highlighting, narration and
  the resume point all index the same flattened chapter text, and both platforms
  must produce it identically: the golden files under
  `app/composeApp/src/htmlParity/` exist to prove that. Changing `htmlToPlain`
  means regenerating and checking them.
- **Verify on both targets.** `:composeApp:desktopTest`,
  `:composeApp:testAndroidHostTest` and `:androidApp:lintRelease` before calling
  anything done, plus the app itself: navigation changes get every destination
  opened, because a missing route compiles perfectly well.
- **Live checks against real sites** are opt-in and stay that way:
  `-PlivePlugins=<id>` for a source, `-PliveBrowser=<url>` for the browser,
  `-PliveTts=true` for narration. Tests never open a browser otherwise.
- **The version lives in `app/gradle.properties`** (`appVersionCode`,
  `appVersionName`) and is shared by both apps. `appVersionCode` must increase
  for every build that gets sideloaded.

## Scraping conduct

Be mindful of target sites: rate-limit requests, set sensible timeouts and
retries, and don't write anything that hammers a source. Requests go out from
the reader's own device and connection, which is a person's, not a crawler's.
