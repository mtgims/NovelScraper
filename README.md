# NovelScraper

A web-novel reader for Android and Linux. It keeps a library on the device,
reads novels straight from their sites through extensions, and narrates chapters
out loud with neural voices that run locally, with the screen off.

It exists because serialised fiction is mostly published on sites that are, to
put it politely, hostile to reading: ads, broken pagination, nothing that
remembers where a chapter was left, and nothing kept once a site disappears.
This keeps its own copy and gets out of the way.

---

## What it does

- **Reads from sources.** Sources are extensions, and the app ships with none:
  add a repository, install the sources wanted, then browse, search and read
  from those sites over the device's own connection.
- **Keeps a library on the device.** Novels, chapter lists, progress, ratings
  and collections live in SQLite on the phone or the computer. Download keeps a
  novel's chapters for reading and listening offline.
- **Typeset reader.** A choice of font, size and line spacing. It remembers the
  position in each chapter, restores it after images and fonts settle, and marks
  chapters read as they are finished.
- **Narrates.** Kokoro and Piper, both running locally through sherpa-onnx, with
  gapless playback across chapters, a sleep timer, a pronunciation dictionary,
  and audio export to WAV. Android plays in the background and on the lock
  screen; Linux answers the keyboard's media keys through MPRIS.
- **Answers browser checks.** Sources behind Cloudflare or DDoS-Guard are
  fetched through a real browser: the phone's WebView, or on Linux the browser
  already installed (Chromium, Brave, Chrome, Edge or Vivaldi), driven in a
  profile of the app's own. A check that needs a person brings its window
  forward and goes away again afterwards.

[`app/CHANGELOG.md`](app/CHANGELOG.md) tracks every version.

## Building it

Kotlin Multiplatform: one `composeApp` module holds the app, `androidApp` is the
Android shell, and the desktop target builds from the same code.

```bash
cd app && mise exec -- ./gradlew :androidApp:assembleRelease        # Android
cd app && mise exec -- ./gradlew :composeApp:packageLinuxAppImage   # Linux
cd app && mise exec -- ./gradlew :composeApp:run                    # Linux, from source
```

The phone build lands at `novelscraper.apk` in the repo root and the Linux one
at `novelscraper-x86_64.AppImage` (one file with its own Java runtime: make it
executable and run it; it needs FUSE 2, `fuse2` on Arch).

## Sources

Sources live in their own repository,
[novelscraper-extensions](https://github.com/mtgims/novelscraper-extensions),
which also explains how to write one. The app ships with none: under Browse,
Extensions, Repositories, add

```
https://raw.githubusercontent.com/mtgims/novelscraper-extensions/master/index.json
```

or LNReader's repository, or any index in the same format, then install what is
wanted. Plugins run in QuickJS inside the app; the JavaScript host they run
against lives in `app/composeApp/pluginHost/` (`npm install && npm run build`
regenerates the bundled `host.js`).

## Where things live

On Linux: settings in `~/.config/novelscraper`, the library and downloaded
voices in `~/.local/share/novelscraper`, the image cache in
`~/.cache/novelscraper`, and downloads in the Downloads folder. On Android the
equivalents are the app's own storage, with downloads in the Downloads
collection.

Keys in the reader: ←/→ chapters, Space/Page Down and Shift+Space/Page Up to
turn the page, P to play or pause, Ctrl +/- font size, Esc back.

## An optional server

None of the above needs an account. There is a companion server that adds one,
and with it sync between devices (the library, its order, ratings, collections,
read chapters and the reading position to the sentence), scraping a novel by its
web address, EPUB import and export, reading statistics, and a web reader. It
lives in its own repository and is not required to use the apps: signed out, the
app is a local library that reads from its sources.

## A thing to expect: Cloudflare

Several sources answer a home connection and refuse a datacentre one, because
Cloudflare blocks hosting-provider address ranges by reputation, and no amount
of header fiddling changes that. The apps fetch from the device's own
connection, which is usually a home one, so this mostly affects a server. A VPN
exit can also be judged that way: a source may then ask for a check that has to
be answered by hand.

## Being reasonable about this

Requests go out at a polite rate, back off on errors, and are cached so a re-read
doesn't re-fetch. Please leave that alone. The point is a personal library of
things already read, not a way to strip-mine someone's site, and the sources
here are largely aggregators reposting other people's translations, which is its
own mess.

Anything fetched is still under someone else's copyright; it is not for
redistribution.
