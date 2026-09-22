#!/usr/bin/env python3
"""ChromeScribe Android release pipeline.

One command to build, test, lint, sign, verify and (optionally) publish an
APK plus its update manifest to Cloudflare R2.

Safety model
------------
* Local build is the default. ``--publish`` is required to touch R2.
* Signing secrets are read from a file *outside* the repository (default
  ``~/.config/chromescribe-android/signing.json``, override with
  ``CHROMESCRIBE_SIGNING_JSON`` / ``--signing-json``). Secret values are never
  printed, never placed on a command line, and never written to the manifest.
* apksigner receives keystore/key passwords through environment variables
  only (``env:NAME`` syntax), never argv.
* The versioned APK is uploaded FIRST, its bytes are read back and hashed, and
  only after that verification succeeds is the ``latest.json`` manifest
  overwritten LAST. A failed APK upload or failed read-back aborts before the
  manifest changes, so clients are never pointed at a missing or corrupt APK.
* Artifacts land under ``dist/`` which is gitignored.

This module is importable so the ordering logic can be unit tested with fake
uploaders; the ``main`` entry point performs the real side effects.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
DIST_DIR = REPO_ROOT / "dist"
DEFAULT_SIGNING_JSON = Path.home() / ".config" / "chromescribe-android" / "signing.json"
DEFAULT_KEYSTORE = Path.home() / ".config" / "chromescribe-android" / "release.keystore"

ACCOUNT_ID = "7865043d9ae730882b77f3d5d670c158"
BUCKET = "chromescribe-transcripts"
LATEST_KEY = "android/latest.json"
APK_MIME = "application/vnd.android.package-archive"

DEFAULT_ANDROID_SDK = Path("/home/valentin/android-sdk")
BUILD_TOOLS_VERSION = "34.0.0"

PACKAGE_NAME = "org.crolab.chromescribe"


class ReleaseError(RuntimeError):
    """Raised for any expected, user-actionable release failure."""


# ---------------------------------------------------------------------------
# Small process helpers
# ---------------------------------------------------------------------------


def _run(
    argv: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    capture: bool = True,
    check: bool = True,
) -> subprocess.CompletedProcess:
    """Run a subprocess. Never echo secret-bearing argv."""
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    proc = subprocess.run(
        argv,
        cwd=str(cwd) if cwd else None,
        env=merged_env,
        capture_output=capture,
        text=True,
    )
    if check and proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        raise ReleaseError(
            f"command failed ({proc.returncode}): {argv[0]} ...\n{detail}"
        )
    return proc


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------------------
# Signing secrets (never logged)
# ---------------------------------------------------------------------------


@dataclass
class SigningSecrets:
    alias: str
    password: str
    keystore: Path

    def apksigner_env(self) -> tuple[dict[str, str], list[str]]:
        """Return (env additions, argv password flags) using env: indirection.

        The returned flags are of the form ``--ks-pass env:CS_KS_PASS`` so the
        actual password never appears in argv or logs.
        """
        env = {"CS_KS_PASS": self.password, "CS_KEY_PASS": self.password}
        flags = [
            "--ks-pass",
            "env:CS_KS_PASS",
            "--key-pass",
            "env:CS_KEY_PASS",
        ]
        return env, flags


def load_signing_secrets(signing_json: Path, keystore: Path) -> SigningSecrets:
    if not signing_json.is_file():
        raise ReleaseError(
            f"signing secrets not found at {signing_json} "
            "(expected a local, gitignored file with alias + password)"
        )
    if not keystore.is_file():
        raise ReleaseError(f"keystore not found at {keystore}")
    try:
        data = json.loads(signing_json.read_text())
    except (OSError, ValueError) as exc:
        raise ReleaseError(f"could not parse signing secrets: {exc}") from exc
    alias = data.get("alias")
    password = data.get("password")
    if not alias or not password:
        raise ReleaseError("signing secrets must contain non-empty alias and password")
    return SigningSecrets(alias=str(alias), password=str(password), keystore=keystore)


# ---------------------------------------------------------------------------
# SDK / build tools discovery
# ---------------------------------------------------------------------------


def android_sdk_root() -> Path:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(key)
        if val:
            return Path(val)
    return DEFAULT_ANDROID_SDK


def build_tool(name: str, sdk_root: Path | None = None) -> Path:
    root = sdk_root or android_sdk_root()
    candidate = root / "build-tools" / BUILD_TOOLS_VERSION / name
    if candidate.is_file():
        return candidate
    found = shutil.which(name)
    if found:
        return Path(found)
    raise ReleaseError(f"required build tool not found: {name} (looked in {candidate})")


# ---------------------------------------------------------------------------
# APK metadata extraction (from the signed APK itself)
# ---------------------------------------------------------------------------


def parse_aapt_badging(output: str) -> dict:
    """Parse `aapt dump badging` output for the fields the manifest needs.

    Pure and unit-testable. Raises ReleaseError when mandatory fields are
    missing so we never publish a manifest built from incomplete data.
    """
    version_code = None
    version_name = None
    min_sdk = None
    package = None
    for line in output.splitlines():
        if line.startswith("package:"):
            for field in line[len("package:"):].strip().split():
                if field.startswith("name="):
                    package = field[len("name="):].strip("'")
                elif field.startswith("versionCode="):
                    raw = field[len("versionCode="):].strip("'")
                    # aapt may append a suffix like 3 (or 3.0); keep leading int.
                    digits = ""
                    for ch in raw:
                        if ch.isdigit():
                            digits += ch
                        else:
                            break
                    version_code = int(digits) if digits else None
                elif field.startswith("versionName="):
                    version_name = field[len("versionName="):].strip("'")
        elif line.startswith("sdkVersion:"):
            raw = line.split(":", 1)[1].strip().strip("'")
            if raw.isdigit():
                min_sdk = int(raw)

    missing = [
        name
        for name, val in (
            ("versionCode", version_code),
            ("versionName", version_name),
            ("minSdk", min_sdk),
            ("package", package),
        )
        if val is None
    ]
    if missing:
        raise ReleaseError(f"APK metadata incomplete, missing: {', '.join(missing)}")
    if package != PACKAGE_NAME:
        raise ReleaseError(
            f"APK package is {package!r}, expected {PACKAGE_NAME!r}"
        )
    return {
        "package": package,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": min_sdk,
    }


def extract_apk_metadata(apk: Path, sdk_root: Path | None = None) -> dict:
    aapt = build_tool("aapt", sdk_root)
    proc = _run([str(aapt), "dump", "badging", str(apk)])
    return parse_aapt_badging(proc.stdout)


# ---------------------------------------------------------------------------
# Build / sign / verify
# ---------------------------------------------------------------------------


def gradle_build(repo: Path, sdk_root: Path, tasks: list[str]) -> None:
    env = {"ANDROID_HOME": str(sdk_root), "ANDROID_SDK_ROOT": str(sdk_root)}
    _run([str(repo / "gradlew"), *tasks], cwd=repo, env=env)


def zipalign(input_apk: Path, output_apk: Path, sdk_root: Path | None = None) -> Path:
    tool = build_tool("zipalign", sdk_root)
    output_apk.parent.mkdir(parents=True, exist_ok=True)
    _run([str(tool), "-p", "-f", "4", str(input_apk), str(output_apk)])
    return output_apk


def apksigner_sign(
    aligned_apk: Path,
    signed_apk: Path,
    secrets: SigningSecrets,
    sdk_root: Path | None = None,
) -> Path:
    tool = build_tool("apksigner", sdk_root)
    env, pass_flags = secrets.apksigner_env()
    signed_apk.parent.mkdir(parents=True, exist_ok=True)
    _run(
        [
            str(tool),
            "sign",
            "--ks",
            str(secrets.keystore),
            "--ks-key-alias",
            secrets.alias,
            *pass_flags,
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--out",
            str(signed_apk),
            str(aligned_apk),
        ],
        env=env,
    )
    return signed_apk


def apksigner_verify(signed_apk: Path, sdk_root: Path | None = None) -> None:
    tool = build_tool("apksigner", sdk_root)
    proc = _run([str(tool), "verify", "--print-certs", str(signed_apk)],
                check=False)
    if proc.returncode != 0:
        raise ReleaseError("apksigner verify failed: APK signature is not valid")
    if "Signer #1 certificate SHA-256 digest" not in proc.stdout:
        raise ReleaseError("apksigner verify produced no signer digest")


def apksigner_cert_sha256(signed_apk: Path, sdk_root: Path | None = None) -> str:
    """Return the signer certificate SHA-256 digest (non-secret, pins identity)."""
    tool = build_tool("apksigner", sdk_root)
    proc = _run([str(tool), "verify", "--print-certs", str(signed_apk)])
    for line in proc.stdout.splitlines():
        if "Signer #1 certificate SHA-256 digest" in line:
            return line.split(":", 1)[1].strip()
    raise ReleaseError("could not read signer certificate digest")


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------


def build_manifest(metadata: dict, apk_sha256: str, apk_size: int, notes: str) -> dict:
    version_code = int(metadata["versionCode"])
    return {
        "versionCode": version_code,
        "versionName": str(metadata["versionName"]),
        "minSdk": int(metadata["minSdk"]),
        "apkPath": f"/api/android/releases/{version_code}/app.apk",
        "sha256": apk_sha256,
        "size": int(apk_size),
        "notes": notes,
    }


def apk_object_key(version_code: int) -> str:
    return f"android/releases/{version_code}/app.apk"


# ---------------------------------------------------------------------------
# Publishing (dependency-injected for testability)
# ---------------------------------------------------------------------------

UploadFn = Callable[[str, Path, str, str], None]


class ObjectAbsent(Exception):
    """The requested R2 object does not exist (distinct from failure)."""


class ReadBackError(ReleaseError):
    """Read-back failed for a reason other than absence (network/auth/etc)."""


@dataclass
class Publisher:
    """Publishes APK then manifest, in that order, with verification between.

    The upload/readback/fetch-latest functions are injected so ordering,
    immutability and fail-closed behaviour can be unit tested with no network.

    Invariants enforced here:
    * An existing versioned object is read before upload. Absence (``None``) is
      treated as a fresh slot; the same bytes make an upload idempotent; an
      existing object with *different* bytes aborts, because published
      releases are immutable.
    * ``latest.json`` must never move backwards. A manifest with a lower
      versionCode than the currently published one aborts.
    * The APK is uploaded and read back + hashed before ``latest.json`` is
      touched. Any APK upload/read-back failure aborts before the manifest
      changes.
    """

    upload: UploadFn
    readback: Callable[[str], bytes]
    fetch_existing_apk: Callable[[str], Optional[bytes]]
    fetch_latest: Callable[[], Optional[dict]]
    events: list[str] = field(default_factory=list)

    def _existing_apk(self, key: str) -> Optional[bytes]:
        try:
            self.events.append("readback:existing-apk")
            return self.fetch_existing_apk(key)
        except ObjectAbsent:
            return None

    def publish(
        self,
        *,
        apk: Path,
        manifest: dict,
        manifest_bytes: bytes,
        manifest_path: Path,
    ) -> None:
        version_code = int(manifest["versionCode"])
        apk_key = apk_object_key(version_code)
        expected_sha = manifest["sha256"]

        # The bytes we upload must be exactly the bytes that produced the
        # manifest we are about to advertise; guard against a stale file.
        if manifest_path.read_bytes() != manifest_bytes:
            raise ReleaseError(
                "on-disk manifest does not match the bytes to publish; aborting"
            )

        # 0. Never let the advertised latest version move backwards.
        existing_latest = self.fetch_latest()
        if existing_latest is not None:
            current_code = int(existing_latest.get("versionCode", 0))
            if version_code < current_code:
                raise ReleaseError(
                    "refusing to publish a lower versionCode than latest.json "
                    f"({version_code} < {current_code})"
                )

        # 1. Immutability: read any existing versioned object first.
        existing = self._existing_apk(apk_key)
        if existing is not None:
            existing_sha = sha256_bytes(existing)
            if existing_sha == expected_sha:
                self.events.append("reuse:apk")
            else:
                raise ReleaseError(
                    f"release {version_code} already exists with different bytes; "
                    "published releases are immutable (bump versionCode)"
                )
        else:
            # 2. Upload the versioned APK first.
            self.events.append("upload:apk")
            self.upload(apk_key, apk, APK_MIME, "private, max-age=31536000, immutable")

        # 3. Read the object back and verify the bytes match exactly.
        self.events.append("readback:apk")
        downloaded = self.readback(apk_key)
        actual_sha = sha256_bytes(downloaded)
        if actual_sha != expected_sha:
            raise ReleaseError(
                "APK read-back hash mismatch; refusing to publish latest.json "
                f"(expected {expected_sha[:12]}…, got {actual_sha[:12]}…)"
            )
        if len(downloaded) != int(manifest["size"]):
            raise ReleaseError(
                "APK read-back size mismatch; refusing to publish latest.json"
            )

        # 4. Latest manifest LAST, only after the APK is confirmed in place.
        self.events.append("upload:manifest")
        self.upload(LATEST_KEY, manifest_path, "application/json", "no-store")


def wrangler_upload(key: str, path: Path, content_type: str, cache_control: str) -> None:
    object_path = f"{BUCKET}/{key}"
    argv = [
        "wrangler",
        "r2",
        "object",
        "put",
        object_path,
        "--remote",
        "--file",
        str(path),
        "--content-type",
        content_type,
        "--cache-control",
        cache_control,
    ]
    env = {"CLOUDFLARE_ACCOUNT_ID": ACCOUNT_ID}
    _run(argv, env=env)


def wrangler_readback(key: str) -> bytes:
    """Read an object back, distinguishing absence from other failures.

    A missing object raises ObjectAbsent; any other failure (network, auth,
    permissions) raises ReadBackError so callers never mistake an outage for
    "absent" and then overwrite a published release.
    """
    with tempfile.TemporaryDirectory() as tmp:
        dest = Path(tmp) / "readback.bin"
        argv = [
            "wrangler",
            "r2",
            "object",
            "get",
            f"{BUCKET}/{key}",
            "--remote",
            "--file",
            str(dest),
        ]
        env = {"CLOUDFLARE_ACCOUNT_ID": ACCOUNT_ID}
        proc = _run(argv, env=env, check=False)
        combined = f"{proc.stdout or ''}\n{proc.stderr or ''}"
        if proc.returncode != 0:
            if _looks_absent(combined):
                raise ObjectAbsent(key)
            raise ReadBackError(f"read-back failed for {key}: {combined.strip()}")
        if not dest.is_file():
            raise ReadBackError(f"read-back produced no file for {key}")
        return dest.read_bytes()


def _looks_absent(output: str) -> bool:
    """Heuristically detect wrangler's 'object does not exist' response."""
    lowered = output.lower()
    markers = (
        "not found",
        "does not exist",
        "no such object",
        "the specified key does not exist",
        "404",
    )
    return any(marker in lowered for marker in markers)


