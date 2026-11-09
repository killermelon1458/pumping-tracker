# Pumping — Next Beta Feature Plan

**Status:** Stage 1 accepted on physical device. Stage 2 implemented and verified, awaiting owner device feedback. Stages 3–4 remain unimplemented.
**Current public baseline:** `1.4.0-beta.1` (`versionCode 7`).
**Development baseline:** Quick Log authority-tracking UX is implemented in `1.4.1-dev` (per local Codex report).
**Location in repository:** `docs/NEXT_BETA_PLAN.md`.
**Related policy:** `docs/VERSIONING.md` and root `AGENTS.md` take precedence for release/version/artifact conventions.

## Product principles

1. **Fast by default.** The ordinary Quick Log path must remain brief. Advanced controls are optional and collapsed unless actively used.
2. **Correct data over inferred data.** Unknown historical values remain `Unknown`/`null`, not `No`. Never infer pumping circumstances from notes, time of day, or pump brand.
3. **One pumping event is one session.** Power-pump phases are children of the session, never separately counted sessions.
4. **Meaningful comparisons.** Users can filter across multiple dimensions simultaneously (pump, preceding breastfeeding, power-pump status, date range, and optionally time-of-day) and compare like with like.
5. **Safe upgrades.** Preserve all real records, deletion markers, stash ledgers and links, preferences, original iOS import compatibility, and future release signing identity.
6. **Losses are explicit.** Native backup must preserve all supported data. Legacy `.pumping` exports may omit app-specific metadata only with an explicit warning and must not misstate session or inventory values.
7. **No medical intake inference.** A difference between pumping averages before/after nursing is **not** an estimate of milk transferred to the baby. Label it as expressed-milk difference only. Do not present infant intake estimates from these values.

## Delivery rules for every stage

- Inspect the current local code, local `AGENTS.md`, and `docs/VERSIONING.md` before editing. Local development may be ahead of the published GitHub commit.
- Work on **only the current stage**. Do not automatically proceed to later stages, commit, push, tag, or publish; wait for owner approval.
- Advance the `MAJOR.MINOR.REVISION-dev` version and `versionCode` for each **new deliverable**, including subsequent bug-fix builds. Do not reuse a versioned APK filename for a different binary. `1.4.2-dev`, `1.4.3-dev`, etc. are examples, not guaranteed stage numbers if intermediate fixes occur.
- The debug app must retain its distinct application ID (`dev.killermelon1458.pumping.debug`), label (`Pumping Dev`), and ordinary debug signing. The signed production app remains `dev.killermelon1458.pumping` and is never replaced by a debug build.
- Stage the installable APK in `dev-builds/X.Y.Z-dev/Pumping-X.Y.Z-dev.apk`, derived from the embedded `versionName`; verify ID/version/code with Android build tools, record SHA-256 and test results in local build metadata.
- Run relevant unit tests, Room migration tests, backup round-trip tests, lint, and available emulator/Compose UI tests. Report actual counts and any limitations. For each stage, test with synthetic data and a non-production upgrade path; never reset real production data.
- Preserve data on every upgrade. Avoid schema-destructive migrations or fallback-to-destructive-migration. Keep original iOS backup files and other personal records outside Git and release artifacts.
- Stop after each stage and request physical-device UX feedback before continuing.

## Shared data model and migration strategy

The existing app has `Session(start, end, activeSeconds, volume, left, right, notes, mode, ...)`, Room database version 3 (in published baseline), and native JSON backup `format: "Pumping", version: 1`. The local tree may be newer: inspect before deciding migration numbers.

Target conceptual entities and fields:

- **Session:** existing fields; `breastfedBefore` as `YES | NO | UNKNOWN`; `isPowerPump` as `YES | NO | UNKNOWN` where appropriate; optional `pumpProfileId` reference. Existing/imported sessions default to UNKNOWN and no assigned pump. New ordinary sessions can default to NO for power pumping, but breastfeeding status must be explicitly recorded or left Unknown (do not silently classify older data).
- **PumpProfile:** immutable ID, category (`STATIONARY`, `MOBILE_OR_WEARABLE`, `MANUAL`, `OTHER`), optional custom category label, nickname, optional brand/model/notes, active/archived status. Allow unique identity without requiring unique brand; preserve archived references in historical sessions.
- **SessionPhase:** session ID, ordered position, `PUMP` or `REST`, duration and/or explicitly timestamped boundaries, with sufficient fidelity to reconstruct the session's timing. Phases must be internally consistent and must not overlap. No per-phase milk volume required.

