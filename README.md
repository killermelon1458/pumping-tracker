# Pumping

Pumping is an independently developed, native Android application for logging breast-milk pumping sessions and tracking stored milk. It uses Kotlin, Jetpack Compose, Room and DataStore.

**Status: beta / pre-release.** This is a source project under active development, not a published production release. Physical-device validation, particularly Samsung One UI notifications and background battery restrictions, remains part of beta preparation.

The current public beta is **1.4.0-beta.1** (code **7**). The current development revision is **1.4.2-dev** (code **9**), adding breastfeeding context and native backup v2 while preserving the Quick Log time-editing improvement. Private builds use `dev.killermelon1458.pumping.debug` (**Pumping Dev**); public builds retain `dev.killermelon1458.pumping` (**Pumping**). Both install side by side with independent app storage/settings/notifications. Kotlin namespace remains `app.pumping`. See [development versioning](DEVELOPMENT.md).

## Features

- Timed and manually logged pumping sessions, editable history, notes, optional left/right volumes, and explicit Yes/No/Unknown breastfeeding context.
- Milk stash bags with storage locations, expiry estimates, partial withdrawals, transaction history and confirmed deletion of linked records.
- Statistics calculated from saved sessions and inventory transactions.
- Reminders with Snooze and Dismiss, reboot recovery, optional precise alarms and an inexact fallback.
- A saved Start/End reference for elapsed time, history intervals and automatic reminders; Start is the default.
- Independent oz/mL input and display units, with quantity-preserving editor toggles.
- Local JSON backups, compatible iOS `.pumping` import/export, and session CSV export.

## Android support and privacy

- Android **8.0 (API 26)** and newer.
- Currently targets **API 36** and compiles against **API 37**.
- No account, subscription, Google services or internet connection is required for app use. The application does not request internet permission and includes no advertising or analytics.
- Records are stored locally in Room; preferences and reminder state use DataStore. Android automatic cloud/device-transfer backups are disabled.

Exported backup files contain sensitive records and are not encrypted. Keep backups private. Storage-expiry dates are estimates, not medical or milk-safety advice.

## iOS backup compatibility

In **Profile → Import backup (.pumping or JSON)**, select a backup using Android's system file picker. Import previews records and warnings before confirmation. Merge handles repeated source IDs without duplication; a confirmed replacement requires a safety backup. Select the source time zone for iOS files containing local wall-clock timestamps.

The supported legacy format uses Base64/XOR obfuscation, not encryption. Pumping preserves original IDs, quantities and timestamps within the documented compatibility rules, including retained source precision. Some original fields and event types are retained without being interpreted. Compatible export refuses Android data it cannot safely represent, such as unsupported withdrawals/transfers or multiple newly allocated bags. Native JSON is the preferred complete-record backup.

Acceptance of exported `.pumping` files by the original iOS application remains unverified. See [compatibility details and limitations](LEGACY_COMPATIBILITY.md). Only clearly synthetic records are included in tests; no original personal backups or reference screenshots are needed to build the app.

## Build

Install Android SDK Platform **37.0** and Build Tools **36.1.0**, and use **JDK 21**, the verified build runtime (app source/bytecode compatibility is Java 17). The Gradle wrapper is included; do not install a separate Gradle version. An initial build needs access to the configured dependency repositories unless dependencies are already cached.

Configure the SDK through `ANDROID_HOME` or an untracked `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

From the repository root:

```bash
./gradlew :app:assembleDebug
```

Gradle automatically verifies and stages `dev-builds/<variant-versionName>/Pumping-<variant-versionName>.apk` (currently `dev-builds/1.4.2-dev/Pumping-1.4.2-dev.apk`), plus its checksum and build metadata. This requires Python 3 (`python3` on PATH) and the configured Android SDK tools. Internal Gradle output names remain unchanged. On Windows, use `gradlew.bat`. See the authoritative [versioning/artifact policy](docs/VERSIONING.md).

## Install for development

Use a disposable test device or emulator with USB debugging enabled:

```bash
adb install -r dev-builds/1.4.2-dev/Pumping-1.4.2-dev.apk
```

An update must use the same application ID and signing certificate to preserve the installation. Do not uninstall an installation containing real records without first exporting a backup. Debug APKs are for development; they are not signed beta/production releases. Generated APKs and local `dev-builds/` / `test-builds/` deliveries are intentionally not part of the source repository.

Notifications require permission on Android 13+. Precise reminders need optional special access on Android 12+; otherwise delivery may be delayed. Channel settings, Do Not Disturb and manufacturer battery restrictions apply. See [reminder behavior and device checks](REMINDERS.md).

## Signed beta preparation

Release signing is separate from debug builds. Follow [RELEASE_SIGNING.md](RELEASE_SIGNING.md) to configure the permanent external keystore, build the non-debuggable release APK and verify its identity, signature and checksum. Missing credentials intentionally block release packaging while debug builds and unit tests remain available. No signing key is generated by the project.

Future beta assets use `Pumping-MAJOR.MINOR.REVISION-beta.apk`, built from the validated revision without increasing its revision or code solely for promotion. They must be verified signed **release** builds; a debug APK is never a substitute. Existing `v1.4.0-beta.1` assets remain unchanged. Generated artifacts and release notes stay local under ignored `test-builds/` until explicitly published.

## Tests and project layout

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Run connected tests **only on a dedicated emulator or disposable test device**; Android test tooling may install or uninstall the target application:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The test runner uses synthetic fixtures and isolated storage. Tests cover calculations, persistence, database migrations, inventory transactions, backup compatibility, reminder permissions and actions, and Compose workflows.

- `app/src/main/`: application code, manifest and original Android resources.
- `app/src/test/`: JVM/Robolectric tests and synthetic fixtures.
- `app/src/androidTest/`: device/Compose tests and synthetic fixtures.
- `app/schemas/`: exported Room schemas needed for migration review and testing.
- `gradle/wrapper/`: required Gradle wrapper files.

[Deletion and bag numbering](DELETION_BEHAVIOR.md), [backup compatibility](LEGACY_COMPATIBILITY.md), and [reminders](REMINDERS.md) describe the implemented behavior. The iOS format identifiers retained in the compatibility code are wire-format requirements, not the Android application's branding. Code and Android artwork are independently implemented.

## License

Pumping is licensed under the Apache License, Version 2.0.

See [LICENSE](LICENSE) for details.
