# iPhone `.pumping` compatibility

Pumping implements the supported version-1 legacy backup format independently. This document describes the behavior and limits of the Android implementation; private development references are not required to build or use it.

## Import on Android

In **Profile → Import backup (.pumping or JSON)**, select the file with Android's system file picker. File extension and MIME type do not establish validity; content is decoded and validated locally. The original format is standard Base64 wrapping repeating XOR with the documented `breastmilk2024` UTF-8 key. It is obfuscation, not encryption.

The preview shows session/event counts, already imported IDs, conflicts, understood inventory bags, warnings, and Merge/Replace. **Merge is the default.** Choose the original source time zone if it differs from this device, and apply that zone before confirming. Naive source times are interpreted as wall clocks in that zone, never silently as UTC. An ambiguous fall-back time uses its earlier offset, with a warning. Nonexistent spring-forward times reject the import so the user can choose the correct original zone. If a source duration spans a daylight-saving transition and exceeds the real timestamp span, the active duration is capped to that span and a warning is shown; the exact original duration and strings remain available for legacy re-export.

Confirmation is required before any record changes. Reimporting identical source IDs preserves current Android records, local edits, intentional deletions and the original import zone. ID-only deletion markers suppress previously deleted sessions, bags and transactions; confirmed full Replace clears those markers and can restore the file’s records. Changed source records and conflicting IDs or visible bag numbers reject the entire merge. Bag numbers are never silently changed. Numbers from retained, uninterpreted stash events remain reserved so new Android bags cannot reuse them. A newly encountered unverified usage/location event affecting a previously imported bag also blocks Merge; use a safety-backed confirmed Replace if that file should become authoritative, so the old available balance is never retained as if the usage were understood. If a conflicting source file should replace the current data, choose **Replace**, save the required native JSON safety backup through the system picker, then explicitly confirm **Replace all records**. Cancelling any picker/confirmation changes no records. Replacement affects records; preferences change only when explicitly selected.

File reads are bounded to 20 MB, each legacy collection to 100,000 records, and JSON nesting to 64 levels. Parsing runs off the UI thread. Errors display actionable messages without logging record contents. No files are uploaded and no broad storage or network permission is needed.

## Preserved data and integrity

- Pump IDs stay strings and become native session IDs. `time` is the end; `duration` is minutes. Missing/null `startTime` is derived for display and remains absent/null in retained source data.
- All source quantities are mL, regardless of `volume_unit`. Native quantities use the existing integer microliter model, rounded only to its precision. Original numeric values (including four-decimal mL) and microsecond timestamp strings remain in local compatibility records and native backups. Legacy export retains them for unchanged fields; changing notes does not round quantities or timestamps. This preserves the current Android database's units and existing totals.
- `left == 0` is treated conservatively as unclassified total entry. Positive left output retains both sides. Raw sides and `isMarked` are retained in all cases.
- Each confirmed `Added` event produces exactly one bag and one ADD movement. Movement ID is the original stash record ID; bag ID is stable `legacy:bag:<stash-record-id>`, and its visible number stays `bagId`. A confirmed single matching `stashBagId`/`stashAmount` links the bag to the pump without adding a second session or inventory event. Inconsistent or ambiguous links are not invented. Known scalar links that have no retained target, and links revoked by deletion, are cleared for export without changing measured pump output. Source allocation changes are remembered so repeating the original Merge remains idempotent.
- Imported bag dates and expiry estimates are retained, not recalculated from Android defaults. Identical imports never create another addition.
- Room schema **1 → 2** adds only compatibility-record, metadata and preference-overlay tables. Existing sessions, bags and ledger are unchanged. Schema **2 → 3** adds ID-only deletion markers, preserving existing native/imported records and converting old implicit legacy pump deletions into explicit markers. Both migrations are registered; destructive fallback is not used.
- Every record write, replacement delete, compatibility row and opted-in preference change commits in one Room transaction. A database failure rolls everything back. Preferences imported from a legacy file are a transactional Room overlay on the existing DataStore settings; missing/null source settings keep their current effective values, including preferences from earlier imports; later user edits are recorded with per-key acknowledgement tokens in DataStore. There is no half-committed Room/DataStore dual-write import.
- Native JSON v1 still imports. New native backups contain compatibility rows and a snapshot of supported preferences. Native restore keeps current preferences by default; opt into **Restore supported preferences** to restore the snapshot (including from a replacement safety backup). Active timers, pending alarms and Android-only preferences remain device-local.

## Settings and unverified fields