Choose the simplest defensible Room representation, including nullable foreign keys and cascade/delete behavior for phases (without cascading from pump-profile deletion to sessions). Preserve session/stash relationships and existing foreign keys.

**Backup invariant:** As soon as new data can be saved, native JSON export/import must round-trip it completely. Do not postpone backup support to the final stage.

- Plan a native backup **v2** at the first schema stage. Accept existing v1 imports; export v2 with new metadata and entities. Validate records and references atomically; reject unsupported future versions rather than silently dropping newer fields.
- If later stages add new fields not anticipated in v2, either make the v2 schema explicitly extensible and validate those additions without data loss, or increment the native format version and retain compatibility with earlier versions. Do not silently extend/ignore fields in a way that loses data on round-trip.
- Keep repeated imports idempotent; preserve deletion tombstones and existing merge/conflict semantics. Test both fresh installs and upgrades from production-like synthetic data, including imported original iOS `.pumping` records.

## Stage 1 — Breastfeeding context + persistence foundation

**Primary user goal:** One-tap indication of whether breastfeeding occurred immediately before the pumping session, with no additional mandatory steps.

**UI:** Add a prominent `Breastfed before pumping` toggle in Quick Log. Offer `Unknown` as a truthful state for existing entries and when the user does not know; distinguish unset from explicit No. Optional advanced context (breast side, feeding-end time) is **deferred** unless it is genuinely needed for a later stage; avoid enlarging Quick Log.

**Backend:** Implement versioned Room migration(s) preserving all preexisting data. Agree on schema shape for profiles and power-pump phases before committing the migration; it is acceptable to create supporting nullable fields/tables now with no new UI, provided native backup covers them and tests them. Implement native backup v2 from the outset, supporting v1 imports.

**Acceptance:** First-time and edit-session flow retains chosen status across process restart, app upgrade, export/import, and edit/save. Historical/imported records appear Unknown until explicitly changed. Quick Log timing state-machine behavior from `1.4.1-dev` remains unchanged. Tests establish round-trip and migration preservation with realistic synthetic sessions, bags, ledger movements, legacy metadata and deletion markers.

**Deliverable:** New versioned `-dev` APK; owner manually verifies the one-tap workflow and persistence. STOP.

## Stage 2 — Pump profiles and Quick Log selection

**Primary user goal:** Configure one or more pumps once and choose which was used with minimal taps.

**Profile UI:** Add pump manager with Create/Edit/Archive (or equivalent). Default category choices: `Stationary`, `Mobile/Wearable`, `Manual/Hand`, and `Other/Custom`. For Other allow optional free-text type label. Optional brand, model, notes; editable nickname. Suggest nickname `Category Brand` (or category alone if no brand); never force a name the user has not chosen, and make saved names distinguishable. Support several pumps of one category/brand.

**Quick Log UI:** Dropdown of active profiles plus `Unspecified`, defaulting to last used **only for new sessions** (not existing session edits). Ensure selection remains quick; archived profiles still display correctly on historical sessions but are not offered for new selections unless explicitly reactivated.

**Data integrity:** Stable IDs, nullable reference, safe archive behavior, historical records not destroyed by profile edits/removal. Use a deliberate policy for deleting a profile that is referenced (archive instead or detach with explicit confirmation). Native backup round-trips profiles and references. Old records remain Unspecified.

**Acceptance:** Create two pumps of different categories; select each for sessions; archive one; confirm historical attribution is retained; verify restore and migration. No change to existing output/volume/timing. STOP.

## Stage 3 — Power-pumping sessions with optional intervals

**Primary user goal:** Record a power pump quickly, with precise pumping/rest intervals available for users who want them.

**Quick Log UI:** Add `Power Pump` toggle. Default behavior does **not** require or automatically save detailed intervals. Collapsed optional `Timing details` section. Offer an explicit preset `Pump 20 / Rest 10 / Pump 10 / Rest 10 / Pump 10` and editable/addable/removable phases; advanced mode may allow exact phase start/end times as well as durations. The preset should be a suggestion activated by the user, not silently assumed for every power-pump record.