def wrangler_fetch_existing_apk(key: str) -> Optional[bytes]:
    """Return existing object bytes, or None when the object is absent."""
    try:
        return wrangler_readback(key)
    except ObjectAbsent:
        return None


def wrangler_fetch_latest() -> Optional[dict]:
    """Return the parsed current latest.json, or None if it is absent."""
    try:
        raw = wrangler_readback(LATEST_KEY)
    except ObjectAbsent:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        # A corrupt/unreadable manifest must not silently allow a backwards
        # publish; treat it as unknown and let the caller decide. Here we fail
        # closed by surfacing the error.
        raise ReadBackError("existing latest.json is not valid JSON")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Build, test, lint, sign and verify the ChromeScribe APK. "
            "Publishes to R2 only with --publish."
        )
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help="Upload the signed APK and latest.json to R2 (default: local build only).",
    )
    parser.add_argument(
        "--release-notes",
        type=Path,
        default=None,
        help="Path to a release notes text file stored in the manifest notes field.",
    )
    parser.add_argument(
        "--signing-json",
        type=Path,
        default=Path(os.environ.get("CHROMESCRIBE_SIGNING_JSON", DEFAULT_SIGNING_JSON)),
        help="Path to the local signing secrets file (never printed).",
    )
    parser.add_argument(
        "--keystore",
        type=Path,
        default=Path(os.environ.get("CHROMESCRIBE_KEYSTORE", DEFAULT_KEYSTORE)),
        help="Path to the local release keystore.",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=None,
        help=(
            "Reuse an explicit signed APK path instead of building (its "
            "versionName/versionCode must match build.gradle)."
        ),
    )
    return parser


