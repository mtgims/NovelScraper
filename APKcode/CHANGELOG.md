# NovelScraper for Android, changelog

Newest first. One entry per change that shipped; `versionCode` increments by one
each time, so it doubles as the build number.

**Versioning**, `x.z.y`:
- **`z`** (middle) rises for a new capability: a screen, an engine, a source, a
  new way to get content in.
- **`y`** (last) rises for a small fix: a bug, a tuning pass, polish, a rename.
- **`x`** stays 0 until you call it 1.0.

**`versionCode` must increase for every build you sideload**, or Android refuses
to install it over the previous one. Both fields live in
`app/build.gradle.kts`.

```bash
cd APKcode && mise exec -- ./gradlew :app:assembleRelease
```

The phone build is copied to **`novelscraper.apk` in the project root** on every
release build, overwriting the previous one: that is the file to install or
send. The emulator's x86_64 build stays at
`APKcode/app/build/outputs/apk/release/app-x86_64-release.apk`.

Since 0.25.0 the release is minified by R8. **Archive
`app/build/outputs/mapping/release/mapping.txt` with every APK you ship**, a
crash report is unreadable without the matching one.

---

## 0.28.1, 2026-09-21 · `versionCode 48`
The Listen pill now rises from the bottom of the screen together with the
Prev / Next bar, and leaves with it, instead of popping in on its own above a
bar that slid. While narration is playing and the bars are hidden, the player
stays on its own and settles down to the edge; bringing the bars back slides
the Prev / Next bar in underneath it.

## 0.28.0, 2026-09-21 · `versionCode 47`
Ratings. Tap a star under the author on the book screen to rate it 1 to 5;
tap the current rating again to clear it, same as on the web. The rating is
saved to the server and shows as small stars on the library card.

## 0.27.0, 2026-09-21 · `versionCode 46`
Download volumes from the book screen: ⋮ → Download lists every volume, each
saved as an EPUB, plus all of them as one zip when there's more than one. Files
go through the system download manager, so they get a notification, keep going
if you leave the app, and land in Downloads.

## 0.26.2, 2026-09-20 · `versionCode 45`
Covers are requested at the size they're drawn (`?w=800`), instead of at
whatever resolution the source site published. 80% fewer bytes over mobile data
(2 905 376 B → 581 870 B across the 9-cover test library) with no visible
softening. Needs the matching server release; an older server ignores the
parameter and serves the original.

## 0.26.1, 2026-09-20 · `versionCode 44`
The OkHttp logging interceptor no longer runs in release builds. It had been
formatting a logcat line for every request *and* every image Coil pulled through
the shared client, and putting the user's library activity into the device log.

## 0.26.0, 2026-09-20 · `versionCode 43`
R8 and resource shrinking enabled. dex drops from 46 774 612 B across three
files to **3 474 384 B in one** (−93%), `resources.arsc` 420 220 → 173 508 B,
`res/` 93 → 48 entries. Cold start 236 → 211 ms, idle memory 46.4 → 36.0 MB.
Keep rules cover the four things R8 can't see: sherpa-onnx's JNI classes,
kotlinx-serialization's generated serializers, Retrofit's reflective proxy, and
`@JavascriptInterface` members on the offscreen WebViews.

## 0.25.0, 2026-09-20 · `versionCode 42`
Per-ABI APKs. The release used to package arm64-v8a *and* x86_64 together;
x86_64 exists only for the emulator, yet its 33.9 MiB of sherpa-onnx and ONNX
Runtime libs were 42% of every APK a phone downloaded and could never run.
Phone APK **80 391 724 → 44 824 901 B**.

## 0.24.0, 2026-09-20 · `versionCode 41`
Reader immersive mode: the toolbars and the Android status/nav bars hide while
reading, leaving only the text. A tap toggles them, scrolling hides them, and
they start visible when a chapter opens.

## 0.23.1, 2026-08-24 · `versionCode 40`
NovelUpdates scrapes carry the title and author across, so books stop importing
as "Unknown Author". NU scrapes start from a translator's chapter page, whose OG
tags rarely include the author: it lives on the novel page, which the app
already reads.

## 0.23.0, 2026-08-24 · `versionCode 39`
Paste a NovelUpdates series link into the normal URL field and scrape it. The
app reads the translation groups in an offscreen WebView using the saved NU
login, shows the group chooser, and scrapes the chosen group from chapter 1: no separate browser screen needed.