**Session semantics:** Exactly one parent session for each power-pumping event. `activeSeconds` equals known pumping time; elapsed time equals `end - start` when rests are recorded. With **no detailed phases**, do not invent resting time: store the user-entered known duration under a clearly explained interpretation (e.g., `Active pumping time`, with elapsed unknown/unconfirmed rather than fabricating a 60-minute span). Reconcile this with existing session validation, which requires activeSeconds <= timestamp span; establish an explicit, tested policy before coding.

**Interactions:** The existing `End → Duration` Quick Log edit-authority state machine must remain predictable. Clarify on screen whether a displayed duration is active pumping or total elapsed in detailed mode. Phase edits should update parent timing deterministically, not trigger feedback loops. Save totals and optional exact intervals; do not count phases as independent pumps, do not multiply session volumes, and retain parent-level stash links.

**Acceptance:** Normal Quick Log unchanged with toggle off. Toggle-only records work without filling phases. Preset produces 40 min active / 60 min elapsed. Custom phases, editing, midnight transitions, validation, backup round-trip, and session deletion all work. Existing timer pause behavior and reminders remain intact. STOP.

## Stage 4 — Filterable statistics + legacy export policy + final integration

**Primary user goal:** Compare like-for-like pumping outcomes without an explosion of single-purpose statistic rows.

**Stats UI:** Add a compact, reusable filter bar with at least date range (7/30 days, all/custom), pump profile (individual/all; optionally category), breastfeeding context (`Yes`, `No`, `Not specified`, `All`), and pump mode (`Normal`, `Power`, `Unknown`, `All`). Optionally include time-of-day bands or a comparison grouping selector. Multiple filters must work **simultaneously**. Show active filter chips and obvious Reset; persist filters only if unobtrusive.

**Results:** Show sample count, total volume, mean and **median** volume per parent session, left/right split when available, and milk per **active pumping minute**, clearly labeling how calculated. Distinguish `sum(volume)/sum(active time)` from a mean of per-session rates; select one and document it. Never mix power-pump phases into session counts. Support side-by-side grouping by pump under the same breastfeeding/mode/date constraints, and by breastfeeding context under the same pump/date/mode constraints. Show sensible empty states and low-sample cautions; do not rank devices as intrinsically better from uncontrolled differences.

**Statistical honesty:** Time of day, preceding breastfeeding, power-pump method and gap since last pump confound comparisons. Display sample size and filters alongside each result. Do **not** calculate or label an estimate of the baby's direct milk intake based on pumping-output differences. A simple expressed-output difference may be shown if explicitly labeled non-intake.

**Legacy backup:** Maintain native JSON as complete-fidelity recommendation. Introduce a preview/confirmation for optional **lossy** original iOS `.pumping` exports when app-specific metadata (nursing flags, profile references, phases) is discarded. Enumerate which fields are lost and warn that the original iPhone app's acceptance is unverified. Preserve session identities, dates, supported pumping quantities and notes wherever actually representable. Do not fabricate times/durations or promise that every native record can be represented. Existing hard failures for unsafe stash transactions, conflicting inventory or unsupported session timing should remain unless a rigorously tested safe conversion exists. Do not mislabel a lossy export as a complete backup. Test against synthetic source fixtures.

**Acceptance:** Cross-filter Spectra vs mobile/wearable with breastfeeding=No, then repeat with breastfeeding=Yes; sample sizes and totals reflect only matching parent sessions. Native backup restores every new field. Legacy export warns before omitting unsupported metadata and refuses genuinely unsafe conversions. Existing `1.4.0-beta.1` real-data-like synthetic upgrade path works without loss. Full suite and physical-device acceptance complete. STOP, then await explicit permission for signed `-beta` publication.

## Release readiness / final checklist (not automatic)

- [ ] All four stages individually accepted on device.
- [ ] Every new field is included in native export/import with compatibility tests for v1 backups and any intervening versions.
- [ ] Room upgrade migration from published beta is verified; sessions, stash ledgers, links, deleted records, original-iOS retention, settings and reminders survive.
- [ ] No accidental user-data reset, no production application-ID change, no signing-key change.
- [ ] Power-pump session count, pumping vs resting time and parent-session volume are consistent.
- [ ] Multi-filter stats and comparison sample counts are correct; no false intake estimate.
- [ ] Legacy lossy-export warnings and strict safety limits are tested.
- [ ] Version and artifact names follow `docs/VERSIONING.md`; final signed beta has a higher versionCode than 7.
- [ ] Build, test, lint, certificate and install-over-existing-signed-beta checks pass.
- [ ] Owner explicitly authorizes Git commit, tag, GitHub release and asset publication.

