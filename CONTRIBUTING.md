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

Those three also run in CI, on every push to master and every pull request
(`.github/workflows/check.yml`). Running them locally first is still faster
than waiting for a red run.

Checks that touch real sites, a real browser or a speech model are opt-in and
stay that way. Nothing in the ordinary suite opens a browser or reaches a
source:

```bash
-PlivePlugins=<id>[,<id>] -PliveRepo=<extensions checkout> [-PliveSearch=<term>]
-PliveBrowser=<url> [-PliveDump=<file>]
-PfetchBrowser=true
-PliveTts=true [-PliveTtsOut=<file.wav>]
```

## Signing a release

The APK is signed in CI with a real release key. The keystore is never in the
repository, never in a Gradle file, and never on a developer's machine as part
of a build: it reaches the build as environment variables, from repository
secrets.

**The key cannot be replaced later.** Android refuses an update whose signature
differs from the installed one, so a lost or rotated keystore means every
existing reader must uninstall, and uninstalling takes their library with them.
Back the keystore and its passwords up somewhere that survives losing the
machine, before the first signed release goes out.

Creating one, once:

```bash
keytool -genkeypair -v -keystore novelscraper-release.jks \
  -alias novelscraper -keyalg RSA -keysize 4096 \
  -validity 10000 -storetype PKCS12
base64 -w0 novelscraper-release.jks     # the value for ANDROID_KEYSTORE_BASE64
```

Four repository secrets, under Settings, Secrets and variables, Actions:

| Secret | What it holds |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the `.jks` file, base64 |
| `ANDROID_KEYSTORE_PASSWORD` | the store password |
| `ANDROID_KEY_ALIAS` | the alias, `novelscraper` above |
| `ANDROID_KEY_PASSWORD` | the key password |

Until those exist the release workflow logs a warning and builds no APK, rather
than attaching one signed with the debug key.

A local `./gradlew :androidApp:assembleRelease` still falls back to the debug
key, which is fine for a build nobody else installs. Any build that will be
published must pass `-PrequireReleaseKey=true`, which fails rather than
producing an APK that cannot update what readers already have.

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
