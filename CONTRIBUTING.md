# Contributing

Notes for working on the apps, mostly constraints that aren't obvious from the
code and were expensive to find out.

## Before calling something done

After any non-trivial change, read the diff and check for:

- **Security**, in particular anything touching source plugins (they are
  third-party JavaScript running in QuickJS), fetched HTML, cookies and the
  browser the app drives. Never widen what a plugin can reach, never log a
  cookie or a credential, never let fetched content reach a shell or a file
  path unchecked.
- **Bugs**, logic errors, unhandled edge cases, resource leaks and concurrency.
  Anything that starts a process, a browser, a thread or a coroutine scope owns
  stopping it, including when the app is killed rather than closed.
- **Performance**, work on the UI thread, unbounded memory or request counts,
  and per-item work in a list that should have been done once.

State what was actually checked, not "reviewed".

## Building and checking

```bash
cd app
mise exec -- ./gradlew :androidApp:assembleRelease        # APK, lands in the repo root
mise exec -- ./gradlew :composeApp:packageLinuxAppImage   # AppImage, same place
mise exec -- ./gradlew :composeApp:run                    # Linux, from source

./gradlew :composeApp:desktopTest :composeApp:testAndroidHostTest :androidApp:lintRelease
```

Checks that touch real sites, a real browser or a speech model are opt-in and
stay that way. Nothing in the ordinary suite opens a browser or reaches a
source:

```bash
-PlivePlugins=<id>[,<id>] -PliveRepo=<extensions checkout> [-PliveSearch=<term>]
-PliveBrowser=<url> [-PliveDump=<file>]
-PfetchBrowser=true
-PliveTts=true [-PliveTtsOut=<file.wav>]
```

## App conventions

- **Shared code first.** Anything not tied to a platform belongs in
  `jvmSharedMain`; `androidMain` and `desktopMain` hold only what genuinely
  differs (audio sinks, file pickers, the browser, window handling).
- **Sentence indices are a contract.** The reader's highlighting, narration and
  the resume point all index the same flattened chapter text, and both platforms
  must produce it identically. The golden files under
  `app/composeApp/src/htmlParity/` exist to prove it; changing `htmlToPlain`
  means regenerating and checking them.
- **Verify on both targets, and in the app itself.** The three Gradle checks
  above, then the thing that changed: a navigation change gets every destination
  opened, because a route that no longer exists compiles perfectly well and
  crashes at the tap.
- **Layout follows the window, behaviour follows the input.** Width decides what
  is laid out; `isDesktop` decides what a long press, a right-click or a hover
  means. A long press belongs to dragging on a phone, so menus there live on the
  novel's own page.
- **The version lives in `app/gradle.properties`.** `appVersionCode` must
  increase for every build that gets installed over another; `appVersionName` is
  `x.z.y`, where `z` rises for a new capability and `y` for a fix. Both apps
  share it.
- **Every version ships as a release.** Bump, write both changelogs, run the
  checks, build the APK and the AppImage, commit, then publish a GitHub release
  with both binaries attached. The README has no build instructions on purpose:
  releases are how anyone installs this.

## The browser the app drives

Sources behind Cloudflare or DDoS-Guard are fetched through a real browser: the
WebView on Android, the installed browser on Linux over the DevTools protocol.
That machinery is delicate and most of its rules were learned the hard way.

- **Never let the page know it is being driven.** Chromium reports
  `navigator.webdriver` while a debugging session is attached, and an
  interactive challenge refuses a browser that admits it, whatever else is
  right. `--disable-blink-features=AutomationControlled` is load-bearing.
- **Read pages through the DOM**, not by evaluating script in them, except for
  the in-page requests a site's own scripts would make (a chapter list is
  usually one of those, and is refused to anything else).
- **One page load per site per session.** Landing on a site costs seconds; its
  other pages are asked for from inside the page it is already on, and pages a
  site hands only to a navigation are loaded in a frame of it.
- **A browser is owned.** It closes when unused, goes down with the app whatever
  ends it, and anything left on the app's profile by an earlier run is cleared
  before a new one starts. A leaked browser is a gigabyte of processes nobody
  can see.
- **A window the reader didn't ask for stays out of the way**, and how that is
  done depends on the desktop: minimised where minimising exists, on a workspace
  of its own under a tiling compositor. It comes forward only when a check needs
  a person, and goes away again.

## Sources

Sources are extensions in LNReader's plugin format and live in
[novelscraper-extensions](https://github.com/mtgims/novelscraper-extensions).
Fixing a source means changing a plugin there and publishing it; the app picks
it up from the repository's index. Check a change with
`-PlivePlugins=<id> -PliveRepo=<checkout>`, which lists, searches and reads one
novel for real.

Be mindful of the sites: rate-limit requests, set sensible timeouts and retries,
and write nothing that hammers a source. Requests go out from the reader's own
device and connection, which belongs to a person, not to a crawler.