## Open design decisions to resolve during relevant stage (do not block Stage 1)

- Whether a future optional field records left/right/both breastfeeding and/or feed-end time. Keep it optional; do not build unsolicited screens in Stage 1.
- Whether pump-profile statistics group by a profile's current metadata or a historical metadata snapshot after edits. Choose and document a consistent policy.
- Exact handling of power-pump timing when rests are unknown, accounting for existing activeSeconds <= end-start constraints; do not infer unrecorded rests.
- Which exact legacy session shapes can be exported without compromising timestamps, quantities, or inventory integrity.


## Stage 1 implementation record (1.4.2-dev / code 9)

Scope: **Stage 1 only**. Stages 2–4 remain unimplemented. The 1.4.1-dev Quick Log authority state machine and paused-timer clock logic are unchanged.

### Chosen persistence shape and later relationships

- **Implemented now:** Room schema **4**, migration **3→4** adds only `sessions.breastfedBefore TEXT NOT NULL DEFAULT 'UNKNOWN'`. Kotlin uses `BreastfeedingStatus` (`YES`, `NO`, `UNKNOWN`) with Room's enum conversion. Existing 1→2→3 migrations chain to 4; no destructive fallback. IDs, timestamps, volumes, ledgers, stash foreign keys, raw iOS projections, deletion markers and preference tables are untouched.
- **Planned Stage 2, not created now:** `pump_profiles` with stable text ID, category, optional custom category label, nickname, optional brand/model/notes and archived status. A nullable, indexed `sessions.pumpProfileId` references its ID with deletion restricted (archive referenced profiles; never cascade into sessions). New attribution is an explicit selection; historical records stay unassigned. Statistics will initially use the referenced profile's current descriptive metadata unless Stage 2 acceptance requires snapshots.
- **Planned Stage 3, not created now:** explicit tri-state session power-pump classification; optional `session_phases` with stable ID, session FK `ON DELETE CASCADE`, unique `(sessionId, position)`, pump/rest kind, positive whole-second duration and optional exact boundaries. No per-phase volume or separate session counting. Validate ordered/non-overlapping boundaries and parent consistency atomically. Unknown-rest timing semantics remain a Stage 3 decision, not an inferred field in Stage 1.
- **Planned Stage 4, not created now:** combine predicates over parent sessions and optional profile/phase relationships; no cached statistical totals. Phase rows never multiply session counts/volumes. No intake inference.
- Supporting profile/phase tables and speculative session fields were deliberately deferred. This is allowed by the baseline plan and keeps Stage 1's migration small. Later stages require additive migrations and their own migration tests.

### UI decisions

Quick Log presents one compact `Breastfed before pumping` group of one-tap Yes/No/Unknown choice chips using existing UI conventions. New entries default to Unknown; the user can explicitly record either Yes or No in one tap. Existing/iOS/v1-imported records remain Unknown until changed. No breastfeeding side, feed-end time or other mandatory detail was added. Status also appears in pumping history. Draft selection survives activity recreation; saving/reopening does not change the choice or session timing.

### Native backup v2 and legacy safety

- Export `format: "Pumping", version: 2`; each session includes required string `breastfedBefore`. All three states and all existing supported session/bag/movement/raw-iOS/deletion data round-trip. Existing optional preference restoration behavior is unchanged.
- Read native v1 as well as v2. V1 sessions become Unknown, never No. Repeated identical imports remain idempotent. Existing strict merge/conflict semantics remain: an old v1 backup does not overwrite a subsequently edited session/status; conflicts fail transactionally instead.
- Reject unsupported format versions, unrecognized structural fields, invalid/missing v2 statuses, broken references and conflicting records before commit. Raw retained iOS JSON remains opaque/preserved. Future stages must increment the native format when introducing additional fields/entities and continue accepting v1/v2, rather than silently extending v2 and losing fields in older readers.
- Original iOS decoding/import, stable IDs, exact retained source values and merge tombstones remain compatible. Breastfeeding is never inferred from raw notes/settings or existing iOS fields. Legacy projection snapshots intentionally retain their original field set.
- **Interim safe export policy:** `.pumping` export refuses sessions with explicit Yes/No context and recommends complete JSON v2; Unknown remains representable as absent context. This prevents silent loss now. Stage 4's optional lossy preview/confirmation is still deferred; no Stage 4 feature was implemented early. Existing unsafe timing/inventory export failures remain.

### Stage 1 acceptance and verification

