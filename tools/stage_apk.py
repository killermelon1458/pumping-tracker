#!/usr/bin/env python3
"""Verify and stage an APK using the configured Gradle variant's versionName. Never signs."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from prepare_beta_release import VerificationError, run, sdk_tools


BASE_ID = "dev.killermelon1458.pumping"


def artifact_name(version):
    if not re.fullmatch(r"\d+\.\d+\.\d+-(dev|beta)", version):
        raise VerificationError("Variant versionName must be MAJOR.MINOR.REVISION-dev or -beta.")
    return f"Pumping-{version}.apk"


def artifact_directory(base, version, channel):
    artifact_name(version)  # Validate before using versionName as a path component.
    return base / version if channel == "debug" else base


def verify_metadata(badging, application_id, version, code, channel):
    artifact_name(version)
    expected_id = BASE_ID + (".debug" if channel == "debug" else "")
    suffix = "-dev" if channel == "debug" else "-beta"
    if application_id != expected_id or not version.endswith(suffix) or code <= 0:
        raise VerificationError("Configured variant identity/channel is inconsistent with VERSIONING.md.")
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not match or match.groups() != (application_id, str(code), version):
        raise VerificationError("Embedded APK identity/version differs from the configured Gradle variant.")
    if ("application-debuggable" in badging) != (channel == "debug") or "application-testOnly" in badging:
        raise VerificationError("APK debuggable/test-only flags do not match the requested variant.")


def stage_file(source, directory, version):
    """Atomic creation; an existing different artifact is never replaced, including races."""
    target = directory / artifact_name(version)
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    directory.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise VerificationError(f"Version collision: {target.name} already contains a different APK. "
                                    "Explicitly resolve/archive the existing build or advance the revision. Nothing was replaced.")
        return target, digest
    with tempfile.NamedTemporaryFile(dir=directory, prefix=".staging-", suffix=".tmp", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    try:
        shutil.copyfile(source, temporary_path)
        if hashlib.sha256(temporary_path.read_bytes()).hexdigest() != digest:
            raise VerificationError("APK changed during staging; no artifact was replaced.")
        try:
            os.link(temporary_path, target)
        except FileExistsError:
            if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                raise VerificationError("Version collision during staging; existing APK preserved.")
    finally:
        temporary_path.unlink(missing_ok=True)
    return target, digest


def atomic_text(path, value):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=".metadata-", delete=False) as file:
        file.write(value)
        temporary = Path(file.name)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--channel", choices=("debug", "release"), required=True)
    parser.add_argument("--sdk", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        signer, aapt = sdk_tools(root, args.sdk)
        signature = run([str(signer), "verify", "--verbose", "--print-certs", str(args.apk)])
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", signature)
        if len(certificates) != 1:
            raise VerificationError("APK must have one verified signing certificate.")
        if args.channel == "release" and re.search(r"CN\s*=\s*Android Debug", signature, re.I):
            raise VerificationError("Release APK cannot use the development/debug certificate.")
        def verify(path):
            verify_metadata(run([str(aapt), "dump", "badging", str(path)]), args.application_id,
                            args.version_name, args.version_code, args.channel)
        verify(args.apk)
        directory = artifact_directory(args.output, args.version_name, args.channel)
        target, digest = stage_file(args.apk, directory, args.version_name)
        verify(target)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise VerificationError("Staged APK checksum mismatch.")
        commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True)
        state = subprocess.run(["git", "-C", str(root), "status", "--porcelain"], capture_output=True, text=True)
        metadata = dict(apk=target.name, apk_sha256=digest, application_id=args.application_id,
                        version_name=args.version_name, version_code=args.version_code,
                        channel=args.channel, signing_certificate_sha256=certificates[0].lower(),
                        verified_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        source_head=commit.stdout.strip() if commit.returncode == 0 else None,
                        working_tree_dirty=bool(state.stdout.strip()) if state.returncode == 0 else None,
                        tests="Not run by staging; record actual results in BUILD_INFO.md.")
        atomic_text(target.with_suffix(".apk.sha256"), f"{digest}  {target.name}\n")
        atomic_text(target.with_suffix(".build.json"), json.dumps(metadata, indent=2) + "\n")
        print(f"Verified APK staged: {target}\nSHA-256: {digest}")
    except (VerificationError, OSError) as error:
        parser.exit(1, f"APK staging failed: {error}\n")


if __name__ == "__main__":
    main()
