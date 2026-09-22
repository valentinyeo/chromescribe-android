# Changelog

## 1.2.0: versionCode 3

- Lean self-updater (Android app side). Foreground check at most once daily
  plus manual *Check for updates* in the overflow menu. Automatic/offline
  failures stay silent; manual errors are visible; an auth-blocked check does
  not consume the day and retries after sign-in.
- `GET /api/android/latest` is fetched same-origin with the WebView's scoped
  cookie (redirects disabled). `apkPath` is restricted to exactly
  `/api/android/releases/<versionCode>/app.apk`; integer fields reject
  decimal/exponent/string/boolean coercion.
- The release-notes dialog with *Update* / *Later* appears before any
  download: the check only fetches the manifest, and the APK is fetched only
  after you tap *Update*. APK download is bounded (20 MiB ceiling and declared
  length enforced while streaming), timed out, and staged atomically; a failed
  attempt never deletes a previously staged good APK.
- Verified fail-closed before install and re-verified immediately before every
  install launch (including the Settings resume): byte length, SHA-256, package
  name, version code, minSdk, and signer certificates must all match the
  installed app. Failures discard the file and never advance the daily marker;
  concurrent checks/downloads are de-duplicated.
- Install uses a non-exported `ContentProvider` (single fixed `pending.apk`,
  read-only, one-shot URI grant) plus the system `ACTION_VIEW` installer, so
  the OS always confirms. The staged APK is not deleted when the installer
  opens (Android reads it asynchronously); it is cleaned up on a later
  foreground once the new version is installed. Android 8+
  `REQUEST_INSTALL_PACKAGES` and the one-time
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` grant are handled, and the pending
  install (persisted only after *Update* is tapped) is resumed after returning
  from Settings without re-prompting in a loop.
- Added `REQUEST_INSTALL_PACKAGES`, the `ApkProvider` (not exported), and pure
  JVM tests for path/size/hash/schedule, strict manifest parsing, the streaming
  verifier (truncated/oversized/interrupted/hash-mismatch and previous-file
  preservation) and the never-delete-early cleanup decision.
- No new runtime dependencies, no background service, no push. Existing data
  is preserved across updates.

## 1.1.0: versionCode 2

- Replace the direct OkHttp API client and service-token headers with a
  WebView session. Login stays in the WebView; native calls reuse scoped
  dashboard cookies and the same User-Agent. No credentials in the APK.
- Handle `ACTION_SEND text/plain` YouTube links directly in MainActivity and
  add a paste dialog; strict host/path/ID validation, normalised to
  `https://www.youtube.com/watch?v=<id>`. The share intent is consumed only
  after durable storage and is not reprocessed on rotation/recreation.
- Durable, bounded, deduplicating outbox: persisted atomically before the
  network, retried on resume/connectivity/dashboard return, retained on
  auth/network/server failure. Acknowledgements require an explicit queue
  envelope (`queued=true` + id, or `queued=false` + `transcript_id`); malformed
  responses never clear an entry. Never reports queued as transcribed.
- Explicit Sync caches the latest 20 transcripts and preserves titles/dates for
  older saved items; the open transcript is cached automatically. Server
  `created_at` ISO/SQLite strings are parsed instead of read as epoch 0.
- Offline reader: JS and network disabled, fixed non-null HTTPS base (fixes
  blank output on Android 10), light-mode CSS overrides ordered after dark
  rules. Safe, sentinel-free markdown renderer (escaped HTML, no `javascript:`
  links or image loads) with one ordered list and correct blank-line grouping.
- Compact no-action-bar toolbar (Website / Saved / Add / overflow) that fits a
  360dp display; overflow has Sync latest 20, Save current and Retry shares.
  Auth/server errors stay visible until retried.
- Main-frame network failure falls back to the saved library; Website reloads
  when tapped. External links open in the browser; only the dashboard and
  `*.cloudflareaccess.com` stay internal; non-http(s) navigation is blocked.
- Background failures are logged/surfaced instead of swallowed; UI callbacks
  are guarded against destroyed activities; network calls use the application
  context; outbox writes are process-wide synchronized.
- `allowBackup=false`, restricted backup rules, only `INTERNET` +
  `ACCESS_NETWORK_STATE`, cleartext traffic disabled.
- Release builds enable minify + resource shrinking (unsigned by default).
- Added Gradle wrapper 8.2, GitHub Actions test/lint/build, README and tests.

## 1.0: versionCode 1

- Initial extracted Android companion: share URL in, read summaries offline.