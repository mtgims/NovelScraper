# NovelScraper

A self-hosted library for web novels. It scrapes a novel into a proper database,
gives you a reader that remembers where you were, and can narrate chapters out
loud, in the browser or on Android, with the screen off.

I built it because I read a lot of serialised fiction on sites that are, to put
it politely, hostile to reading. Ads, broken pagination, no sync between my
phone and my desktop, and no way to keep anything once a site disappears. This
keeps my own copy and gets out of the way.

It's meant for one person or a handful of friends, there's auth and per-user
isolation, but it is not a service and I wouldn't run it as one.

---

## What it does

- **Scrapes a novel from a URL.** Paste a link, it works out the source, enumerates
  the chapters and pulls them down at a polite rate. 15 sources are supported by
  YAML profile; unknown sites fall back to a generic extractor.
- **Reads.** Typeset reader with your choice of font, size and line spacing. It
  remembers your position per chapter, restores it after images and fonts settle,
  and marks chapters read as you finish them.
- **Narrates.** Three engines on the web (a server-side one, an on-device WebGPU
  one, and your OS voices) and two on Android (Kokoro and Piper, both running
  locally via sherpa-onnx). Android does background and lock-screen playback.
- **Imports and exports EPUB,** so nothing is trapped here.
- **Keeps up.** Books can re-scrape themselves for new chapters.

See [`CHANGELOG.md`](CHANGELOG.md) for what's changed and when.

## Running it

Built and run on Python 3.14 and Node 22: the same versions the container
images use. Older ones may well work; nothing pins a floor. From a checkout:

```bash
./start.sh
```

That brings up the API on `:8000` and the web app on `:3000`, installing
dependencies on first run. Set an admin password before the first start, or the
bootstrap will skip account creation:

```bash
export NOVELSCRAPER_ADMIN_USERNAME=you
export NOVELSCRAPER_ADMIN_PASSWORD='something long'
```

There is no forgot-password flow. The admin is created only when the database
has no users at all, so if you lose it you'll be editing `password_hash`
directly.

## Deploying

`docker-compose.yml` brings up backend, frontend and Caddy (which handles
HTTPS). Create a `.env` next to it:

```
DOMAIN=your-domain.com
ACME_EMAIL=you@example.com
NOVELSCRAPER_ADMIN_USERNAME=you
NOVELSCRAPER_ADMIN_PASSWORD=something long
```

then:

```bash
docker compose up -d --build
```

On the server it's a git clone, so updates are:

```bash
ssh <server> 'cd /opt/novelscraper && git pull && docker compose up -d --build'
```

Two things that aren't obvious and will cost you an afternoon if you get them
wrong:

- **Keep the domain DNS-only (grey cloud) if you use Cloudflare.** Proxying it
  caps uploads at 100 MB, which breaks large EPUB imports, and it stops Caddy
  reaching the box to issue a certificate.
- **Keep the deploy directory named `novelscraper`.** Compose derives the
  project name from it, and your library lives in the `novelscraper_nsdata`
  volume. Rename the directory and Compose will happily start with an empty
  new one.

`backend/data` is a Docker volume, not a directory in the tree: it is the only
irreplaceable thing here. Everything else rebuilds.

## The apps: Android and Linux

Native Kotlin and Compose Multiplatform, in `app/`. Not a WebView wrapper: it has
its own reader and its own on-device TTS. The screens, networking and reader are
shared code, so the Android and Linux apps are the same app (Windows comes next).

```bash
cd app && mise exec -- ./gradlew :composeApp:assembleRelease        # Android
cd app && mise exec -- ./gradlew :composeApp:packageLinuxAppImage   # Linux
cd app && mise exec -- ./gradlew :composeApp:run                    # Linux, from source
```

The phone build lands at `novelscraper.apk` in the repo root and the Linux one at
`novelscraper-x86_64.AppImage` (one file with its own Java runtime: make it
executable and run it; it needs FUSE 2, `fuse2` on Arch). `app/CHANGELOG.md`
tracks every version.

On Linux the app keeps its settings and login in `~/.config/novelscraper`,
downloaded voices in `~/.local/share/novelscraper` and its image cache in
`~/.cache/novelscraper`; volume downloads go to your Downloads folder. Narration
uses the Kokoro or Piper voices (download one in Settings). Keys in the reader:
←/→ chapters, Space/Page Down and Shift+Space/Page Up to turn the page, P to play
or pause, Ctrl +/- font size, Esc back.

## A thing you'll hit: Cloudflare

Several sources return 403 to a datacentre IP but serve a residential one
happily. Cloudflare blocks hosting-provider address ranges by reputation, and no
amount of header fiddling changes that, I tried. So if you host this on a VPS,
some sources won't scrape from the server.

Two ways around it, both built in: scrape on a device with a normal connection
and import the EPUB, or use the Android app, which relays the fetch through your
phone.

## How it's put together

```
backend/     FastAPI + SQLModel over SQLite. The scraper lives in app/scraper/,
             domain logic in app/services/, routers in app/api/. Site profiles
             are YAML in site_profiles/.
frontend/    Next.js 15, App Router. Proxies /api/* to the backend, so there's
             one origin and no CORS.
app/         The Android app (Kotlin Multiplatform; desktop targets share it).
deploy/      Compose, Caddy, the push script and the hosting runbook.
docs/        Performance audit, an engineering risk brief, older handoff notes.
```

Chapter HTML is sanitised on the way in and stored gzip-compressed. Scrapes run
as background jobs with live progress over SSE.

## Adding a source

Most sites need a YAML file in `backend/site_profiles/` and no code: a base URL,
how to enumerate chapters (`paginated`, `next_link`, `json_api` or `sequential`),
and CSS selectors for the title, content and cover. Copy the closest existing
profile and adjust.

If a URL matches no profile, a generic extractor (`build_generic_profile`) has a
go at it. If the page is JS-only and the static HTML is useless, the scrape can
render it in the Android app's WebView over the relay and extract from the
post-JS DOM, so that path needs the phone connected.

## Tests

```bash
cd backend && .venv/bin/python tests/smoke_api.py     # full API lifecycle
.venv/bin/python tests/test_isolation.py              # per-user data isolation
```

The rest of the suites are in `backend/tests/`. There's no runner; each file is
a script that prints its own summary and exits non-zero on failure.

## Being reasonable about this

It respects `robots.txt`, rate-limits itself, backs off on errors and caches what
it fetches so a re-run doesn't re-hammer a site. Please leave that alone. The
point is a personal library of things you already read, not a way to strip-mine
someone's site, and the sources here are largely aggregators reposting other
people's translations, which is its own mess.

Whatever you scrape is still under someone else's copyright. Keep it to yourself.