| Field | Android handling |
|---|---|
| `volume_unit`, `dark_mode` | Applied only on explicit settings opt-in |
| `formula_cost_per_100ml` | If non-null, converted to native cost per liter (×10) on opt-in |
| `storage_fridge_days`, `storage_freezer_days`, `storage_deep_freezer_days` | If non-null positive whole days, applied to future bags on opt-in |
| `deep_freezer_enabled` | If non-null boolean, applied on opt-in |
| `version` | Recognized version 1 is validated |
| `install_date` | Retained for export; does not replace the Android installation identity |
| `reminder_type`, `reminder_custom_interval`, `reminder_custom_times` | Retained; schedule semantics/interval units are not sufficiently documented to activate notifications |
| `reminder_sound_enabled`, `reminder_vibration_enabled`, `reminder_night_mode_enabled`, `reminder_night_start`, `reminder_night_end` | Retained; not applied to Android notification channels or night rules |
| `interval_from_start`, `pump_parts_reminders` | Retained, no confirmed mapping |
| `isMarked` | Retained, not exposed as an Android marking feature |
| `stashBagIds`, `brickId`, non-null `purpose` | Retained verbatim; unverified structures are not interpreted |
| Unknown pump fields, settings, and root/data fields | Retained where available; legacy export uses root/settings metadata from the most recent imported document; all imported metadata is retained in native backups |
| Stash `type` other than `Added`, or location other than lowercase `fridge`/`freezer` | Raw event retained, with explicit warnings; every affected bag is excluded from available inventory rather than guessing its remaining balance. This includes unverified `Used`/Donation/Other/Discard serialization. The original event remains exportable. |

## Export and limits

**Export complete records (JSON)** remains the preferred complete records backup. It includes the native inventory ledger, exact retained legacy data and supported preference snapshot. Old native files without these optional fields remain readable.

**Export compatible .pumping (legacy, unencrypted)** uses string version `1.0`, app `breastmilk`, local ISO date-time strings, mL numeric fields, integer minute durations, the documented schema, and Base64/XOR wrapping. New source records use native IDs; imported records retain original IDs, nulls, Unicode/line-break notes, exact original quantities/times for unchanged fields, and unknown fields. No claim is made that an exported file has been accepted by the iPhone application: that requires a private iPhone import test.

The exporter refuses to silently lose Android withdrawals, transfers, deep-freezer bag serialization, multiple new bags per session, or new paused/sub-minute sessions. Their legacy serialization is unverified or not representable in the documented schema. The actionable error directs the user to native JSON. Unknown events that arrived in a legacy file remain exportable verbatim, without inventing new event conventions.

## Testing and validation

Only synthetic fixtures are in test resources and the instrumentation test APK. They are not included in the installable application APK. Tests cover the reference codec, precision/null/Unicode round-trips, time zones, classification, associations, no double counting, merges/reimports/local edits, conflicts, confirmed replacement, database-failure rollback, unsupported events, invalid/oversized input, JSON limits, native compatibility, migration and SAF callbacks/cancellation.

Connected tests must run on a dedicated synthetic-data emulator because Android test tooling may install/uninstall packages. A test-only application supplies an isolated in-memory Room database and separate temporary preferences; UI tests cannot read or replace the production database/settings. The connected test uses a test-only content provider to exercise actual `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` contracts and URI streams; it intercepts the picker result instead of depending on a particular device's Documents UI layout. A physical-device system-picker check remains necessary. It verifies duplicate import, safety-backup cancellation, safety write before replacement confirmation, and `.pumping` export.

For a backup containing real records, compare the preview and representative imported records against your source history, then repeat Merge to check idempotence. Keep original backups and decoded records out of source control, diagnostics, screenshots and build artifacts.

## Permanent deletion and reusable numbers (1.2.0)

`Bag.sourceSession` is the authoritative stable-ID link. Deleting a bag removes its ledger rows and retained source stash rows, not a fabricated usage/discard. Keeping the pump clears its revoked stash allocation. Deleting the pump while keeping bags detaches their source link and preserves their ledger. Deleting one bag plus its pump never silently deletes sibling bags.

Native JSON backups include deletion marker IDs (no deleted notes, volumes or timestamps) and omit removed records and orphaned links. Restoring that backup preserves suppression during subsequent original-file merges. An older native backup also cannot resurrect locally deleted rows during Merge; a deletion marker arriving from another installation cannot silently delete conflicting retained records. Full confirmed legacy Replace intentionally resets deletion suppression.

Legacy bag-number reservations are rebuilt from retained source records, so deleted imported bags no longer inflate the next number. Their source IDs remain suppressed even if a new native bag reuses the number. Exhausted retained bags and uninterpreted retained source bags still reserve their numbers. Existing bag numbers never change automatically.

As of Android 1.3.0, input and display units are independent. Original iOS `volume_unit` remains the only unit field in `.pumping` exports and reflects Android Display Units. Opting into iOS settings import initializes both Android preferences from that single field. Native Android backups preserve both preferences. Unit settings never change source quantities, timestamps, IDs, or stash relationships.