Implementation checks and final test/build results are recorded below after execution. Physical-device acceptance remains the owner gate before any Stage 2 work.

- [x] Explicit Yes/No/Unknown persistence and native v2 serialization; Unknown defaults for historical/v1/iOS data.
- [x] Non-destructive 3→4 migration; original migration chain retained.
- [x] No optional-detail requirement and no Stage 2–4 UI or speculative records.
- [x] Legacy import and deletion/ledger behavior preserved; no silent lossy metadata export.
- [x] Final complete automated test/lint and APK verification recorded (231 unit, 21 emulator/Compose and 12 tooling tests passed; debug/release lint 0 errors, 20 warnings each).
- [x] Non-production APK upgrade/process-restart check recorded: 1.4.1-dev/schema 3 → 1.4.2-dev/schema 4 retained synthetic records, links/ledger and mL input preference; Unknown initialized truthfully and explicit Yes survived a cold process restart.
- [x] Owner physical-device acceptance of Stage 1 confirmed before Stage 2 authorization.


Final Stage 1 verification:

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:lintRelease --max-workers=1`: PASS; 231 unit tests (including eight migration cases across API 26/35). Final invocation reused passing test outputs where unchanged.
- `./gradlew :app:connectedDebugAndroidTest :app:lintDebug --max-workers=2`: PASS, 21 tests on disposable Android 16.1/API 36.1 emulator, including three new context/native-v2 UI workflows and existing Quick Log timing, units, stash, reminders and iOS SAF regressions.
- Python tooling suite: 12 passed. APK metadata/signature checked with aapt2/apksigner; debug and production IDs remain separate.
- APK: `dev-builds/1.4.2-dev/Pumping-1.4.2-dev.apk`, code 9, SHA-256 `5dfa07126e06c4e778441ee18a495303543a01e2e2d4406e7c0bb7b7c269378d`.
- Live synthetic upgrade result: `dev-builds/1.4.2-dev/stage1-upgrade-device-check.json`. An initial emulator system-process ANR under host test load was resolved by restarting the disposable emulator after stopping the idle Gradle daemon; UI driver selectors were corrected to target the session row rather than the same-valued daily total. The successful final check used the staged APK above. No app-data resets or production-device changes occurred.
- No unresolved automated failures. Physical-device UX/upgrade acceptance remains pending; no Stages 2–4 work, commit, push, tag, signing or publication is authorized/performed by this delivery.

Checkpoint preparation retains the complete tested 1.4.2-dev source state for one owner-reviewed commit. Development artifacts now reside in the version directory above; prior local files remain retained. Preparing the index does not authorize a commit/tag or Stage 2.


## Stage 2 implementation record (1.4.3-dev / code 10)

Scope: pump profiles and the breastfeeding label correction only. Stage 1 was accepted on a physical device by the owner. No power-pump phases or filterable statistics UI have been implemented.

### Decisions

- Breastfeeding choices now read **Yes / No / Not specified**; Not specified remains the same internal `UNKNOWN` enum, SQL value and native-backup spelling. This is presentation-only, including history/accessibility text. It does not classify old records as No.
- Profile → **My Pumps** supports Add/Edit/Archive/Restore. Types: Stationary/Electric, Wearable/Mobile, Manual/Hand, Other/Custom. Custom type label, brand, model and notes are optional. Optional details are collapsible.
- New profile drafts suggest a type/brand nickname, with numeric suffixes for duplicates. Once the user types a nickname, later type/brand edits cannot change it. Existing saved nicknames never auto-change during edits. Nicknames are unique across active/archived profiles (case-insensitive validation); duplicate custom names produce a clear error without changing records. Stable UUID IDs, not names, identify pumps.
- Quick Log uses a dropdown of active pumps plus **Unspecified**. Existing sessions retain their stored nullable ID, including archived pumps. The current recorded archived pump remains available in that session's editor only. A new session cannot be assigned an archived pump; saving a stale draft rejects that choice without data loss.
- New-entry default is derived from the most recently saved retained session (`updated`, `created`, ID tie-break). An explicit Unspecified choice, no history, or an archived last pump defaults to Unspecified. No extra preference/duplicate last-used state is stored. Opening old records never applies this default. Rename/archive never changes the last session's data.
- Statistics will group/filter by stable profile ID and stored type, with current profile descriptive metadata (including renamed names/current type); no historical metadata snapshots are fabricated. Profile/association indexes support simultaneous joins with existing context, date/time, quantity and active duration. Stage 3 classification remains future work.

### Persistence and backups

- Room **4→5** creates `pump_profiles` (stable ID, type, nickname, customType, brand, model, notes, archived), adds nullable `sessions.pumpProfileId` with indexed FK **ON DELETE RESTRICT**, plus indexes on profile nickname/type. Existing sessions migrate to null, not an inferred device. No data/schema-destructive fallback. Previous migration chain is retained/tested.
- There is no normal pump deletion action: archive preserves references; the database prevents deleting referenced pumps. Renaming/archiving updates only the profile row. Confirmed full legacy replacement clears sessions before profiles, after the existing native safety-backup workflow.
- Native JSON **v3** exports all profiles (including archived/unreferenced profiles) and every session association. v1/v2 remain importable with unassigned pump IDs and their original breastfeeding semantics. v3 is deliberate: older v2 apps reject the newer version instead of discarding profiles. Unknown future fields/versions, invalid types/flags, broken references, duplicate names/IDs and conflicts reject atomically.
- Original iOS `.pumping` format remains unchanged. It cannot preserve profiles/associations; export blocks this metadata and recommends native JSON v3 until Stage 4's optional confirmed lossy export exists. Existing nursing/inventory/timing safety restrictions remain.
- Default artifact policy remains `dev-builds/1.4.3-dev/Pumping-1.4.3-dev.apk`. Per the explicit Stage 2 delivery request, an identical verified copy will also be supplied at `test-builds/Pumping-1.4.3-dev.apk`. Existing release verification paths/signing guards are unchanged.

### Acceptance (updated after final verification)

- [x] Stage 1 physical-device acceptance confirmed by owner.
- [x] New model, 4→5 migration and v3/v1/v2 import compatibility implemented.
- [x] No Stage 3/4 UI or data fabrication; timing/volume calculations unchanged.
- [x] Final verification: 249 unit tests, 25 Compose/instrumentation tests, 13 tooling tests passed; debug/release lint 0 errors and 20 existing warnings each.
- [x] Actual Dev 1.4.2/schema 4 → 1.4.3/schema 5 upgrade retained records, links, ledger, raw tables and mL setting. Profiles/default selection survived cold restart. Production beta and Dev launched side by side with distinct UIDs and separate synthetic records.
- [ ] Owner physical-device feedback on Stage 2 (STOP here before Stage 3).


Final Stage 2 verification and handoff:

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease --max-workers=1`: PASS; 249 unit tests including ten migration cases across API 26/35, old/native-v3 backup cases, archived-link safety, nickname validation and simultaneous profile/type/context/date query readiness. Repeat original-iOS merge retains locally assigned pump IDs.
- `./gradlew :app:connectedDebugAndroidTest :app:lintDebug --max-workers=1`: PASS, all 25 tests on disposable API 36.1. Four new pump-management/selection tests cover all types, optional fields, auto/custom names, renaming/archiving, last-used/Unspecified, and archived historical edits. Native SAF restore includes a complete archived profile. Existing Quick Log timing/units/breastfeeding, stash and reminder workflows passed. The initial existing stash touch-injection race was resolved in the test by waiting for editor dismissal and invoking the button's accessibility action; no stash behavior changed.
- Actual final-APK upgrade/coexistence evidence: `dev-builds/1.4.3-dev/stage2-device-check.json` (synthetic emulator data only; no reset/uninstall or production-device access). Old `UNKNOWN` remains the exact database value while its label is Not specified. Selecting a pump changed only its association and normal edit timestamp, not recorded quantities/timing/context/notes/inventory.
- APK: `dev-builds/1.4.3-dev/Pumping-1.4.3-dev.apk`, identical explicitly requested copy `test-builds/Pumping-1.4.3-dev.apk`; version code 10; SHA-256 `d442bd87bdea1599c5e8707775f9cc51999d3851fdd44cdc0385d0b5ca3dc1c7`. Verified app ID `dev.killermelon1458.pumping.debug`, label Pumping Dev and standard debug certificate. Existing published 1.4.0-beta.1 asset unchanged.
- No unresolved automated failures. Global case-insensitive nickname uniqueness (including archived profiles), conservative last-saved defaulting, and grouping by current profile descriptive metadata are intentional design choices for owner review. Physical-device Stage 2 feedback remains pending. STOP before Stage 3/4; no commit, tag, push or production signing/publication performed.
