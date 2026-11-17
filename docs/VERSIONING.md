Pumping — Versioning and Build Artifact Policy

This document defines the authoritative versioning, build identity, APK naming, and release progression conventions for Pumping.

All development agents and build workflows must follow these rules.

Version naming

Use the format:

"MAJOR.MINOR.REVISION-CHANNEL"

Supported channels:

- "dev" — private development and testing builds.
- "beta" — validated, release-signed public beta builds.

The revision number increments for each new feature, functional change, or bug-fix revision.

Do not maintain separate "dev.1", "dev.2", "beta.1", or similar counters for new releases under this convention.

Do not increment REVISION merely because an already-validated development revision is being promoted to beta.

Examples:

Version| Purpose
1.4.0-beta.1| Existing historical public release; do not rename
1.4.1-dev| Quick Log timing UX update
1.4.2-dev| Breastfeeding context and persistence foundation
1.4.3-dev| Pump profiles and breastfeeding label correction
1.4.3-beta| Public beta containing the validated 1.4.3 changes
1.4.4-dev| Power-pump tracking with optional intervals (UX rejected)
1.4.5-dev| Stage 3 timing UX correction and history indicators (Duration regression found)
1.4.6-dev| Accepted Stage 3 checkpoint, including immediate Duration synchronization
1.4.7-dev| Independent date/time controls, native time wheels and semantic discard detection
1.4.8-dev| Stage 4A filterable statistics and pump comparisons
1.4.8-beta| Promotion of the accepted 1.4.8 development milestone
1.4.9-dev| History cleanup, stash timestamp/discard parity, independent editor layouts and global picker style
1.4.10-dev| Scrollable customization footer and compact section layout rows

The historical "1.4.0-beta.1" release is grandfathered into the convention.

The revision component is a project development counter, not strict Semantic Versioning.

Android version codes

Android "versionCode" is an integer and must increase as development revisions advance.

The current public baseline is "1.4.8-beta", version code 15. Historical "1.4.0-beta.1" uses code 7.

Examples:

Version| Version code
1.4.0-beta.1| 7
1.4.1-dev| 8
1.4.2-dev| 9
1.4.3-dev| 10
1.4.3-beta| 10
1.4.4-dev| 11
1.4.5-dev| 12
1.4.6-dev| 13
1.4.7-dev| 14
1.4.8-dev| 15
1.4.8-beta| 15
1.4.9-dev| 16
1.4.10-dev| 17

Development and beta builds of the same revision may share a version code because their Android application IDs differ.

A public beta must always have a greater version code than the previously published production version.

Never decrease the version code within an existing installation channel.

Application identities

Development builds:

- Application ID: "dev.killermelon1458.pumping.debug"
- App label: "Pumping Dev"
- Version suffix: "-dev"
- Standard Android debug signing
- Debuggable
- Independent application data

Production beta builds:

- Application ID: "dev.killermelon1458.pumping"
- App label: "Pumping"
- Version suffix: "-beta"
- Permanent production release signing certificate
- Non-debuggable
- Existing user data preserved through upgrades

Keep the Kotlin namespace independent of the application ID.

Development and production installations must coexist on one device without sharing application storage.

APK filename convention

All distributable APKs must use:

"Pumping-MAJOR.MINOR.REVISION-CHANNEL.apk"

Examples:

- "Pumping-1.4.1-dev.apk"
- "Pumping-1.4.2-dev.apk"
- "Pumping-1.4.3-dev.apk"
- "Pumping-1.4.3-beta.apk"

The APK filename must match the actual packaged Android "versionName".

Use exactly the app name "Pumping", followed by the version name, followed by ".apk".

Do not distribute APKs using generic filenames such as:

- "pumping-debug.apk"
- "app-debug.apk"
- "app-release.apk"
- "pumping-latest.apk"

Gradle's internal output filenames may remain unchanged. The build/staging workflow must copy the completed APK into the correct versioned filename.

Artifact location

Stage development builds under:

`dev-builds/VERSION/Pumping-VERSION.apk`

For the current checkpoint: `dev-builds/1.4.10-dev/Pumping-1.4.10-dev.apk`.

Each development version directory retains its APK, checksum, automatic `.build.json` verification report, and `BUILD_INFO.md` with actual test results and limitations. `dev-builds/` is ignored by Git but remains available locally for Syncthing; ignoring a directory does not remove it or disable synchronization.

Release APKs and independent release-verification metadata continue using `test-builds/`. Do not change that workflow merely to move development artifacts. Existing historical files in `test-builds/` may remain available locally.

Development APKs, reports and all generated build artifacts are not tracked by Git. Both artifact directories must be ignored.

Keep prior versioned APKs when practical. Never overwrite an existing, different build under the same versioned filename without explicitly identifying and resolving the version collision.

A new code change intended for distribution or installation testing should ordinarily receive a new revision and version code.

Test reports, checksums, and build metadata should identify the exact APK filename and version.

Build workflow requirements

Whenever Codex creates an APK:

1. Determine the intended version and channel.
2. Verify that the Gradle configuration produces the correct application ID, version name, and version code.
3. Build the corresponding Android variant.
4. Stage development APKs at `dev-builds/VERSION/Pumping-VERSION.apk`; retain release staging under `test-builds/`. Derive VERSION from the configured Gradle variant output, then verify it matches the packaged versionName.
5. Verify the staged APK's package identity, version name, and version code using Android build tools.
6. Calculate the staged APK's SHA-256 checksum and record it in the build report.
7. Report the exact APK path, version, application ID, and test results.

