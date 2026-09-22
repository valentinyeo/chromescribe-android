# ChromeScribe for Android

A tiny native shell around the existing ChromeScribe mobile website. It lets
you share a YouTube link from the YouTube app (or paste one) and read
transcripts offline. Login stays in the app's WebView, so there are **no
service credentials embedded in the APK**.

- App id: `org.crolab.chromescribe`
- Version: 1.1.0 (versionCode 2)
- minSdk 24, target/compileSdk 34

## Install

Signed APK releases: https://github.com/valentinyeo/chromescribe-android/releases


Debug builds are installable directly:

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are unsigned unless a local signing config is supplied.
`assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Use

- **Website**: the real dashboard, including the Cloudflare Access email OTP
  login. Sign in once; the session lives in the WebView's own cookie store.
  Only the dashboard origin and `*.cloudflareaccess.com` login pages open
  inside; other links open in your browser. Tapping Website reloads after a
  main-frame network failure.
- **Saved**: a fully offline list of transcripts whose markdown has actually
  been cached on the device. Long-press to remove one.
- **Add**: paste a YouTube link. Shared links (`ACTION_SEND`) use the same path.
  The link is written to a durable local outbox *before* any network call, then
  sent. If you're offline it stays queued and is retried when you reopen the
  app, reconnect, or return to the dashboard. Queued is not the same as
  transcribed. Retry entries are acknowledged only on an explicit server
  response; malformed responses leave the entry queued.
- **Overflow (⋮)**
  - *Sync latest 20*: refresh the list and the latest 20 cached transcripts.
    A failed sync keeps the previous readable copies and preserves titles for
    older saved items.
  - *Save current*: cache the transcript currently open in the WebView.
  - *Retry shares*: retry queued submissions now; auth/server errors stay
    visible until retried.

## Offline scope

The reader is bundled and self-contained: no CDN, no web fonts, no JavaScript,
no network loads. Cached markdown is treated as untrusted: raw HTML is
escaped, only `http(s)` links are rendered, and images never load over the
network. Caching is atomic, so a killed process keeps the last good copy.

## Verification status

Pure-JVM unit tests (62) cover URL validation, durable outbox/retry semantics,
queue-response acknowledgements, server date parsing, cache atomicity/metadata
preservation and safe markdown rendering. Android lint reports zero issues.

Android 10 emulator checks confirmed offline reading with Wi-Fi and mobile data
disabled, saved-library persistence after restart, and durable ACTION_SEND
queueing. The software emulator showed font-rendering artifacts. Physical-device
layout and the interactive email OTP flow remain unverified.

The production queue backend was deployed on 2026-09-22. A live API submission
produced transcript 3359 and its summary. Duplicate submissions reuse the queue
entry; previously transcribed videos return their existing transcript.
Full phone share-sheet and interactive login verification remain pending.

## Build / test

```bash
./gradlew test lint assembleDebug assembleRelease
```

CI (`.github/workflows/build.yml`) runs the same on Java 17 + Android SDK and
uploads the debug APK. No signing keys are checked in.