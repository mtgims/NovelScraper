# Third-party notices

NovelScraper itself is under the GNU General Public License, version 3 or
later; see [LICENSE](LICENSE). The components below are other people's work,
under their own terms.

## Fonts, bundled in the app

All three are shipped inside the Android and desktop builds, so their licence
travels with them: the full text is in
[third-party/OFL-1.1.txt](third-party/OFL-1.1.txt).

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

| Model | Licence | Upstream |
|---|---|---|
| Kokoro | Apache License 2.0 | [hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) |
| Supertonic | OpenRAIL-M (the sample code is MIT) | [Supertone/supertonic](https://huggingface.co/Supertone/supertonic) |
| Piper | Per voice; each carries its own, and they differ | [rhasspy/piper](https://github.com/rhasspy/piper) |

**Supertonic is the one with conditions on use, not only on credit.** An
OpenRAIL-M licence restricts what the model may be used *for*, where Apache 2.0
asks only to be acknowledged. Its terms forbid, among other things, using the
model to impersonate a person without their consent, presenting what it
generates as human-made without saying otherwise, and any use against
applicable law. Anyone narrating with that voice is bound by them; the full
text is with the model at the link above.

Piper ships as a collection of separate voices rather than one model, and the
licence varies from voice to voice. The voice's own model card states which.

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