## 0.22.1, 2026-08-24 · `versionCode 38`
Two NovelUpdates fixes: it had been scraping only the last chapter (it resolved
a *recent* chapter from NU's landing table and enumerated forward from there),
and it showed a wrong chapter count instead of the latest chapter.

## 0.22.0, 2026-08-24 · `versionCode 37`
Add novels from NovelUpdates: an in-app browser and a translation-group chooser.
NU sits behind a Cloudflare challenge that only reveals group and chapter links
when logged in, so the backend can't fetch it: a real in-app WebView passes the
challenge and you log into NU on its own page.

## 0.21.0, 2026-08-24 · `versionCode 36`
WebView render fallback for JS-only and Cloudflare-gated pages: when the static
HTML is unusable the page is rendered in the phone's WebView and extracted from
the post-JS DOM, feeding the normal pipeline.

## 0.20.0, 2026-08-20 · `versionCode 35`
Due books auto-update when the app comes to the foreground or you sign in.
Cloudflare-gated sources 403 from the server, so the background updater can
never fetch them; this queues the work while the phone relay is up.

## 0.19.0, 2026-08-18 · `versionCode 34`
In-chapter illustrations appear in the reader. The native reader had flattened
chapter HTML to plain text, dropping every `<img>`, so imported-EPUB
illustrations never showed.

## 0.18.0, 2026-08-15 · `versionCode 33`
Three library-management features: delete a novel (overflow menu → confirm),
check for new chapters, and export reading progress.

## 0.17.0, 2026-08-14 · `versionCode 32`
**Scrape through your phone's IP.** Cloudflare 403s the server's datacentre IP
for some sources (novelfire/novelphoenix, freewebnovel, novelhall) but not a
residential or mobile one, so each scrape's raw HTTP fetch is routed through the
phone over a WebSocket relay.

## 0.16.2, 2026-08-13 · `versionCode 31`
The spoken sentence is centred in the reader. The follow-scroll had used a
proportional estimate that landed it near the bottom, where the floating Listen
pill covered it; it now uses the real on-screen position from the text layout.

## 0.16.1, 2026-08-13 · `versionCode 30`
Narration stops when you leave a chapter, but survives backgrounding, screen-off
and rotation.

## 0.16.0, 2026-08-13 · `versionCode 29`
Piper gains a curated voice set, each a ~65 MB download-on-demand: US English
(Amy, Ryan, Lessac, Joe) and British English (Alan, Cori, Alba, Northern). It
had been a single voice.

## 0.15.0, 2026-08-13 · `versionCode 28`
Piper (VITS) added as a second on-device engine alongside Kokoro. Kokoro-82M
runs at about 1× real time on this CPU, its ceiling, and RTF logging confirmed
no thread or provider combination reaches 2×. A lighter Piper model does, via
the same sherpa-onnx `OfflineTts`.

## 0.14.9, 2026-08-13 · `versionCode 27`
Fix the Kokoro speed regression. On-device RTF logging showed the previous
"speedup" (XNNPACK + 6 threads) was actually *slower*, RTF ~1.05–1.25, slower
than real time, which caused the buffering, versus ~0.79–0.95 on plain CPU.
Reverted to the plain CPU execution provider with threads capped at 4.

## 0.14.8, 2026-08-13 · `versionCode 26`
Log Kokoro's real-time factor per chunk, to tune speed on-device by measurement
rather than by guessing (the Dimensity 8400's cores span 3.25/3.0/2.1 GHz, so
more threads can be slower).

## 0.14.7, 2026-08-13 · `versionCode 25`
Kokoro audio quality: switch off `CONTENT_TYPE_SPEECH`, which some OEMs route
through a bandlimited voice-processing path (the "old radio" tinniness), and
de-pop with clamping plus edge fades.

## 0.14.6, 2026-08-13 · `versionCode 24`
Speed up Kokoro and remove playback buffering. Underruns came from a shallow
pipeline: a 2-sentence prefetch and a ~0.5 s AudioTrack buffer, with only four
inference threads.

## 0.14.5, 2026-08-13 · `versionCode 23`
Switch Kokoro to the multilingual v1.0 model. The v1.1-zh model shipped in
0.14.0 contains only English and Chinese: three English voices, all female,
and 100 Mandarin, which is the wrong model for a reader who wants accents.

## 0.14.4, 2026-08-13 · `versionCode 22`
Sort Kokoro voices into gendered sections with counts. The single "Chinese
(Mandarin)" section listed 55 female then 45 male, so the numbering jumped back
at the boundary and looked unsorted.

## 0.14.3, 2026-08-13 · `versionCode 21`
Name the Kokoro voices and group them by nationality, instead of "Voice #n", all 103 speakers in the exact `voices.bin` order.

## 0.14.2, 2026-08-13 · `versionCode 20`
Reader and TTS fixes: collapsible per-volume accordions in the book TOC and a
new reader "Chapters" bottom sheet, plus progress-marking, scroll and
TTS-start fixes.

## 0.14.1, 2026-08-13 · `versionCode 19`
Fix the Kokoro model download failing with ENOENT on a fresh install, `cacheDir` can be absent right after install, so the temp tar stream failed
before any bytes were written.

## 0.14.0, 2026-08-13 · `versionCode 18`
**On-device neural TTS via sherpa-onnx (Kokoro).** Streams the ~150 MB model,
extracts it with a zip-slip guard, and swaps it into place atomically so a
killed download can't leave half a model. Synthesis is single-flight (ONNX
Runtime isn't thread-safe) and release can't free the engine mid-generate, that
had been a use-after-free crash. Falls back to the device TTS if the model isn't
ready. Also fixes a packaging bug: an unanchored `data/` ignore rule had been
matching the app's `com/novelscraper/app/data/` package, so three source files
were never committed and a fresh clone wouldn't build.

## 0.13.1, 2026-08-13 · `versionCode 17`
The voice picker lists every installed voice. It had filtered to the device's
current language, so other-language voices never appeared.

## 0.13.0, 2026-08-13 · `versionCode 16`
Sentence highlighting, and tap a sentence to start narration there. A shared
splitter is used by both the reader and the TTS service so the indices line up
with what's spoken. Trade-off: the reader renders plain text (paragraph breaks
kept) rather than rich HTML spans, which is what lets the highlight align.

