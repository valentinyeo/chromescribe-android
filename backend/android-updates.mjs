// ChromeScribe Android self-update handler (standalone, side-effect free).
//
// Intended to be dropped into the existing Cloudflare Worker (bound to the R2
// bucket `chromescribe-transcripts` as `env.TRANSCRIPTS`) and called from the
// worker's `/api/` dispatcher:
//
//     import { handleAndroidUpdates } from "./android-updates.mjs";
//     ...
//     const updaterResponse = await handleAndroidUpdates(request, env);
//     if (updaterResponse) return updaterResponse;
//
// This module is protected by the same Cloudflare Access policy as the rest of
// the dashboard origin; it performs no authentication of its own and must only
// be served behind that Access application. It intentionally does not read or
// log any request headers, cookies, or tokens.
//
// Routes:
//   GET /api/android/latest              -> R2 android/latest.json  (no-store)
//   GET /api/android/releases/<int>/app.apk
//                                        -> R2 android/releases/<int>/app.apk
//                                           (immutable private, exact length)
//
// Any other path returns `null` so the caller can continue routing. All
// errors resolve to a plain Response; this handler never throws.

const LATEST_OBJECT_KEY = "android/latest.json";
const RELEASE_PREFIX = "android/releases/";

// Strict: digits only, no signs, no leading-zero padding, no traversal, no
// encoding tricks. Release 0 is rejected: the app requires a positive code and
// the release tooling starts at 1, so 0 must never be treated as a version.
const RELEASE_APK_RE = /^\/api\/android\/releases\/([1-9][0-9]{0,9})\/app\.apk$/;

const APK_MIME = "application/vnd.android.package-archive";

/** Build a JSON Response and forbid intermediary/browser caching. */
function jsonResponse(status, body) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function notFound() {
  return jsonResponse(404, JSON.stringify({ error: "not_found" }));
}

function badGateway() {
  return jsonResponse(502, JSON.stringify({ error: "upstream_error" }));
}

/**
 * Serve android/latest.json from R2.
 * The object is small; we buffer it so we can force no-store semantics and
 * avoid streaming a stale body if R2 metadata is missing.
 */
async function serveLatest(bucket) {
  if (!bucket || typeof bucket.get !== "function") return badGateway();
  const object = await bucket.get(LATEST_OBJECT_KEY);
  if (!object || !object.body) return notFound();
  const text = await object.text();
  return new Response(text, {
    status: 200,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

/**
 * Serve a versioned APK. Only the exact integer release path is allowed; the
 * R2 key is constructed from the captured, validated integer so callers can
 * never reach arbitrary bucket objects. A single `get` is used: a `head`
 * probe would double the round-trips for no benefit, and we always force the
 * APK MIME below rather than trusting stored metadata.
 */
async function serveApk(bucket, versionCode) {
  if (!bucket || typeof bucket.get !== "function") return badGateway();
  const key = `${RELEASE_PREFIX}${versionCode}/app.apk`;
  const object = await bucket.get(key);
  if (!object || !object.body) return notFound();
  return apkResponse(object.body, object.size);
}

/**
 * Build the immutable APK response. The APK is content-addressed by
 * versionCode (its bytes are never rewritten in place; a new release uses a
 * new code), so it is safe and desirable for clients to cache it. The MIME
 * type is always the Android package type; stored metadata is ignored so a
 * misconfigured upload can never serve the APK as something the client will
 * not download as an installer.
 */
function apkResponse(body, size) {
  const headers = {
    "content-type": APK_MIME,
    "cache-control": "private, max-age=31536000, immutable",
    "x-content-type-options": "nosniff",
    "content-disposition": 'attachment; filename="app.apk"',
  };
  if (typeof size === "number" && size >= 0) {
    headers["content-length"] = String(size);
  }
  return new Response(body, { status: 200, headers });
}

/**
 * Handle Android updater GETs.
 *
 * @param {Request} request
 * @param {{TRANSCRIPTS?: object}} env Cloudflare Worker bindings.
 * @returns {Promise<Response|null>} Response when this module owns the route,
 *   otherwise `null` so the caller can keep routing.
 */
export async function handleAndroidUpdates(request, env) {
  try {
    if (!request || request.method !== "GET") {
      // Only GET is owned here; other methods fall through to the caller.
      return null;
    }

    let pathname;
    try {
      pathname = new URL(request.url).pathname;
    } catch {
      return null;
    }

    if (pathname === "/api/android/latest") {
      return await serveLatest(env && env.TRANSCRIPTS);
    }

    const match = RELEASE_APK_RE.exec(pathname);
    if (match) {
      return await serveApk(env && env.TRANSCRIPTS, match[1]);
    }

    // Not an updater route.
    return null;
  } catch {
    // Fail closed and quiet: never leak internals, never throw into the
    // caller's router.
    return badGateway();
  }
}

export default handleAndroidUpdates;