Do not report a build as complete if the expected versioned APK was not created and verified.

Update any existing scripts, tests, documentation, and artifact references that assume a fixed "pumping-debug.apk" filename.

Maintain one authoritative source for the base version where practical. Avoid manually duplicating version strings throughout build scripts.

Promoting development builds to beta

A validated development revision may be promoted to beta without incrementing its revision number.

Example:

"1.4.3-dev" → "1.4.3-beta"

Promotion must:

- Build from the validated source state.
- Switch to the permanent production application ID.
- Use the permanent release signing certificate.
- Produce a non-debuggable release APK.
- Preserve all existing database and backup compatibility guarantees.
- Verify installation over a previous signed production beta.
- Produce a versioned release APK and checksum.
- Pass release verification before publication.

The development and production APKs are separate binaries. Matching revision numbers do not imply byte-identical APKs.

GitHub publication, commits, tags, and pushes must occur only when explicitly authorized.

Never modify previously published release tags or replace published APKs silently.

Data safety

Do not uninstall the production app, clear production storage, or replace the production signing certificate as part of normal development.

Production upgrades must preserve pumping sessions, stash inventory, transaction history, settings, reminders, and associated records.

Development builds may contain disposable test data but must never affect production installation data.

Development Git milestones

1. Each completed, tested development milestone should have a Git commit identifying its exact version. A new feature milestone advances REVISION and versionCode according to this document.
2. Each **accepted** development milestone should receive an **annotated** Git tag identifying the same version, e.g. `v1.4.2-dev`. Acceptance and authorization are separate from successful automated checks.
3. The first checkpoint is the complete tested **1.4.2-dev / code 9** state since the published 1.4.0-beta.1 baseline: Quick Log authority/future-time UX, breastfeeding context, schema 4/native backup v2, Dev identity, versioned staging and related tests/docs/tooling. Do not artificially split that checkpoint into earlier feature commits.
4. Commit reproducible source, resources, schemas/migrations, tests/fixtures that are synthetic, Gradle wrapper/configuration, build tooling and public documentation. Never commit signing credentials/keys, personal records, real backups, private notes, APKs or other generated build artifacts. Do not assume previously tracked files are safe without review.
5. Codex must not commit, tag, push or publish without **explicit authorization for that action**. Authorization to prepare or stage a checkpoint is not authorization to create its commit or tag. Stop for owner review when requested.
6. Record the source commit or pre-commit working-tree state and exact APK SHA-256 in the local version directory's verification report. A pre-commit build must not falsely claim its changes belong to the current HEAD. After authorized commit, record the new commit/rebuild verification before tagging an accepted milestone.
7. Preserve each versioned artifact: identical bytes may be restaged; do not overwrite an existing artifact with different bytes. Resolve a collision explicitly or advance the revision for changed distributable content. Keep historical APKs/reports where practical.

The accepted Stage 4A checkpoint is committed/tagged as `v1.4.8-dev` (`70e373f`). The owner authorized promotion to `1.4.8-beta` / code 15 with the permanent production identity/certificate, local annotated beta tag, push and prerelease publication only after verification. Stage 4B remains deferred. Release-only documentation may be committed separately without rebuilding an unchanged application, provided provenance records the actual APK build commit and verifies every distributable/build input is identical; never relabel an old binary as built at a newer documentation commit.

The accepted preceding private milestone is **1.4.9-dev / code 16**, above the immutable published 1.4.8-beta baseline. It changes editor presentation and device-local DataStore layout/picker preferences only; Room 6/native JSON v4 and permanent production identity/signing remain unchanged. Stage source for review only; this task does not authorize a commit, tag, release or Stage 4B. See [editor UX notes](EDITOR_LAYOUTS.md).

The owner’s picker-style amendment remains part of 1.4.9-dev/code 16 because no 1.4.9 APK was finalized or distributed beforehand. Repeat verification for the complete amended source before staging the single deliverable; earlier internal candidate checks are not the final amended-build results.

The owner physically accepted 1.4.9-dev/code 16. The cumulative uncommitted checkpoint now advances to **1.4.10-dev/code 17** for focused editor organization corrections: the unchanged customization buttons scroll at the bottom; section editing uses one row with the drag handle on the right. Retain the accepted 1.4.9 APK and reports unchanged. No persistence/backup/signing changes and no Stage 4B work are authorized. Stage source for review; do not commit/tag/push/publish.

## 1.4.10 beta promotion

The owner physically accepted 1.4.10-dev/code 17 and explicitly authorized promotion to **1.4.10-beta/code 17** without a revision/code increment. Phase A audits and commits the cumulative accepted 33-file source checkpoint plus release documentation, verifies a clean tree, and stops for owner-local signing. No keystore/password access or production build is performed by the agent in Phase A. Phase B reuses the owner's signed artifact and requires independent permanent-certificate verification, an in-place signed upgrade from published 1.4.8-beta on a disposable emulator, and focused fresh-install smoke checks before annotated tag `v1.4.10-beta`, push and GitHub prerelease publication. Development results are reused; full-suite repetition requires a specific reason. Preserve previous releases. Stage 4B and 1.5.0 features remain deferred.
