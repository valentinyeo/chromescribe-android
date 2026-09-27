# ChromeScribe for Android

A tiny native shell around the existing ChromeScribe mobile website. It lets
you share or paste YouTube, TikTok and HTTPS article links and read
transcripts offline. Login stays in the app's WebView, so there are no
service credentials embedded in the APK.

- App id: `org.crolab.chromescribe`
- Version: 1.3.0 (versionCode 5)
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

- Website: the real dashboard, including the Cloudflare Access email OTP
  login. Sign in once; the session lives in the WebView's own cookie store.
  Only the dashboard origin and `*.cloudflareaccess.com` login pages open
  inside; other links open in your browser. Tapping Website reloads after a
  main-frame network failure.
- Saved: a fully offline list of transcripts whose markdown has actually
  been cached on the device. Long-press to remove one.
- Add: paste a YouTube, TikTok or HTTPS article link. Shared links (`ACTION_SEND`) use the same path.
  The link is written to a durable local outbox *before* any network call, then
  sent. If you're offline it stays queued and is retried when you reopen the
  app, reconnect, or return to the dashboard. Queued is not the same as
  transcribed. Retry entries are acknowledged only on an explicit server
  response; malformed responses leave the entry queued.
- Overflow (⋮)
  - *Sync latest 20*: refresh the list and the latest 20 cached transcripts.
    A failed sync keeps the previous readable copies and preserves titles for
    older saved items.
  - *Save current*: cache the transcript currently open in the WebView.
  - *Retry shares*: retry queued submissions now; auth/server errors stay
    visible until retried.
  - *Check for updates*: check now. A silent check also runs when the app comes
    to the foreground, at most once a day.

## Self-update

The app can update itself from the same signed-in dashboard, without any
service credentials in the APK and without a background service or push.

- A check runs on foreground at most once a day, and on demand from
  *Check for updates*. Automatic failures (offline, server hiccup) are silent;
  the manual action shows the error. A check that fails because you are signed
  out does not count for the day, so it retries after you sign in.
- `GET /api/android/latest` (same origin, WebView's scoped cookie) returns
  `{versionCode, versionName, minSdk, apkPath, sha256, size, notes}`. `apkPath`
  must be exactly `/api/android/releases/<versionCode>/app.apk`; integer fields
  must be plain integers (decimals/exponents/strings/booleans are rejected).
- You are shown the release notes with *Update* / *Later* before any
  download: the check only fetches the manifest. The APK is downloaded only
  after you tap *Update* (over same-origin HTTPS, redirects disabled, a hard
  20 MiB ceiling enforced while streaming, and a timeout).
- The APK is verified before anything is offered and again immediately before
  every install: exact byte length, SHA-256, package name, version code, minSdk,
  and the signing certificate must match the installed app. Any mismatch is
  discarded (fail-closed). A failed download never deletes a previously staged
  good APK. Duplicate concurrent checks/downloads are prevented.
- Android 8+ asks once for "install unknown apps" permission; the app records
  the pending install, and re-verifies the APK before resuming after you return
  from Settings. The staged APK is not deleted when the installer opens
  (Android reads it asynchronously); it is cleaned up on a later foreground
  once the new version is actually installed. The system installer always shows
  its own confirmation. installs are never silent. Existing data is preserved
  (the update is an in-place package replace).

## Offline scope

The reader is bundled and self-contained: no CDN, no web fonts, no JavaScript,
no network loads. Cached markdown is treated as untrusted: raw HTML is
escaped, only `http(s)` links are rendered, and images never load over the
network. Caching is atomic, so a killed process keeps the last good copy.

## Verification status

Pure-JVM unit tests cover URL validation, durable outbox/retry semantics,
queue-response acknowledgements, server date parsing, cache atomicity/metadata
preservation, safe markdown rendering, and the self-update manifest/path/size/
hash rules and daily schedule. Android lint reports zero issues; `test`, `lint`,
`assembleDebug` and `assembleRelease` are all green.

On 2026-09-22, an Android 10 emulator upgraded through the app from a signed
older-version test build to the live 1.2.1 release. Verified the update offer,
no download before consent, authenticated APK download, signature checks,
unknown-source permission, recovery after process restart, Android install
confirmation, version replacement, and staged-APK cleanup. The saved transcript
remained byte-for-byte identical and the queued-link entry survived.

111 Android tests, 14 backend tests, and 17 release-script tests pass. GitHub CI
also passes. Physical-phone verification remains outstanding.

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

## Publish an update

Bump versionCode and versionName, then run on the release machine:

```bash
python3 scripts/release.py --release-notes notes.txt --publish
```

The command tests, builds, signs, uploads and reads back the APK, then updates
the version manifest. Published APKs cannot be replaced with different bytes.
The signing key stays outside Git. The initial updater APK requires one manual
installation; later releases are offered inside the app.
