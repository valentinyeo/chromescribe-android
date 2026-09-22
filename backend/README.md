# Android updater backend

Standalone Cloudflare Worker handler and local release tooling for the
ChromeScribe Android self-updater. Nothing here is deployed by this repo; the
parent installs the handler into the live worker.

## Files

- `android-updates.mjs` — `handleAndroidUpdates(request, env)`. Returns a
  `Response` for updater routes, `null` otherwise.
- `android-updates.test.mjs` — node tests with a fake R2 binding.
- `../scripts/release.py` — one-command build/sign/verify/upload pipeline.
- `../scripts/test_release.py` — pure-Python tests for publication ordering.

## Routes

| Method | Path | R2 object | Cache |
| --- | --- | --- | --- |
| GET | `/api/android/latest` | `android/latest.json` | `no-store` |
| GET | `/api/android/releases/<code>/app.apk` | `android/releases/<code>/app.apk` | `private, max-age=31536000, immutable` |

`<code>` must be a positive integer (`0` is rejected). Any other path or
non-GET method returns `null` so the existing router keeps working. Missing
objects return `404`; R2 failures return `502`. The handler never throws and
never logs request data. It relies on the existing Cloudflare Access policy;
it performs no auth of its own.

## Installing into the live worker

The live worker serves `/api/*` via its own `handleApi`. Add the import and a
single guard at the top of that dispatcher, **before** the existing routes,
without modifying any other bytes:

```js
import { handleAndroidUpdates } from "./android-updates.mjs";

// ...inside handleApi / fetch, before existing /api routing:
const updater = await handleAndroidUpdates(request, env);
if (updater) return updater;
```

The handler uses the existing R2 binding `env.TRANSCRIPTS`
(bucket `chromescribe-transcripts`). No new bindings, secrets, or vars are
required.

### Manual smoke check (behind Access)

```bash
curl -sS https://<dashboard-origin>/api/android/latest | head
curl -sSI https://<dashboard-origin>/api/android/releases/3/app.apk
```

## Tests

```bash
node --test backend/android-updates.test.mjs
python3 -m unittest scripts.test_release
```