// Tests for the standalone Android update handler using a fake R2 binding.
// Run with: node --test backend/android-updates.test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { handleAndroidUpdates } from "./android-updates.mjs";

/** Minimal in-memory R2 stand-in matching the subset the handler uses. */
function fakeBucket(entries) {
  const store = new Map(Object.entries(entries));
  return {
    async head(key) {
      const e = store.get(key);
      if (!e) return null;
      return { key, size: e.size, httpMetadata: e.httpMetadata };
    },
    async get(key) {
      const e = store.get(key);
      if (!e) return null;
      return {
        body: e.body,
        size: e.size,
        httpMetadata: e.httpMetadata,
        async text() {
          return e.body;
        },
      };
    },
  };
}

const LATEST = JSON.stringify({
  versionCode: 3,
  versionName: "1.2.0",
  minSdk: 24,
  apkPath: "/api/android/releases/3/app.apk",
  sha256: "a".repeat(64),
  size: 11,
  notes: "test",
});

function bucket(extra = {}) {
  return fakeBucket({
    "android/latest.json": { body: LATEST, size: LATEST.length },
    "android/releases/3/app.apk": {
      body: "APKBYTES!!!",
      size: 11,
      httpMetadata: { contentType: "application/vnd.android.package-archive" },
    },
    ...extra,
  });
}

const req = (url, method = "GET") => new Request(url, { method });

test("serves latest manifest as no-store JSON", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/latest"),
    { TRANSCRIPTS: bucket() },
  );
  assert.ok(res);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("cache-control"), "no-store");
  assert.match(res.headers.get("content-type"), /application\/json/);
  const parsed = JSON.parse(await res.text());
  assert.equal(parsed.versionCode, 3);
  assert.equal(parsed.versionName, "1.2.0");
});

test("serves versioned APK with immutable private cache and exact length", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/3/app.apk"),
    { TRANSCRIPTS: bucket() },
  );
  assert.ok(res);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("content-length"), "11");
  assert.match(res.headers.get("cache-control"), /immutable/);
  assert.match(res.headers.get("cache-control"), /private/);
  assert.equal(
    res.headers.get("content-type"),
    "application/vnd.android.package-archive",
  );
  assert.equal(await res.text(), "APKBYTES!!!");
});

test("missing latest object returns 404 not_found", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/latest"),
    { TRANSCRIPTS: fakeBucket({}) },
  );
  assert.ok(res);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error, "not_found");
});

test("missing APK object returns 404", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/99/app.apk"),
    { TRANSCRIPTS: bucket() },
  );
  assert.ok(res);
  assert.equal(res.status, 404);
});

test("non-updater paths return null so the caller keeps routing", async () => {
  for (const p of [
    "/api/transcripts",
    "/api/android/latest/extra",
    "/api/android/releases/3/app.apk/extra",
    "/index.html",
    "/",
  ]) {
    const res = await handleAndroidUpdates(req(`https://example.com${p}`), {
      TRANSCRIPTS: bucket(),
    });
    assert.equal(res, null, `expected null for ${p}`);
  }
});

test("rejects arbitrary and malformed R2 paths (no traversal)", async () => {
  const malicious = [
    "/api/android/releases/../latest/app.apk",
    "/api/android/releases/-1/app.apk",
    "/api/android/releases/03/app.apk",
    "/api/android/releases/%2e%2e/app.apk",
    "/api/android/releases/3/app.apk%00",
    "/api/android/latest.json",
  ];
  for (const p of malicious) {
    const res = await handleAndroidUpdates(req(`https://example.com${p}`), {
      TRANSCRIPTS: bucket(),
    });
    assert.equal(res, null, `expected null for ${p}`);
  }
});

test("dot-segment traversal is normalised by URL parsing to a safe route", async () => {
  // The WHATWG URL parser collapses /3/../3/ to /3/, so the R2 key built is
  // android/releases/3/app.apk and never an attacker-chosen object.
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/3/../3/app.apk"),
    { TRANSCRIPTS: bucket() },
  );
  assert.ok(res);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), "APKBYTES!!!");
});

test("non-GET methods are not handled here", async () => {
  for (const method of ["POST", "PUT", "DELETE", "HEAD"]) {
    const res = await handleAndroidUpdates(
      req("https://example.com/api/android/latest", method),
      { TRANSCRIPTS: bucket() },
    );
    assert.equal(res, null, `expected null for ${method}`);
  }
});

test("missing R2 binding fails closed with 502", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/latest"),
    {},
  );
  assert.ok(res);
  assert.equal(res.status, 502);
});

test("R2 get throwing is caught and reported as 502 (never throws)", async () => {
  const throwing = {
    async get() {
      throw new Error("boom");
    },
  };
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/3/app.apk"),
    { TRANSCRIPTS: throwing },
  );
  assert.ok(res);
  assert.equal(res.status, 502);
});

test("falls back to get() when head() is unavailable is moot (single get path)", async () => {
  // The handler only calls get(); a binding with no head() must still work.
  const raw = {
    async get(key) {
      if (key !== "android/releases/3/app.apk") return null;
      return {
        body: "APKBYTES!!!",
        size: 11,
        async text() {
          return "APKBYTES!!!";
        },
      };
    },
  };
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/3/app.apk"),
    { TRANSCRIPTS: raw },
  );
  assert.ok(res);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("content-length"), "11");
  assert.equal(await res.text(), "APKBYTES!!!");
});

test("release 0 is rejected (app requires positive version codes)", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/0/app.apk"),
    {
      TRANSCRIPTS: fakeBucket({
        "android/releases/0/app.apk": { body: "x", size: 1 },
      }),
    },
  );
  assert.equal(res, null);
});

test("APK MIME is forced even when stored metadata lies", async () => {
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/3/app.apk"),
    {
      TRANSCRIPTS: fakeBucket({
        "android/releases/3/app.apk": {
          body: "APKBYTES!!!",
          size: 11,
          httpMetadata: { contentType: "text/html" },
        },
      }),
    },
  );
  assert.ok(res);
  assert.equal(res.status, 200);
  assert.equal(
    res.headers.get("content-type"),
    "application/vnd.android.package-archive",
  );
});

test("encoded characters cannot smuggle a non-numeric release key", async () => {
  // %2d decodes to '-', which fails the strict integer regex.
  const res = await handleAndroidUpdates(
    req("https://example.com/api/android/releases/%2d1/app.apk"),
    { TRANSCRIPTS: bucket() },
  );
  assert.equal(res, null);
});