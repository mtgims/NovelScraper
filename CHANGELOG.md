# Changelog

What changed and when, newest first. Dates are when the work landed.

The Android app has its own version numbers on top of this, see
[`app/CHANGELOG.md`](app/CHANGELOG.md), which maps each `versionCode` to
what's in that APK, so you can tell what's on your phone.

---

## 2026-09-23

- **Fixed: Ranobes and Novel Hall (0.34.3).** Ranobes is guarded by DDoS-Guard,
  whose 503 the app read as a dead site rather than a check to pass; it now
  loads through the browser like a Cloudflare site. Novel Hall was showing a
  "book cover not available" picture on every novel, because its list pages
  carry no covers; the app draws its own tile instead. Cookies collected on the
  way through the browser are kept, so following pages are plain requests.
- **Browser checks on Linux, the way the phone does them (0.34.2).** A hidden
  browser loads pages for sources that refuse plain requests, and shows itself
  only when a check needs a tap, which is how Mihon handles this on Android.
- **Fixed: the Linux app crashed on a source's browser check (0.34.1).**
  Chromium was started with a display backend that didn't match the window it
  was given, and with the wrong path to its helper programs; either one killed
  the app. Pages a site refuses to hand over are now fetched through that
  browser, which works for some sources; sites that only accept a browser
  window you can watch (Scribble Hub) still can't be read on the desktop.

- **Reliability and parity (0.34.0).** Reading carries on into the next chapter
  by scrolling, the library checks its source novels for new chapters, downloads
  retry and skip instead of stopping (and survive leaving the app on Android),
  the library file is copied before any schema migration, and the Linux app can
  open a real browser to pass a source's browser check.

## 2026-09-22

- **Narration rebuilt (0.33.0).** Gapless chapters, asides skipped, a
  pronunciation dictionary, a sleep timer, saving chapters as audio files, and
  media keys on Linux (MPRIS). Android's neural narration now runs the shared
  narrator, the same code the desktop uses.
- **Devices sync their libraries (0.32.0).** Library, order, ratings,
  collections, read chapters and the reading position (to the sentence) sync
  between a user's devices through their account, metadata only, with offline
  changes sent later and later-change-wins on conflicts. Server side:
  `POST /api/sync`, which seeds from the existing tables on first use and keeps
  them in step for novels stored on the server.
- **Linux app starts again (0.31.1).** The 0.31.0 AppImage closed on startup
  (its bundled Java runtime was missing the database module).
- **Local library and streaming (0.31.0).** The apps keep the library on the
  device and read novels straight from their sources, with Add to library and
  Download for offline reading and narration. The server account is optional;
  signed in, the server's library is imported and kept in step (changes made
  offline are sent later).
- **New toolchain (0.30.2).** Kotlin 2.4.20, Compose Multiplatform 1.12.0, AGP
  9.4.1, Gradle 9.7.1; the Android app is its own `androidApp` module and is built
  with `./gradlew :androidApp:assembleRelease`. Lint now covers the shared code
  and caught a crash in extensions using `urlencode` on Android 12 and older.
- **Sources live in their own repository (0.30.1).** All 15 of NovelScraper's
  sources are extensions in github.com/mtgims/novelscraper-extensions; the app
  ships with none and adds repositories only when you do (ours, LNReader's, or
  any other in the same format).
- **Source extensions (0.30.0).** A Browse tab reads novels straight from their
  sites through extensions: LNReader's 280 community-maintained plugins run as
  they are, and the app's own sources (Novel Archive, Novel Bin, OpenQuill,
  Ranobes, Wuxia Click) ship built in, in the same format. Popular, latest,
  search, novel pages and a reader, on Android and Linux.

## 2026-09-21

- **The Linux app (0.29.0).** The phone app as a desktop app: library, reader,
  ratings, downloads, EPUB import, export and Kokoro/Piper narration, laid out for
  a wide window with reader keyboard shortcuts. One AppImage file
  (`novelscraper-x86_64.AppImage`). On the phone, the reader keeps its text width
  in landscape and on tablets.
- **Android: importing a downloaded volume (0.28.3).** An EPUB saved with
  ⋮ → Download can be imported again; it was rejected as "not an .epub file".
- **Fix: exporting reading progress** (Stats → Export) failed with a server
  error since the 2026-09-20 stats refactor, which dropped an import the export
  still used. It now uses the same batched query as the stats page, and has a
  test.
- **Android app restructured for desktop (0.28.2).** No visible change. The
  project moved from `APKcode/` to `app/` and is now Kotlin Multiplatform:
  screens, networking and the reader are shared JVM code, so the coming Linux
  app runs the same code. Installing over 0.28.1 keeps your login and settings.
- **Android reader:** the Listen pill slides up from the bottom with the
  Prev / Next bar as one piece, instead of popping in above it.
- **Android: ratings.** Rate a book 1 to 5 stars from its page; the rating
  shows on the library card and matches the web.
- **Android: download volumes.** ⋮ → Download on a book saves any volume as an
  EPUB, or every volume as one zip, into Downloads.

## 2026-09-20

**Performance pass across the whole app**, measured before and after rather than
guessed at. Nothing about the UI or behaviour changed.

- Covers are served as width-capped WebP instead of whatever the source site
  published, and the server now honours conditional requests: it had been
  sending an `ETag` and then ignoring it, so every client re-downloaded every
  cover forever. Library page LCP 15.3 s → 4.1 s on mobile; covers below the
  first row load lazily, which on a 60-book shelf cut transfer 2 446 → 980 kB.
- SQLite switched to WAL, so reading a chapter no longer waits behind a scrape
  writing one. Three indexes the models declared had never actually been
  created: every per-user query was a full table scan. Chapter lookups now use
  a covering index: 4.60 ms → 2.42 ms.
