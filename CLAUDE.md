# ChromeScribe Android: agent notes

Lean native Android wrapper for the existing ChromeScribe mobile website
(`https://chromescribe-dashboard.ancient-glitter-9ac9.workers.dev/`).
No Compose, no AndroidX, no OkHttp, no JS bridge, no embedded secrets.

## Verification setup (local, this machine)

- Android SDK: `/home/valentin/android-sdk` (`platforms/android-34`, `build-tools/34.0.0`).
- Java: OpenJDK 17 (`/usr/lib/jvm/...`), `JAVA_HOME` points at it.
- Gradle wrapper 8.2, cached offline at:
  `/home/valentin/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle`
- AGP 8.2.0 / Kotlin 1.9.21 are already in the local Gradle cache.
- `local.properties` (gitignored) holds only `sdk.dir=/home/valentin/android-sdk`. No credentials.
- A parent-run emulator (`adb -s emulator-5580`, Android 10 / API 29) is
  available for device checks, but this agent only runs Gradle test/lint/build.
  Anything requiring a running Android runtime (WebView rendering, ACTION_SEND,
  cookie exchange with Cloudflare Access, connectivity callbacks) is
  build-verified here and must still be marked "device unverified" until the
  parent's device pass.

## How to verify

```bash
export ANDROID_HOME=/home/valentin/android-sdk
./gradlew --offline test lint assembleDebug assembleRelease
```

- `test`: pure-JVM JUnit tests for URL parsing, durable outbox/retry
  semantics, atomic transcript cache, and safe markdown rendering.
- `lint`: Android lint (no baseline; fix real issues, don't suppress blindly).
- `assembleDebug`: installable debug APK for a real device.
- `assembleRelease`: unsigned release APK (minify + resource shrink on).

Do not claim the app works on-device from a desktop browser check. The website
is behind Cloudflare Access email OTP, so only the WebView can hold the login.

## Hard rules

- Login stays in the WebView; native API requests read scoped cookies from
  `CookieManager` for the dashboard host only and reuse the WebView User-Agent.
- No SSL bypass, no `addJavascriptInterface`, no attaching cookies to other
  hosts/redirects.
- Outbox is written to disk atomically *before* any network call.
- Cached markdown is untrusted: escape raw HTML, never allow `javascript:` or
  network image loads, reader runs with JS and network disabled.
- `android:allowBackup="false"`; only `INTERNET` + `ACCESS_NETWORK_STATE`.
- Keep app id `org.crolab.chromescribe`.
- The live site is behind Cloudflare Access, so protected routes (including the
  exact transcript-page path) cannot be inspected from here. The app currently
  opens `${Api.BASE}/transcript.html?id=<id>` as a best-effort route; confirm
  and adjust after a signed-in device pass.
- Android 10 runtime pass confirmed cached transcript text with both network
  transports disabled, saved-library persistence and durable ACTION_SEND.
- Production queue deployed on 2026-09-22. API submission produced transcript
  3359 and summary. Interactive OTP, full phone share-sheet flow, and physical
  device layout remain unverified.

## Updater verification

Release 1.2.1, versionCode 4, passed the full authenticated in-app update flow
on Android 10, including installation permission and recovery after process
restart. Cached transcript bytes and the queued-link entry survived. Tests:
111 Android, 14 backend, 17 publisher. The signing key remains outside Git.
Use scripts/release.py for publication; never overwrite a versioned APK.
