# Building and verifying signed beta releases

The permanent application ID is `dev.killermelon1458.pumping`. The first planned prerelease is **1.4.0-beta.1**, version code **7**. The namespace/Kotlin packages remain `app.pumping`. This is a new installation relative to the old `app.pumping` application ID; no cross-ID data migration is attempted.

From this beta onward, distributed updates must use this permanent ID, the same permanent signing key/certificate and an increased version code. Preserve Room migrations, DataStore state and compatibility with previous native/iOS backups. Never clear or uninstall a real-data installation to make an update work.

## 1. Create and retain the permanent key yourself

The project never generates a release key. Keep the keystore and credentials **outside this repository**, including outside ignored `test-builds/` or other project directories. Securely retain recovery copies of the keystore, alias and both passwords. Do not upload them to GitHub, issue attachments, source archives or build logs.

If you choose to create a new key using the JDK's interactive `keytool`, an example owner-run command is:

```bash
keytool -genkeypair -keystore /absolute/external/path/pumping-release.jks \
  -storetype JKS -alias pumping-release -keyalg RSA -keysize 3072 -validity 10000
```

This command is an instruction only; it has not been executed by the project. Select your own certificate identity and passwords when prompted. Do not use Android's `androiddebugkey` or the development/debug certificate. Do not create a temporary key for a public beta: later updates need the same permanent key.

Record the certificate SHA-256 independently:

```bash
keytool -list -v -keystore /absolute/external/path/pumping-release.jks -alias pumping-release
```

Supply the password interactively, not as a command-line argument. The certificate fingerprint is public metadata; the keystore and passwords are private.

## 2. Configure the build environment

Set these four environment variables in a private shell/session:

| Variable | Value |
| --- | --- |
| `PUMPING_KEYSTORE_FILE` | Absolute path to the readable external keystore |
| `PUMPING_KEY_ALIAS` | Alias of its permanent private-key entry |
| `PUMPING_KEYSTORE_PASSWORD` | Keystore password |
| `PUMPING_KEY_PASSWORD` | Private-key password |

No signing properties file is read from the project. Environment-only configuration avoids adding a secret-bearing file to source control. For Bash, the passwords can be read without shell-history arguments or terminal echo:

```bash
export PUMPING_KEYSTORE_FILE=/absolute/external/path/pumping-release.jks
export PUMPING_KEY_ALIAS=pumping-release
read -rsp 'Keystore password: ' PUMPING_KEYSTORE_PASSWORD
printf '\n'
export PUMPING_KEYSTORE_PASSWORD
read -rsp 'Key password: ' PUMPING_KEY_PASSWORD
printf '\n'
export PUMPING_KEY_PASSWORD
```

Do not use shell tracing, Gradle debug logging or shared configuration caches while signing. The validation task refuses missing credentials, unreadable/in-repository keystores, unavailable private-key entries, expired certificates and known Android debug keys. It never falls back to debug signing. Ordinary debug compilation, lint and unit tests work without these variables.

## 3. Review source and run checks

Commit the reviewed application changes yourself before producing the final publication artifact, so the recorded build commit is the code actually being tagged. Do not commit signing credentials, private references or generated artifacts.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
python3 -m unittest discover -s tools/tests -v
```

On a dedicated, disposable emulator only:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The connected test tooling may install/uninstall the permanent app ID. Never run it against an installation containing real records. Debug and release use the same application ID but different signing certificates; use a disposable emulator or a separate device for debug tests, not your installed signed beta.

## 4. Build the real release APK

```bash
./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
```

The signed APK is expected at `app/build/outputs/apk/release/app-release.apk`. Release mode is explicitly non-debuggable; R8/code shrinking and resource shrinking are disabled for this beta. Test fixtures, instrumentation providers/runners and the debug Compose host activity are not production dependencies.

If signing credentials are unavailable, this command must fail with the missing variable names. This is expected. Do not rename a debug or unsigned APK to the release asset name.

## 5. Verify, then stage the local asset

Use the independently recorded permanent certificate fingerprint, not a fingerprint copied from an untrusted APK:

```bash
python3 tools/prepare_beta_release.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --certificate-sha256 YOUR_PERMANENT_CERTIFICATE_SHA256
```

The script runs SDK Build Tools 36.1.0 `apksigner verify --verbose --print-certs` and `aapt2`, checks the ID/version/SDK levels, verifies non-debuggable/non-test-only status and manifest components, and rejects packaged fixtures/test code and known debug certificates. It never signs or publishes. The script requires Python 3 and SDK tools via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties`, or `--sdk`.

Only after verification succeeds does it stage:

- `test-builds/Pumping-1.4.0-beta.1.apk`
- `test-builds/Pumping-1.4.0-beta.1.apk.sha256`
- `test-builds/Pumping-1.4.0-beta.1-verification.json`
- `test-builds/Pumping-1.4.0-beta.1-verification.md`

The metadata records the actual signing fingerprint, checksum, current source HEAD and dirty-worktree state. A dirty build is a local candidate, not proof that HEAD contains those changes; commit/rebuild before tagging. The script refuses to overwrite a different existing beta asset: archive that local candidate first if replacing it intentionally. Record automated and device test results separately in `test-builds/BUILD_INFO.md`; the verifier does not invent test results or certify the freshness of old reports.

Unset passwords after the build:

```bash
unset PUMPING_KEYSTORE_PASSWORD PUMPING_KEY_PASSWORD
```

## 6. Install-test the signed release

On an empty disposable emulator first, verify clean installation, session editing/deletion, linked stash creation/usage/deletion, bag-number reuse, both unit settings, Start/End reference, reminders/actions, native JSON export/reimport, and synthetic iOS import/reimport. Restart the app and verify persisted records/settings. Check actual release file-picker and alarm behavior. Do not include private backups in any test artifact.

```bash
adb install test-builds/Pumping-1.4.0-beta.1.apk
```

For subsequent public releases, test `adb install -r` over the previously distributed release using the same permanent certificate and a higher version code. Verify quantities, notes, relationships, reminder state and backups remain intact. Keep the first beta APK/certificate metadata for future upgrade tests. No migration from the old Android development application ID is required or attempted.

## 7. Publish manually, after review

1. Complete the release installation/physical-device checks and update the local build information and release notes with actual results, final commit and signing fingerprint.
2. Verify the final reviewed changes are committed and pushed by you. Rebuild/verify if the source commit changes.
3. Create tag **`v1.4.0-beta.1`** at the exact recorded build commit and push it yourself.
4. In <https://github.com/killermelon1458/pumping-tracker/releases/new>, select that tag, set title **Pumping 1.4.0 Beta 1**, and mark **Prerelease**.
5. Paste the reviewed local `test-builds/RELEASE_NOTES-1.4.0-beta.1.md`. Attach only the verified `Pumping-1.4.0-beta.1.apk` and its checksum (and public verification metadata if desired), then publish yourself.

No task here creates a commit, Git tag, GitHub Release, remote change, upload or deployment workflow. Credentials never belong in release assets.

Android references: [release signing](https://developer.android.com/studio/publish/app-signing), [Gradle signing configuration](https://developer.android.com/build/gradle-tips), [build variants](https://developer.android.com/build/build-variants).