- Authenticated reads no longer write to the database. Session expiry was being
  persisted on every single request, which also forced a redundant re-read.
- `/api/stats` lost its N+1: at 60 books it was 124 SQL statements, now 6.
- Deleting a 2 334-chapter novel: 321 ms → 17 ms, and 74 MB → 0.09 MB of memory.
  It had been loading every chapter's compressed text just to discard it.
- The reader stopped re-rendering itself on every scroll frame. Scroll layout
  work down 97%, scripting down 85%.
- framer-motion is loaded lazily, taking 27 kB off the reader's first load.
- Android: per-ABI APKs (phones were downloading the emulator's 34 MB of native
  libs) and R8 enabled. APK 80.4 MB → 33.6 MB, cold start 236 → 211 ms, memory
  46 → 36 MB. Release builds no longer log every request to logcat.

Also: the repo got a README, the docs were tidied, and the deploy moved from
rsync to a git clone on the server.

**Reader immersive mode**, toolbars and the Android system bars hide while
reading. Tap to bring them back, scroll to hide them.

## 2026-08-24

**NovelUpdates as a source.** An in-app browser to pick a translation group,
then scrape that group from chapter 1. NU sits behind a Cloudflare challenge
that only reveals chapter links to a logged-in session, so it needs a real
WebView. Paste an NU link into the normal URL field and it works too.

**Generic extraction for unknown sites**: a profile-less fallback, a
sequential-URL strategy for JS-router readers, and a WebView render fallback
that extracts from the post-JS DOM when static HTML is useless.

## 2026-08-20

Auto-update was never actually running; fixed, with a per-user "what's due"
endpoint. The Android app triggers it when it comes to the foreground, so
Cloudflare-gated sources update through the phone.

## 2026-08-18

In-chapter illustrations in the Android reader. Fixed TTS skipping lines by
capping chunk size under Kokoro's token limit.

## 2026-08-15

Delete novels, check for new chapters, and export reading progress from the
Android app.

## 2026-08-14

**Scrape through your phone's IP.** Cloudflare blocks the server's datacentre
address range for several sources but not a residential one, so the raw fetch is
relayed over a WebSocket to the phone. Caddy routes the relay straight to the
backend, since Next.js rewrites don't forward WebSocket upgrades.

## 2026-08-13

**On-device neural narration on Android** via sherpa-onnx, Kokoro first, then
Piper as a faster second engine with named US and UK voices. Getting it to sound
right took most of the day: the wrong model (English and Chinese only), voices
listed as "Voice #n", an audio path some phones route through a bandlimited
voice channel, and a "speedup" that on-device timing proved was slower than what
it replaced.

Sentence highlighting and tap-a-sentence-to-start in the Android reader, plus
the "Listen" pill matching the web player.

**Web TTS** moved to a Web Worker so synthesis stops freezing the UI, with a
CPU fallback for browsers whose WebGPU can't run the model.

## 2026-08-12

**The Android app**, built in a day: native Kotlin and Compose, not a WebView
wrapper. Login, library grid with collections and drag-to-reorder, book detail,
reader, EPUB import, the scrape/progress/stats screens, background and
lock-screen narration, and the editorial theme from the web app.

## 2026-08-11

Six new sources, novelcool, ranobes, novelhall, novelbuddy, wuxia.click,
openquill, plus a new `sequential` enumeration strategy, and a fix for
freewebnovel only ever finding the first 40 chapters. Dropped lightnovelworld,
which shut down.

## 2026-08-10

**One-command Docker deploy** to a GPU-free VPS at a custom domain, with
automatic HTTPS.

Reader typography controls (font, size, line spacing) that work on mobile. A
third TTS engine using the device's own OS voices, which phones now default to.
The book page reworked for mobile with swipe tabs and a finger-following bottom
sheet.

## 2026-08-09

TTS voice picker grouped by language and gender; fixed the highlight running
away and losing its place when you changed voice or speed mid-playback.

## 2026-08-01

Fixed incremental updates filing every new chapter as its own volume.

## 2026-07-11 → 07-13

**Multi-user accounts.** Sessions as opaque tokens in an httpOnly cookie,
scrypt password hashing with no external crypto dependency, invite-based
registration, and an admin page. Every query scoped to its owner, with a
cross-user isolation test to keep it that way: a missing scope there is a data
leak, not a bug.

Fixed a VRAM leak that filled a 12 GB card, and made the session cookie slide so
an active reader never expires mid-chapter.

## 2026-07-04 → 07-05

Installable as a PWA. Lock-screen media controls and background audio on mobile,
narration that auto-advances between chapters, and a pull-for-next gesture.

A maintainability pass over both halves: the backend grew a service layer so
routers stopped importing each other's private helpers, and the frontend's
duplicated interaction logic was deduplicated. The conventions that came out of
it are in `CONTRIBUTING.md`.

Several rounds of on-device TTS work establishing what actually runs on a phone
GPU: the answer being "less than you'd hope".

## 2026-07-02 → 07-03

Library collections, ratings, bulk chapter marking, drag-and-drop reordering,
and progress that survives deleting and re-scraping a novel.

EPUB import, on-demand EPUB export, and embedded illustrations so a downloaded
book is complete offline. Chapter text stored compressed.

In-chapter scroll restore, rewritten on localStorage after the server-side
version proved unreliable: it now waits for images and fonts to settle before
restoring, because they change the page height for a while after first paint.

Rate-limit handling that paces off the server's advertised budget rather than
backing off blindly.

## 2026-07-01

Initial commit: the scraper, a FastAPI backend and a Next.js reader.
