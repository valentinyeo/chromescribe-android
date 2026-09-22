#!/usr/bin/env python3
"""Unit tests for scripts/release.py publication ordering and guards.

These are pure-JVM-free, no-network tests. They exercise the injected
Publisher and the pure parsing/version helpers. Run with:

    python3 -m unittest scripts.test_release -v
    # or
    python3 -m pytest scripts/test_release.py
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent


def _load_release_module():
    spec = importlib.util.spec_from_file_location("release", _HERE / "release.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    # dataclasses inspects sys.modules[cls.__module__], so register first.
    sys.modules["release"] = module
    spec.loader.exec_module(module)
    return module


release = _load_release_module()
sha256_bytes = release.sha256_bytes
Publisher = release.Publisher
ReleaseError = release.ReleaseError
ObjectAbsent = release.ObjectAbsent
ReadBackError = release.ReadBackError
LATEST_KEY = release.LATEST_KEY
apk_object_key = release.apk_object_key


class FakeR2:
    """In-memory R2 with optional failure injection."""

    def __init__(self):
        self.store: dict[str, bytes] = {}
        self.upload_calls: list[str] = []
        self.fail_upload: set[str] = set()
        self.fail_readback: set[str] = set()

    def upload(self, key, path, content_type, cache_control):
        if key in self.fail_upload:
            raise RuntimeError(f"upload failed for {key}")
        if not path.is_file():
            raise RuntimeError(f"missing local file for {key}")
        self.upload_calls.append(key)
        self.store[key] = path.read_bytes()

    def readback(self, key):
        if key in self.fail_readback:
            raise ReadBackError(f"read-back failed for {key}")
        if key not in self.store:
            raise ObjectAbsent(key)
        return self.store[key]

    def fetch_existing_apk(self, key):
        return self.store.get(key)

    def fetch_latest(self):
        raw = self.store.get(LATEST_KEY)
        if raw is None:
            return None
        import json

        return json.loads(raw)


def make_manifest(version_code, body: bytes, notes="n"):
    return {
        "versionCode": version_code,
        "versionName": f"1.{version_code}.0",
        "minSdk": 24,
        "apkPath": f"/api/android/releases/{version_code}/app.apk",
        "sha256": sha256_bytes(body),
        "size": len(body),
        "notes": notes,
    }


class PublisherOrderingTests(unittest.TestCase):
    def setUp(self):
        self.r2 = FakeR2()
        self.publisher = Publisher(
            upload=self.r2.upload,
            readback=self.r2.readback,
            fetch_existing_apk=self.r2.fetch_existing_apk,
            fetch_latest=self.r2.fetch_latest,
        )
        self.tmp = tempfile.TemporaryDirectory()
        self.apk = Path(self.tmp.name) / "app.apk"
        self.apk.write_bytes(b"APK-BYTES-1")
        self.manifest_path = Path(self.tmp.name) / "latest.json"

    def tearDown(self):
        self.tmp.cleanup()

    def publish(self, manifest):
        import json

        body = (json.dumps(manifest) + "\n").encode()
        self.manifest_path.write_bytes(body)
        self.publisher.publish(
            apk=self.apk,
            manifest=manifest,
            manifest_bytes=body,
            manifest_path=self.manifest_path,
        )

    def test_apk_uploaded_before_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        self.publish(m)
        apk_key = apk_object_key(1)
        self.assertLess(
            self.r2.upload_calls.index(apk_key),
            self.r2.upload_calls.index(LATEST_KEY),
        )
        # Events document the readback between the two uploads.
        self.assertIn("upload:apk", self.publisher.events)
        self.assertIn("readback:apk", self.publisher.events)
        self.assertIn("upload:manifest", self.publisher.events)
        self.assertLess(
            self.publisher.events.index("readback:apk"),
            self.publisher.events.index("upload:manifest"),
        )

    def test_apk_upload_failure_never_touches_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        self.r2.fail_upload.add(apk_object_key(1))
        with self.assertRaises(RuntimeError):
            self.publish(m)
        self.assertNotIn(LATEST_KEY, self.r2.upload_calls)

    def test_readback_mismatch_never_touches_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        # Corrupt the stored bytes behind the publisher's back.
        original_upload = self.r2.upload

        def corrupting_upload(key, path, ct, cc):
            original_upload(key, path, ct, cc)
            if key == apk_object_key(1):
                self.r2.store[key] = b"CORRUPTED"

        self.publisher.upload = corrupting_upload
        with self.assertRaises(ReleaseError):
            self.publish(m)
        self.assertNotIn(LATEST_KEY, self.r2.upload_calls)

    def test_readback_failure_never_touches_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        self.r2.fail_readback.add(apk_object_key(1))
        with self.assertRaises(ReadBackError):
            self.publish(m)
        self.assertNotIn(LATEST_KEY, self.r2.upload_calls)

    def test_size_mismatch_never_touches_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        m["size"] = m["size"] + 1  # lie about size
        with self.assertRaises(ReleaseError):
            self.publish(m)
        self.assertNotIn(LATEST_KEY, self.r2.upload_calls)

    def test_same_hash_existing_object_is_idempotent_reuse(self):
        m = make_manifest(1, self.apk.read_bytes())
        self.publish(m)
        calls_after_first = list(self.r2.upload_calls)
        # Second run with identical bytes: should not re-upload the APK.
        self.publish(m)
        apk_key = apk_object_key(1)
        self.assertEqual(self.r2.upload_calls.count(apk_key), 1)
        self.assertIn("reuse:apk", self.publisher.events)
        # Manifest is still refreshed last.
        self.assertEqual(self.r2.upload_calls[0], apk_key)
        self.assertEqual(self.r2.upload_calls[-1], LATEST_KEY)
        self.assertEqual(calls_after_first.count(apk_key), 1)

    def test_different_hash_same_code_fails_before_manifest(self):
        m = make_manifest(1, self.apk.read_bytes())
        self.publish(m)
        # New build claims same code but different bytes.
        self.apk.write_bytes(b"APK-BYTES-DIFFERENT")
        m2 = make_manifest(1, self.apk.read_bytes())
        with self.assertRaises(ReleaseError):
            self.publish(m2)
        # Manifest from the first publish remains untouched (only one upload).
        self.assertEqual(self.r2.upload_calls.count(LATEST_KEY), 1)

    def test_latest_never_moves_backwards(self):
        self.publish(make_manifest(5, self.apk.read_bytes()))
        self.apk.write_bytes(b"APK-BYTES-4")
        with self.assertRaises(ReleaseError):
            self.publish(make_manifest(4, self.apk.read_bytes()))
        self.assertEqual(self.r2.upload_calls.count(LATEST_KEY), 1)

    def test_same_version_republish_allowed(self):
        self.publish(make_manifest(5, self.apk.read_bytes()))
        self.publish(make_manifest(5, self.apk.read_bytes()))
        self.assertEqual(self.r2.upload_calls.count(LATEST_KEY), 2)

    def test_newer_version_publishes_apk_then_manifest(self):
        self.publish(make_manifest(1, self.apk.read_bytes()))
        self.apk.write_bytes(b"APK-BYTES-2")
        self.publish(make_manifest(2, self.apk.read_bytes()))
        self.assertEqual(self.r2.upload_calls[-2:], [apk_object_key(2), LATEST_KEY])


class ManifestTests(unittest.TestCase):
    def test_build_manifest_path_and_fields(self):
        metadata = {
            "package": "org.crolab.chromescribe",
            "versionCode": 3,
            "versionName": "1.2.0",
            "minSdk": 24,
        }
        m = release.build_manifest(metadata, "a" * 64, 123, "notes")
        self.assertEqual(m["versionCode"], 3)
        self.assertEqual(m["versionName"], "1.2.0")
        self.assertEqual(m["minSdk"], 24)
        self.assertEqual(m["apkPath"], "/api/android/releases/3/app.apk")
        self.assertEqual(m["sha256"], "a" * 64)
        self.assertEqual(m["size"], 123)
        self.assertEqual(m["notes"], "notes")

    def test_aapt_parse_extracts_fields(self):
        out = (
            "package: name='org.crolab.chromescribe' versionCode='3' "
            "versionName='1.2.0' platformBuildVersionName='14'\n"
            "sdkVersion:'24'\n"
            "targetSdkVersion:'34'\n"
        )
        parsed = release.parse_aapt_badging(out)
        self.assertEqual(parsed["versionCode"], 3)
        self.assertEqual(parsed["versionName"], "1.2.0")
        self.assertEqual(parsed["minSdk"], 24)

    def test_aapt_parse_rejects_wrong_package(self):
        out = "package: name='com.evil.app' versionCode='3' versionName='1.2.0'\nsdkVersion:'24'\n"
        with self.assertRaises(ReleaseError):
            release.parse_aapt_badging(out)

    def test_aapt_parse_rejects_incomplete(self):
        out = "package: name='org.crolab.chromescribe' versionCode='3'\n"
        with self.assertRaises(ReleaseError):
            release.parse_aapt_badging(out)


class ConfiguredVersionTests(unittest.TestCase):
    def test_read_configured_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            (repo / "app").mkdir()
            (repo / "app" / "build.gradle").write_text(
                "android {\n  defaultConfig {\n"
                "    versionCode 3\n    versionName '1.2.0'\n  }\n}\n"
            )
            v = release.read_configured_version(repo)
            self.assertEqual(v["versionCode"], 3)
            self.assertEqual(v["versionName"], "1.2.0")

    def test_verify_apk_matches_configured_rejects_mismatch(self):
        configured = {"versionCode": 3, "versionName": "1.2.0"}
        release.verify_apk_matches_configured(
            {"versionCode": 3, "versionName": "1.2.0"}, configured
        )
        with self.assertRaises(ReleaseError):
            release.verify_apk_matches_configured(
                {"versionCode": 2, "versionName": "1.2.0"}, configured
            )
        with self.assertRaises(ReleaseError):
            release.verify_apk_matches_configured(
                {"versionCode": 3, "versionName": "1.1.0"}, configured
            )


class SecretHandlingTests(unittest.TestCase):
    def test_apksigner_env_never_puts_password_in_flags(self):
        secrets = release.SigningSecrets(
            alias="release", password="topsecretpw", keystore=Path("/tmp/k.keystore")
        )
        env, flags = secrets.apksigner_env()
        self.assertEqual(env["CS_KS_PASS"], "topsecretpw")
        joined = " ".join(flags)
        self.assertNotIn("topsecretpw", joined)
        self.assertIn("env:CS_KS_PASS", joined)
        self.assertIn("env:CS_KEY_PASS", joined)


if __name__ == "__main__":
    unittest.main()