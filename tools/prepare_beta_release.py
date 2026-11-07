#!/usr/bin/env python3
"""Verify an owner-signed release APK, then stage local beta files. Never signs or publishes."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

APP_ID = "dev.killermelon1458.pumping"
VERSION_NAME = "1.4.0-beta.1"
VERSION_CODE = 7
COMPONENTS = (
    "app.pumping.PumpingApp", "app.pumping.MainActivity",
    "app.pumping.reminders.AlarmReceiver", "app.pumping.reminders.RestoreReceiver",
)


class VerificationError(Exception):
    pass


def fingerprint(value):
    normalized = value.replace(":", "").replace(" ", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise VerificationError("Expected certificate SHA-256 must contain 64 hexadecimal digits.")
    return normalized


def verify_certificate(output, expected):
    if re.search(r"CN\s*=\s*Android Debug", output, re.I):
        raise VerificationError("Development/debug certificates cannot be published as beta releases.")
    values = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output)
    if len(values) != 1 or fingerprint(values[0]) != fingerprint(expected):
        raise VerificationError("APK signing certificate does not match the independently recorded permanent certificate.")
    return fingerprint(values[0])


def verify_identity(badging, manifest):
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != (APP_ID, str(VERSION_CODE), VERSION_NAME):
        raise VerificationError("APK application ID or beta version does not match the intended release.")
    def sdk(name):
        match = re.search(r"^" + name + r":'([0-9]+)'", badging, re.M)
        return int(match[1]) if match else None
    minimum = sdk("minSdkVersion") or sdk("sdkVersion")
    if minimum != 26 or sdk("targetSdkVersion") != 36:
        raise VerificationError("APK must support minSdk 26 and targetSdk 36.")
    if "compileSdkVersion='37'" not in badging or re.search(r"\bmaxSdkVersion", badging + manifest):
        raise VerificationError("APK must compile against API 37 without a maximum SDK restriction.")
    if "application-debuggable" in badging or "application-testOnly" in badging:
        raise VerificationError("Debuggable/test-only APKs cannot be staged as beta releases.")
    for attribute in ("debuggable", "testOnly"):
        if re.search(r"android:" + attribute + r"[^\n]*=(?:\(type 0x12\))?(?:0xffffffff|true)\b", manifest):
            raise VerificationError("APK manifest enables a development-only flag.")
    if any(component not in manifest for component in COMPONENTS):
        raise VerificationError("APK manifest does not register the expected application/activity/alarm components.")
    if "SyntheticBackupProvider" in manifest or "SyntheticTestApplication" in manifest:
        raise VerificationError("APK registers a test-only component.")
    return {"application_id": APP_ID, "version_name": VERSION_NAME, "version_code": VERSION_CODE,
            "min_sdk": minimum, "target_sdk": 36, "compile_sdk": 37, "debuggable": False}


def verify_contents(apk):
    forbidden_descriptors = (
        b"Lapp/pumping/SyntheticBackupProvider;", b"Lapp/pumping/SyntheticTestRunner;",
        b"Lapp/pumping/SyntheticTestApplication;", b"Landroidx/compose/ui/test/",
        b"Lorg/robolectric/",
    )
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            lower = entry.lower()
            if lower.endswith(".pumping") or "synthetic_fixture" in lower or "schema-v" in lower:
                raise VerificationError("APK includes a backup/test fixture.")
            if entry.endswith(".dex") and any(item in archive.read(entry) for item in forbidden_descriptors):
                raise VerificationError("APK includes development-only test code.")


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        # SDK tools receive no secrets; don't replay arbitrary process output into release notes.
        raise VerificationError(f"{Path(command[0]).name} failed; APK verification did not complete.")
    return result.stdout


def sdk_tools(root, requested):
    sdk = requested or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        properties = root / "local.properties"
        if properties.exists():
            match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(), re.M)
            if match:
                sdk = match[1].replace(r"\:", ":").replace(r"\ ", " ").replace("\\\\", "\\")
    if not sdk:
        raise VerificationError("Configure ANDROID_HOME or pass --sdk with the Android SDK directory.")
    directory = Path(sdk) / "build-tools" / "36.1.0"
    signer = directory / ("apksigner.bat" if os.name == "nt" else "apksigner")
    aapt = directory / ("aapt2.exe" if os.name == "nt" else "aapt2")
    if not signer.is_file() or not aapt.is_file():
        raise VerificationError("Android SDK Build Tools 36.1.0 (apksigner/aapt2) are required.")
    return signer, aapt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--certificate-sha256", required=True, help="Permanent certificate fingerprint recorded independently of this APK")
    parser.add_argument("--sdk", help="Android SDK directory, if not configured locally")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        expected = fingerprint(args.certificate_sha256)
        signer, aapt = sdk_tools(root, args.sdk)
        if not args.apk.is_file():
            raise VerificationError("The requested signed release APK does not exist.")
        certificate = verify_certificate(run([str(signer), "verify", "--verbose", "--print-certs", str(args.apk)]), expected)
        identity = verify_identity(run([str(aapt), "dump", "badging", str(args.apk)]),
                                   run([str(aapt), "dump", "xmltree", str(args.apk), "--file", "AndroidManifest.xml"]))
        verify_contents(args.apk)
        sha = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        commit = run(["git", "-C", str(root), "rev-parse", "HEAD"]).strip()
        dirty = bool(run(["git", "-C", str(root), "status", "--porcelain"]).strip())
        output = root / "test-builds"
        target = output / f"Pumping-{VERSION_NAME}.apk"
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != sha:
            raise VerificationError("A different beta asset already exists. Archive it before staging a replacement.")
        output.mkdir(exist_ok=True)
        # Do not create/replace the named release asset until all verification passes.
        if args.apk.resolve() != target.resolve():
            shutil.copyfile(args.apk, target)
        metadata = dict(identity, apk_sha256=sha, signing_certificate_sha256=certificate,
                        build_commit=commit, working_tree_dirty=dirty,
                        verified_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        release_command="./gradlew --no-daemon --no-configuration-cache :app:assembleRelease",
                        publication_performed=False)
        (output / f"Pumping-{VERSION_NAME}.apk.sha256").write_text(f"{sha}  {target.name}\n")
        (output / f"Pumping-{VERSION_NAME}-verification.json").write_text(json.dumps(metadata, indent=2) + "\n")
        (output / f"Pumping-{VERSION_NAME}-verification.md").write_text(
            f"# Verified beta artifact\n\n- APK: `{target.name}`\n- Application ID: `{APP_ID}`\n"
            f"- Version: {VERSION_NAME} ({VERSION_CODE}); min/target/compile SDK: 26/36/37\n"
            f"- APK SHA-256: `{sha}`\n- Certificate SHA-256: `{certificate}`\n"
            f"- Source HEAD: `{commit}`; working tree dirty: **{dirty}**\n"
            "- APK integrity/signature, non-debuggable metadata, components and fixture exclusion: PASS\n"
            "- Test/device results: record separately in BUILD_INFO.md after running the release checklist.\n"
            "- Publication: not performed. Commit reviewed changes and rebuild before tagging if dirty.\n")
        print(f"Verified release asset staged locally: {target}")
        print(f"APK SHA-256: {sha}\nSigning certificate SHA-256: {certificate}")
        if dirty:
            print("Source contains uncommitted changes. Commit/rebuild and record the final commit before tagging/publishing.")
    except (VerificationError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")


if __name__ == "__main__":
    main()
