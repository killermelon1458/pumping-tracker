# Development builds and revision counters

The authoritative policy is [docs/VERSIONING.md](docs/VERSIONING.md). Pumping uses **MAJOR.MINOR.REVISION** with a build-channel suffix. The third number is an intentional revision counter, not strict Semantic Versioning.

The published `1.4.0-beta.1` (code 7) is historical and unchanged. Quick Log timing shipped in `1.4.1-dev` (code 8). Stage 1 `1.4.2-dev` (code 9) was accepted on device. Current Stage 2 is `1.4.3-dev` (code 10), adding pump profiles and native backup v3; see [the implementation/acceptance plan](docs/NEXT_BETA_PLAN.md).

## Advance and promote

`gradle.properties` holds the single shared `pumpingVersion` and `pumpingVersionCode`.

- Advance REVISION and Android versionCode together when completing a feature or subsequent bug-fix revision.
- Do not advance either merely to promote the validated development revision to beta: build type changes `-dev` to `-beta`.
- Keep public production version codes increasing relative to previously distributed production builds. Do not publish changed production content under an already distributed version code.
- Do not pre-create future revisions. Keep existing release tags and assets immutable.

## Build identities

| Property | Debug | Release |
| --- | --- | --- |
| App ID | `dev.killermelon1458.pumping.debug` | `dev.killermelon1458.pumping` |
| Name | Pumping Dev | Pumping |
| Version suffix | `-dev` | `-beta` |
| Signing | Standard Android debug key | Existing permanent release key |
| Debuggable | Yes | No |
| Launcher icon | Bottle with blue DEV badge | Original bottle |

Both use namespace `app.pumping` and the same data/backup schemas. Android isolates their databases, settings, persisted alarms, permissions and notification channels by package/UID. Explicit PendingIntents target the owning package. SAF backup import/export accesses only the running variant's records; a deliberately selected external backup can be exchanged between variants. Export filenames/formats remain unchanged, so choose separate external destinations if retaining both variants' exports.

No automatic copying/migration between the two apps is performed. An old debug build using the production ID is not upgraded by the new Dev app. Never uninstall or clear a real-data production installation to run development tests.

## Build and test

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools/tests -v
```

Use a disposable emulator for `./gradlew :app:connectedDebugAndroidTest`; instrumentation and its synthetic provider derive their IDs from the Dev package. `assembleDebug` automatically runs `stageDebugApk`; `assembleRelease` runs `stageReleaseApk` after the existing release-signing guards. Each staging task consumes AGP's completed APK artifact and the configured variant output's `versionName`, `versionCode` and application ID. It verifies the actual APK with SDK tools and stages debug artifacts in `dev-builds/<versionName>/Pumping-<versionName>.apk`, with `.apk.sha256` and `.build.json` verification reports beside them. Release artifacts remain in `test-builds/`; that workflow is unchanged. Python 3 (`python3` on PATH) is required. No version is hardcoded into the staging task.

Identical builds may be restaged; a different APK under an existing versioned filename fails without replacing it. Explicitly resolve/archive a candidate collision or advance the revision for changed distributable content. Keep previous versioned APKs. Tests are not invented by staging: retain commands/results in each development version directory's `BUILD_INFO.md` (release verification reports remain under `test-builds/`).

```bash
adb install -r dev-builds/1.4.3-dev/Pumping-1.4.3-dev.apk
```

Profile displays the variant's generated `BuildConfig.VERSION_NAME`, e.g. `1.4.3-dev`. Production updates retain their permanent ID/certificate and preserve all user data through tested migrations. See [RELEASE_SIGNING.md](RELEASE_SIGNING.md) for owner-configured external credentials, signing guards, verification and manual publication. The verifier reads the shared revision and stages `Pumping-<revision>-beta.apk` only after verifying a genuine release. Preparing this convention does not authorize any release, tag, upload or publication.


## Commit and tag checkpoints

Each completed tested development milestone gets a version-identifying Git commit; each accepted milestone gets an annotated tag (for example `v1.4.2-dev`). Follow the authoritative policy for authorization: preparation/staging does not authorize committing, tagging, pushing or publishing. The first checkpoint includes the complete accumulated 1.4.2-dev state since the published baseline, not separate reconstructed 1.4.1/1.4.2 commits.

Keep generated APKs, verification reports, private records and signing material out of Git. `dev-builds/` is locally retained and ignored, and remains available to Syncthing. Keep previous version directories and never overwrite an existing APK with different bytes. After owner review and authorized commit, record its exact commit ID in the local verification report before an accepted milestone is tagged. Stage 2 requires separate owner approval.
