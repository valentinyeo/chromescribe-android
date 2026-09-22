# Changelog

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