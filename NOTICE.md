# Third-party notices

NovelScraper itself is under the GNU General Public License, version 3 or
later; see [LICENSE](LICENSE). The components below are other people's work,
under their own terms.

## Fonts, bundled in the app

All three are shipped inside the Android and desktop builds, so their licence
travels with them: the full text is in
[third-party/OFL-1.1.txt](third-party/OFL-1.1.txt). The copyright lines are
taken from the font files' own metadata rather than retyped.

| Family | Copyright | Licence |
|---|---|---|
| JetBrains Mono | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) | SIL Open Font License 1.1 |
| Playfair Display | Copyright 2017 The Playfair Display Project Authors (https://github.com/clauseggers/Playfair-Display), with Reserved Font Name "Playfair Display" | SIL Open Font License 1.1 |
| Source Serif 4 | © 2014 - 2021 Adobe Systems Incorporated (http://www.adobe.com/), with Reserved Font Name "Source" | SIL Open Font License 1.1 |

## Speech models, downloaded on request

These are **not** shipped in the app. Narration downloads the model a reader
chooses, once, from the sherpa-onnx releases at
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models, and it then
runs on the device.

| Model | Notes |
|---|---|
| Kokoro | Downloaded on request. Carries its own terms from upstream. |
| Piper | A collection of per-voice models, whose terms differ from voice to voice. |
| Supertonic | Reported to be under an OpenRAIL-M licence, which unlike the others carries **use restrictions** rather than only attribution. |

The exact terms for each have not been confirmed against upstream, and the
Supertonic line above comes from a code review rather than from the model card.
Confirm all three before the app is promoted anywhere, because an OpenRAIL-M
use restriction is a condition on what the software may be used *for*, which is
a different kind of obligation from the fonts' attribution.

## Runtimes and libraries

The desktop and Android builds carry sherpa-onnx and ONNX Runtime for
narration, QuickJS for running sources, and the usual Kotlin, Compose
Multiplatform, OkHttp and SQLDelight stack. Their licences are declared in
their own artifacts and resolved by Gradle; none of them are vendored into this
repository.

The JavaScript side of the source host bundles cheerio, htmlparser2, dayjs,
@noble/ciphers and whatwg-url-without-unicode into
`app/composeApp/src/jvmSharedMain/resources/plugin-host/host.js`. See
`app/composeApp/pluginHost/package.json` for the versions.