## 0.12.0, 2026-08-13 · `versionCode 15`
The reader's "Listen" pill, matching the web player: rewind / play-pause /
forward, an elapsed-and-estimated-total readout, a seek slider that jumps to a
sentence, and an expandable panel with speech rate and voice.

## 0.11.2, 2026-08-12 · `versionCode 14`
Smoother tab slide. Two causes: debug builds run Compose and ART unoptimized, so
a signed release build type was added; and some screens started network loads
during composition, landing the recompose mid-animation, so those are deferred
past the slide.

## 0.11.1, 2026-08-12 · `versionCode 13`
Consistent headers across tabs, direction-aware horizontal slide transitions,
and a fix for books taking up to 10 s to open, book and chapters are now
fetched in parallel and rendered immediately, rather than blocking on
`/progress` and its server-side word-count backfill.

## 0.11.0, 2026-08-12 · `versionCode 12`
EPUB import on the New Scrape screen, streamed straight from the content
resolver so large files aren't held in memory. This is also the workaround for
Cloudflare-gated sources: scrape on a device with a residential IP, export, and
import here.

## 0.10.0, 2026-08-12 · `versionCode 11`
Assign books to collections from the book page, plus a book-detail and reader
restyle (larger cover, serif body at a ~620 dp reading measure).

## 0.9.0, 2026-08-12 · `versionCode 10`
Real New Scrape, Progress and Stats screens, replacing the placeholders, URL
submission with unsupported-source and duplicate handling, live job polling with
cancel, and reading statistics.

## 0.8.0, 2026-08-12 · `versionCode 9`
Library collection tabs and drag-to-reorder. Long-press a cover to drag;
long-press a tab to rename or delete it (novels are kept).

## 0.7.1, 2026-08-12 · `versionCode 8`
Fix covers never loading on-device. The default base URL had no trailing slash
and only `setBaseUrl` normalised it, so hand-built cover URLs became
`https://novelscraper.comapi/books/…` and failed DNS. Retrofit had hidden the
bug because `HttpUrl` adds the root slash itself.

## 0.7.0, 2026-08-12 · `versionCode 7`
Editorial redesign to match the web app: the real fonts (Playfair Display,
Source Serif 4, JetBrains Mono), all four palettes, a floating pill bottom nav
whose selected item expands, and new Settings and Register screens.

## 0.6.0, 2026-08-12 · `versionCode 6`
**Background and lock-screen narration.** A foreground service narrates with the
device TTS engine, so playback continues with the screen off, which the web
app's Web Speech engine can't do. MediaSession gives lock-screen transport and
headset buttons; audio focus and becoming-noisy are handled.

## 0.5.0, 2026-08-12 · `versionCode 5`
The reader: chapter rendering, prev/next gated on availability, persisted font
size, and progress sync.

## 0.4.0, 2026-08-12 · `versionCode 4`
Book detail and chapter list, cover header, read count and percentage, a
Continue button that resumes from the last position, and a volume-grouped
chapter list with read checkmarks.

## 0.3.0, 2026-08-12 · `versionCode 3`
The library grid. Covers load through the same OkHttp client as the API, so they
carry the session cookie; books without one get an initials placeholder.

## 0.2.0, 2026-08-12 · `versionCode 2`
Networking and sign-in: typed REST client, a persistent cookie jar so the
session survives restarts, a configurable server address, and session resume.

## 0.1.0, 2026-08-12 · `versionCode 1`
Scaffold: a native Kotlin + Jetpack Compose app (not a WebView wrapper),
compileSdk 34 / minSdk 26, with the JDK pinned to Temurin 21.