def read_configured_version(repo: Path = REPO_ROOT) -> dict:
    """Read versionCode/versionName from app/build.gradle.

    Pure text parsing (no Gradle invocation) so `--apk` reuse can be sanity
    checked against the source of truth before anything is published.
    """
    gradle = repo / "app" / "build.gradle"
    if not gradle.is_file():
        raise ReleaseError(f"app/build.gradle not found at {gradle}")
    text = gradle.read_text()
    import re

    code_match = re.search(r"versionCode\s+(\d+)", text)
    name_match = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", text)
    if not code_match or not name_match:
        raise ReleaseError("could not read versionCode/versionName from app/build.gradle")
    return {
        "versionCode": int(code_match.group(1)),
        "versionName": name_match.group(1),
    }


def verify_apk_matches_configured(metadata: dict, configured: dict) -> None:
    """Abort if a reused APK does not match the version in build.gradle.

    This prevents accidentally republishing a stale artifact under a new
    manifest (or the reverse).
    """
    if int(metadata["versionCode"]) != int(configured["versionCode"]):
        raise ReleaseError(
            "APK versionCode does not match app/build.gradle "
            f"({metadata['versionCode']} != {configured['versionCode']})"
        )
    if str(metadata["versionName"]) != str(configured["versionName"]):
        raise ReleaseError(
            "APK versionName does not match app/build.gradle "
            f"({metadata['versionName']!r} != {configured['versionName']!r})"
        )


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def run_release(args: argparse.Namespace) -> int:
    sdk_root = android_sdk_root()
    dist = DIST_DIR
    dist.mkdir(parents=True, exist_ok=True)

    configured = read_configured_version(REPO_ROOT)

    if args.apk is not None:
        # Explicit reuse: never guess by glob (lexicographic 1.9 > 1.10).
        final_apk = args.apk
        if not final_apk.is_absolute():
            final_apk = (REPO_ROOT / final_apk).resolve()
        if not final_apk.is_file():
            raise ReleaseError(f"--apk not found: {final_apk}")
        metadata = extract_apk_metadata(final_apk, sdk_root)
        verify_apk_matches_configured(metadata, configured)
    else:
        print("[release] gradle test lint")
        gradle_build(REPO_ROOT, sdk_root, ["test", "lint"])
        print("[release] gradle assembleRelease")
        gradle_build(REPO_ROOT, sdk_root, ["assembleRelease"])

        unsigned = (
            REPO_ROOT
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / "release"
            / "app-release-unsigned.apk"
        )
        if not unsigned.is_file():
            raise ReleaseError(f"unsigned release APK not found at {unsigned}")

        metadata = extract_apk_metadata(unsigned, sdk_root)
        verify_apk_matches_configured(metadata, configured)

        secrets = load_signing_secrets(args.signing_json, args.keystore)
        version_name = metadata["versionName"]
        final_apk = dist / f"ChromeScribe-{version_name}.apk"
        aligned = dist / "aligned.apk"

        print("[release] zipalign")
        zipalign(unsigned, aligned, sdk_root)
        print("[release] apksigner sign")
        apksigner_sign(aligned, final_apk, secrets, sdk_root)
        print("[release] apksigner verify")
        apksigner_verify(final_apk, sdk_root)
        try:
            aligned.unlink()
        except OSError:
            pass

    apk_sha = sha256_file(final_apk)
    apk_size = final_apk.stat().st_size
    cert_sha = apksigner_cert_sha256(final_apk, sdk_root)

    notes = ""
    if args.release_notes:
        notes_path = args.release_notes
        if not notes_path.is_absolute():
            notes_path = REPO_ROOT / notes_path
        if not notes_path.is_file():
            raise ReleaseError(f"release notes file not found: {notes_path}")
        notes = notes_path.read_text().strip()

    manifest = build_manifest(metadata, apk_sha, apk_size, notes)

    manifest_path = dist / "latest.json"
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    manifest_path.write_bytes(manifest_bytes)

    print(f"[release] APK: {_rel(final_apk)}")
    print(f"[release] versionCode={manifest['versionCode']} "
          f"versionName={manifest['versionName']} minSdk={manifest['minSdk']}")
    print(f"[release] sha256={apk_sha}")
    print(f"[release] size={apk_size}")
    print(f"[release] signer-cert-sha256={cert_sha}")
    print(f"[release] manifest: {_rel(manifest_path)}")

    if not args.publish:
        print("[release] dry run (local build only). Re-run with --publish to upload.")
        return 0

    publisher = Publisher(
        upload=wrangler_upload,
        readback=wrangler_readback,
        fetch_existing_apk=wrangler_fetch_existing_apk,
        fetch_latest=wrangler_fetch_latest,
    )
    print(f"[release] publishing APK first: {apk_object_key(manifest['versionCode'])}")
    publisher.publish(
        apk=final_apk,
        manifest=manifest,
        manifest_bytes=manifest_bytes,
        manifest_path=manifest_path,
    )
    print(f"[release] published APK then manifest; upload order: {publisher.events}")
    print("[release] done.")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    try:
        return run_release(args)
    except ReleaseError as exc:
        print(f"[release] ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())