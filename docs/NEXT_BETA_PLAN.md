# Pumping — Next Beta Feature Plan

**Status:** Stages 1–3, the 1.4.7 UX milestone and Stage **4A statistics** are owner-accepted. Promotion/publication of **1.4.8-beta/code 15** is explicitly authorized after verification. Stage **4B legacy export** remains deferred and requires separate authorization.
**Historical public baseline:** `1.4.0-beta.1` (`versionCode 7`); current promotion is `1.4.8-beta` (`versionCode 15`).
**Accepted application source:** `1.4.8-dev` / code 15, commit `70e373f7027cd97843475554f9b1904a4aa2c306`, annotated tag `v1.4.8-dev`; promoted without code/revision changes.
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

**Current authorization split:** Implement statistics as **Stage 4A** only. Legacy export preview/lossy-policy changes are **Stage 4B**, deferred until owner physical-device acceptance and separate authorization. The historical combined acceptance below describes eventual full Stage 4, not this partial delivery.

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
- Power-pump unknown-rest timing and editor authority are resolved by the latest approved Stage 3 correction below: nominal basic timing with ordinary controls, separate detailed timestamp authority, and no inferred rests.
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


## Stage 3 implementation record (1.4.4-dev / code 11 — superseded UX)

**Historical record only:** The owner rejected this build on physical-device UX review. The approved 1.4.5 correction below supersedes conflicting timing, anchor-label, removal and acceptance decisions in this section.

Scope: **Stage 3 only**, based on committed/tagged Stage 2 `c1e613a`. Stage 4 filtering/comparisons and optional lossy legacy export are not implemented.

### Timing and UI decisions

- New Quick Log entries default to **Normal**. Existing/migrated/native-v1–v3/iOS records remain internal `UNKNOWN` (shown as Not specified) until explicitly classified. A single Power Pump switch marks the parent; optional Timing details starts collapsed and the preset is applied only by its button. Existing records can explicitly return to Not specified.
- A new manual toggle-only power pump records **known active duration**. `elapsedKnown=false` makes its `end-start == activeSeconds*1000` window a pair of active-time anchors, not an independently measured elapsed span. Editor/history/notification wording identifies anchors and does not invent breaks. Existing known timestamps and timer spans stay known when classified. Removing details preserves known active time and the recorded span, with confirmation before discarding interval rows.
- With details, ordered contiguous Pump/Rest durations determine both totals: Pump sum = activeSeconds, all intervals = elapsed. One parent owns the total volume, stash links and statistics contribution. The explicit 20/10/10/10/10 preset produces 40 active and 60 elapsed minutes. Add/remove/reorder/kind/duration changes preserve interval IDs. Durations use the existing whole-second precision, not per-phase volumes.
- Ordinary manual Quick Log retains the existing `[End, Duration]` last-two-distinct-edits state machine, intermediate typing handling and future-end Save confirmation. For detailed routines the duration summary is read-only; changing interval totals commits a Duration edit through that same state machine and preserves the last timestamp authority. Timestamp edits must preserve the explicit routine's total span; inconsistent edits show an error rather than scaling/fabricating intervals. Reordering or changing kind without changing elapsed duration does not retime the session or alter authority.
- Timer clocks/recovery are unchanged. Editing a paused session retains its separately recorded wall-clock span rather than replacing it with active duration. Optional timer details must match the already recorded active time and elapsed span; a preset cannot overwrite recorded pauses. Timer timestamps retain their original millisecond precision; interval durations are whole seconds, so a recorded trailing fraction below one second remains at the final derived boundary. No timer pause information is discarded.
- Midnight/date transitions use epoch-millisecond arithmetic; no local-time subtraction is used. Intervals have no gaps/overlap because boundaries derive contiguously from the parent start. No speculative exact-boundary editor was added.

### Persistence and backup decisions

- Room **5→6** adds session `powerStatus` (default `UNKNOWN`) and `elapsedKnown` (default true), plus `session_phases` with stable text IDs, parent FK **ON DELETE CASCADE**, unique `(sessionId, position)`, Pump/Rest kind and whole-second duration. Classification has an index for later combined filters. All existing records, profile/context values, IDs, timestamps, quantities, inventory ledger, deletion markers, retained iOS data and settings remain intact. All earlier migrations are retained; no destructive fallback.
- Repository writes validate parent/phase totals, positive durations, contiguous positions, IDs, at least one Pump interval, overflow and ownership inside a Room transaction. Deleting the parent removes children atomically and retains existing stash-deletion choices. Phases never become independent sessions or inventory transactions.
- Native JSON **v4** preserves classification, elapsed marker, every phase, all profiles/context and prior records. Native **v1/v2/v3** imports retain their previous semantics and initialize power status Unknown with no inferred phases. Older readers reject v4 rather than silently losing metadata. Invalid references/order/totals/flags, fractional seconds, duplicate IDs, future fields/versions and conflicts reject without partial imports.
- Repeated matching imports remain idempotent. Session deletion tombstones also suppress its children. Existing sessions require an exact matching phase list during merge: stale backups cannot resurrect removed intervals or silently change their order. Restore a differing snapshot to an empty installation or use the existing deliberate replacement workflow where supported.
- Original iOS decoding/import and retained source projections are unchanged. `.pumping` export continues to refuse unsupported metadata, now including any known power classification, unknown elapsed timing or phase rows, and recommends complete native JSON v4. Stage 4's confirmed lossy-export workflow is deliberately deferred.
- Stable parent/profile IDs, indexed power classification, activeSeconds and existing context/time fields support future simultaneous filtering without implementing Stage 4 UI or multiplying counts/volumes.

### Acceptance and verification

- [x] Normal/default and historical Unknown classifications, toggle-only entry, optional preset, custom intervals and parent-only totals implemented.
- [x] Room 5→6 non-destructive migration and previous migration chain verified; native v4 round-trip and v1/v2/v3 imports pass.
- [x] Existing timing authority, future warning, paused timers, nursing context, profiles, reminders, stash, deletion and original iOS regressions pass in the unit suite.
- [x] Actual canonical-APK Dev 1.4.3/schema 5 → 1.4.4/schema 6 upgrade preserves synthetic session/profile/context, stash/ledger, mL input and last-used selection without uninstall/reset. New preset and all saved records survive process restart. The old session remains Unknown with no inferred phases; production/Dev have distinct UIDs and separate records.
- [x] Final full Compose/instrumentation rerun: **30 passed**, no failures/errors/skips, on disposable API 36.1. Source-only Git review checkpoint prepared after all checks.
- [ ] Owner physical-device acceptance. STOP before Stage 4, commit/tag, production signing or publication.

Final unit/build/lint checks: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease --max-workers=1` passed; **273 unit tests**, including **12 migration cases across API 26/35**, and both lint variants with **0 errors / 20 existing warnings**. Python tooling suite: **13 passed**. Native v4 tests cover archived profiles, previous context, inventory/retained iOS/deletion data, stable phases, atomic rejection, matching repeated merge and stale phase-list conflicts. Repeating original-iOS merge preserves a locally recorded power classification.

Live synthetic upgrade evidence: `dev-builds/1.4.4-dev/stage3-device-check.json`. APK `dev-builds/1.4.4-dev/Pumping-1.4.4-dev.apk`, code **11**, SHA-256 `0995c278d125626caaa04573e1ed1b3a288709bffd3a88b6171f11f4af1a894f`. aapt2 verifies Dev ID/label, API 26 minimum / 36 target / 37 compile; apksigner verifies the existing standard debug certificate. Published 1.4.0-beta.1 and retained Stage 2 APK checksums are unchanged. No personal records or backup fixtures are packaged.

Owner review should cover active-time-anchor wording, fixed routine duration constraints and timer matching requirements. Emulator UI testing uses disposable API 36.1; API 26/35 persistence/migration coverage is Robolectric, not physical-device or API-26 emulator acceptance. Samsung notification/battery behavior and actual device UX remain physical-device checks. Stage 4's filtering/comparisons and optional lossy legacy export remain absent.


Final Stage 3 UI verification: `./gradlew :app:connectedDebugAndroidTest :app:lintDebug --max-workers=1` **PASS**, all **30** tests (2m27s). Five new power-pump workflows cover toggle-only persistence/reopen, preset/custom durations/add/remove/reorder, invalid draft rejection, historical/paused timer editing with matching custom details, and confirmation before removing intervals. Earlier runs identified an ambiguous Cancel test selector and an existing stash touch-injection race; tests now exclude the editor header's Cancel and use/verify the stash switch's accessibility action. A timer draft active-total cache issue found during review was fixed and covered by the matching-details workflow. No remaining automated failures. Final APK bytes match the verified staged checksum; build/report/upgrade evidence remains local in its ignored version directory.

Review checkpoint: 37 source/schema/test/documentation/configuration files staged; generated builds/reports, private references, real backups and signing material excluded. No commit/tag/push/publication or Stage 4 work. Suggested future checkpoint: `dev: 1.4.4-dev - Power pump tracking`, annotated tag `v1.4.4-dev` only after owner acceptance/authorization.


## Stage 3 approved UX correction (1.4.5-dev / code 12)

**Physical-device follow-up:** The approved basic/detailed semantics remain authoritative, but 1.4.5-dev failed Duration synchronization when focus/IME callbacks did not occur. The 1.4.6 record below supersedes deferred Duration commit behavior; the staged checkpoint remains uncommitted.

**Authoritative decisions:** These owner-approved rules supersede earlier Stage 3 requirements and the rejected 1.4.4-dev UX. Implement this correction only; Stage 4 requires separate physical-device acceptance and authorization.

### Basic mode

- With no detailed intervals, Power Pump changes classification only. Start, End and Duration controls are identical with the switch on or off. Duration means active pumping minutes. The unchanged `QuickLogTiming` last-two-distinct-edits history initializes `[End, Duration]`; toggle/focus/recomposition never changes it. Example End 9:44, Duration 20→40 produces Start 9:04 in either classification.
- No user-facing anchor terminology. Basic Power Pump does not require details or assume rests. Start–End is a nominal representation of active duration, not evidence of zero resting or actual overall elapsed time. Keep the existing internal `elapsedKnown` distinction; no migration or rewriting of saved timestamps for presentation.
- Existing basic records open with saved values unchanged. Timed/paused records retain independently recorded active time and actual span, as in the existing timer editor, regardless of Power classification.

### Detailed mode and transitions

- Explicit interval entry activates detailed mode. Read-only **Pumping time** sums Pump durations; read-only **Total elapsed time** sums Pump + Rest. Do not show an editable basic Duration beside those totals. Optional details stay collapsed on a fresh opening. The optional 20/10/10/10/10 preset remains 40 pumping + 20 rest = 60 elapsed minutes, one parent session/volume.
- `RoutineTiming` handles detailed timestamps independently of ordinary duration authority. A manual Start edit retains intervals and recalculates End; a manual End edit recalculates Start. Both are valid even when alternating. Do not scale Rest phases or reject a valid new timestamp because of the old span.
- Track the most recently **manually** edited timestamp separately (default End). Interval duration changes and basic→detailed entry preserve that timestamp and calculate the other using total elapsed. They do not insert artificial Duration edits into ordinary edit history. Actual manual timestamp edits remain actual history edits; phase recalculations do not.
- Removing all manual-session details returns to basic active Duration, retains the latest manually edited timestamp and calculates the other from active duration. Confirm any interval removal/replacement that discards draft or saved rows; Cancel leaves the routine unchanged. Classification changes that would clear intervals use the same confirmation. Hiding details never removes them.
- Protect timer pause information: basic timer edits keep active time separate from actual recorded span. Initial details must account for recorded timer totals; existing detailed timer timestamp edits move the other endpoint and explicit interval edits use their supplied totals. Removing timer detail rows keeps its recorded active time and actual span rather than collapsing away pauses. Original timer millisecond precision is retained at the final phase boundary (whole-second interval precision).
- Existing records are never retimed just by opening/saving. Activity recreation retains the draft's manual timestamp authority. Future-End Review/Save anyway behavior remains intact.

### History and persistence

- Small original scalable outline indicators: baby head with one hair for `breastfedBefore=YES`, breast pump for `powerStatus=POWER`, existing blue snowflake for a retained stash relationship. They can coexist on a compact separate indicator row, keeping timestamp and volume space free. Icons are non-interactive and have content descriptions; session details also explain the recorded context/mode.
- Retain **Room schema 6** and **native JSON v4**, including v1/v2/v3 import support, stable IDs, phases, context, profile associations, deletion suppression and transactional validation. The nominal duration validator accepts the existing whole-second precision when timestamp milliseconds differ; it still rejects a nominal span inconsistent with active duration. No data rewrite, destructive migration or backup-field change.
- Legacy `.pumping` format/import remains unchanged; unsafe metadata export remains blocked until Stage 4's explicit lossy workflow. Milk quantities, inventory and timer clock/recovery implementation remain unchanged.

### Stage 4 metric definitions (data semantics only)

- **Milk per pumping minute:** summed expressed milk divided by summed activeSeconds/60 for matching parent sessions.
- **Milk per elapsed minute:** summed milk of eligible matching parent sessions divided by summed **known actual** elapsed minutes for those same sessions. Always show included/excluded sample counts. Do not use all-session milk over a subset's elapsed time.
- Complete detailed phases establish elapsed time. Basic non-timer Power Pump nominal windows are excluded, including older ambiguous basic Power rows that retain `elapsedKnown=true`. Timers retain known actual elapsed time even when paused and without phase details. Ordinary records with known elapsed timing can contribute. Unknown actual elapsed is excluded rather than treated as zero.
- `actualElapsedMillis` centralizes eligibility for later use; no Stage 4 statistics screen or comparison is implemented. Primary active-time calculations continue to use parent activeSeconds. Profile/mode/context/date filters remain independently composable; phases never add sessions or duplicate milk volumes.

### Verification and acceptance

Final results will be recorded after unit, migration/backup regression, emulator UI, Python tooling, debug/release lint and staged-APK upgrade checks. The retained 1.4.4-dev APK must remain byte-for-byte unchanged. Physical-device acceptance of this correction is pending; emulator results cannot establish that acceptance. No commit, tag, push, signing or publication is authorized by this correction.


### Final 1.4.5-dev verification and review checkpoint

- [x] Basic switch parity, ordinary last-two-edited-fields rules, explicit 9:44/20→40/9:04 example, repeated/alternating edits and absence of anchor terminology implemented/tested.
- [x] Detailed read-only totals; optional 40 pumping / 20 rest / 60 elapsed preset; custom phase changes/add/remove/reorder; Start/End movement and last-manual-timestamp preservation; confirmation/Cancel and mode transitions verified.
- [x] Existing basic/detailed records, no-op duration commits, paused timers, activity recreation, future-End confirmation, context/profiles, stash/deletion, reminders and old/native-v4 backup regressions verified.
- [x] Baby-head/real pump outline/snowflake indicators tested in all 18 state combinations at 280 dp / 1.3× font scale; descriptions and no icon click action verified. Synthetic final-APK screenshot reviewed.
- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease --max-workers=1`: PASS (1m55s), **280 unit tests**, including **12 migration cases on API 26/35**, native v4 round-trip and v1/v2/v3 compatibility; debug/release lint each **0 errors / 20 existing warnings**. Python tooling: **13 passed**.
- [x] `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest :app:lintDebug --max-workers=1`: PASS (2m57s), **36 tests**, no failures/errors/skips, disposable API 36.1. An earlier 35-case run also passed before final no-op/eligibility coverage.
- [x] Real staged-APK **1.4.4-dev/code 11 → 1.4.5-dev/code 12** upgrade, schema 6 throughout, without uninstall/reset: every stored table identical immediately after upgrade. Reopening/saving basic and detailed records preserves values except normal updated metadata; phases/profile/context/stash/ledger and mL preference retained. Cold restart and independent production/Dev launch checks passed. Evidence: `dev-builds/1.4.5-dev/upgrade-device-check.json`.
- [x] Staged APK SDK metadata and existing debug certificate verified. `dev-builds/1.4.5-dev/Pumping-1.4.5-dev.apk`, SHA-256 **`75d635786d1924643e36d49b334822dbc41daba5383e4209faa27f8f1a849ca0`**. Existing 1.4.4-dev and published 1.4.0-beta.1 APK checksums unchanged. No personal records or compatibility fixtures packaged.
- [ ] Owner physical-device acceptance of this correction. Emulator/Robolectric results do not establish it. **STOP before Stage 4.**

The cumulative Stage 3 source checkpoint is prepared for owner review from baseline `c1e613a`; the rejected 1.4.4 candidate was not committed/tagged by Codex. No commit, tag, push, publication or production signing performed. Local artifacts/reports remain ignored. See the version directory's BUILD_INFO.md and STAGED_FILES.md for final index verification/list. No unresolved automated failures; physical UX/device checks remain the owner gate.


## Stage 3 Duration synchronization correction (1.4.6-dev / code 13)

**Scope:** Correct the original Quick Log synchronization regression only. Keep the approved basic/detailed Stage 3 semantics and existing cumulative source checkpoint. No Stage 4, commit, tag, push or publication.

### Confirmed cause and before evidence

Before changing production code, two new actual Compose tests ran against 1.4.5 source on the disposable API 36.1 emulator. Both failed at the expected recalculated Start assertion. The switch test first asserted the displayed Duration was 40, Power Pump was on, and Duration still had focus. This reproduces the physical-device state transition rather than relying on a pure calculation test.

`SessionEditor` updated only its editable Duration string and `durationDirty` flag in onValueChange. The canonical seconds/timestamps/history changed only through `Common.Field` onCommit, invoked on focus loss or IME Done. Touch-mode switches/chips need not take text-field focus; hiding the keyboard is also not a reliable focus change. Power Pump correctly changed classification only and therefore did not flush the deferred timing draft. The pure QuickLogTiming arithmetic was not the cause. Save attempted a fallback commit, but could not prevent a contradictory live display; final validation also depended on the deferred flag.

Evidence retained locally: `dev-builds/1.4.6-dev/regression-before/` (2 failed UI tests, expected timing assertion), and `regression-after/` (the same two plus expanded interaction coverage, 13 passed).

### Implementation decisions

- Valid Duration text now calls the existing timing model synchronously in onValueChange. Basic manual Start/End/seconds/history remain authoritative and consistent for each valid edit. `QuickLogTiming` last-two-distinct-edits arithmetic and `[End, Duration]` initialization are unchanged; repeated Duration keystrokes remain repeated edits to that one field.
- Keep a separate saveable raw text draft. Synchronizing timing never rewrites that draft, preserving decimal text, cursor and selection. Empty/incomplete/invalid numeric input does not enter the model. It remains visible for correction; Save rejects it instead of using the last valid duration. Use the existing BigDecimal parsing/whole-second precision (including valid `.5` and `40.` numeric forms), without inventing new input syntax.
- Remove `durationDirty` as a source of truth. Focus/IME callbacks remain idempotent validation, not synchronization prerequisites. No forced focus clearing or keyboard dismissal, no polling/recomposition effects, no classification-toggle timing changes. Other Field consumers keep their existing behavior, including separately committed detailed phase inputs.
- Save re-parses the actual displayed basic draft every time, reconciles it through the model, and checks displayed seconds against the authoritative seconds before persistence. Invalid text cannot silently fall back to stale model values.
- Programmatic duration text uses decimal `durationMinutes`, rounded upward only at a fine decimal-minute representation so parsing at existing whole-second precision returns the original seconds exactly. This avoids a floating-point formatting/reparse hazard for values such as a paused timer's 1234 seconds. No saved timestamps or durations are normalized merely by opening/saving.
- Timers keep independent active time and recorded paused span. Detailed power routines retain interval-derived active/elapsed totals and separate last-manual-timestamp authority. Icons, context/profile/stash relationships, reminders, quantities, database schema 6, native backup v4 and older/native/iOS compatibility remain unchanged.

### Acceptance and verification

- [x] Before fix: 2/2 actual Compose regression tests fail on 1.4.5, including retained focus after a Power switch tap.
- [x] After fix: all 13 dedicated Compose duration tests pass, including immediate no-Done edit, switch/chip/profile/other text field/keyboard dismissal, already-Power/repeated toggles, manual Start/End authority, invalid drafts and Save, decimal text/caret/recreation, save/reopen, detailed phase calculations and paused-timer active edits.
- [x] Final build/unit/lint: **282 unit tests passed**, including 12 migration cases across API 26/35; debug/release lint each **0 errors / 20 existing warnings**. `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:lintRelease --max-workers=1`: PASS (1m18s). Python tooling: **13 passed**.
- [x] Final complete emulator suite: **49 passed**, no failures/errors/skips, disposable API 36.1; `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest :app:lintDebug --max-workers=1`: PASS (3m34s). Includes all 13 new Duration cases and all existing Stage 3/Quick Log/timer/backup/stash/context/profile/reminder workflows. Before-failure evidence is retained separately.
- [x] Real staged-APK **1.4.5-dev/code 12 → 1.4.6-dev/code 13** update, Room 6 throughout, without uninstall/reset: every stored table identical immediately after upgrade. Reopening/saving basic and detailed sessions changes only normal updated metadata; phase IDs/order/totals, quantities, notes, profile/context, stash/ledger and mL preference retained. Cold restart and production/Dev coexistence passed. Evidence: `dev-builds/1.4.6-dev/upgrade-device-check.json`.
- [x] APK metadata/signature/integrity verified: **dev.killermelon1458.pumping.debug**, label Pumping Dev, version **1.4.6-dev/code 13**, min/target/compile **26/36/37**, existing standard debug certificate. APK `dev-builds/1.4.6-dev/Pumping-1.4.6-dev.apk`, SHA-256 **`3439d663f9eb044015a9c5047e7fdddc26ce2c5c5620b3e655b682e156c0db67`**. Old 1.4.5-dev/1.4.4-dev/public beta APKs unchanged. No backup fixtures or synthetic test classes packaged.
- [x] Cumulative Stage 3 source-only checkpoint prepared for review; generated/private artifacts excluded. Final staged list and cached whitespace/privacy audit are recorded in the version directory's STAGED_FILES.md. No commit/tag/push/publication.
- [x] Owner physical-device testing and acceptance of **1.4.6-dev/code 13** confirmed on **16 November 2026**, completing Stage 3 including the Samsung no-Done synchronization correction. This is owner acceptance, separate from emulator verification. Stage 4 remains unauthorized.


### Accepted Stage 3 Git checkpoint — 16 November 2026

The owner accepted 1.4.6-dev/code 13 on a physical device and explicitly authorized one cumulative Stage 3 commit and annotated local tag `v1.4.6-dev`, with commit message `dev: 1.4.6-dev - Power pump tracking and timing fixes`. The previously staged 46-file source snapshot matched the retained 1.4.6 review patch digest and verified APK checksum before acceptance-only documentation was added. Recorded verification remains 282 unit / 49 emulator-Compose / 13 tooling tests passing and debug/release lint zero errors (20 existing warnings each). No new feature code is included in this checkpoint, no generated/private artifacts are tracked, and no push or publication is authorized. Subsequent UX work must be a separate development revision and must not be included in this accepted checkpoint.


## Additional UX milestone — 1.4.7-dev / code 14

**Scope:** Owner-authorized Quick Log usability work after accepted Stage 3. This is separate from Stage 4; statistics and legacy-export changes remain unauthorized.

### Accepted baseline checkpoint

Part 1 was completed before any 1.4.7 feature edits: committed accepted 1.4.6-dev/code 13 as **`d08e8653b653ef0edbec5cc32cb563c268b6b082`**, message **`dev: 1.4.6-dev - Power pump tracking and timing fixes`**. Annotated local tag **`v1.4.6-dev`** resolves to that exact new commit (parent `c1e613a`), not the previous checkpoint. Its source version is still 1.4.6/code 13. The 46-file staged snapshot matched the prior reviewed patch digest and APK checksum; only owner-acceptance documentation was added. No push, release or upload. New 1.4.7 work is not part of that commit/tag.

### Independent date/time controls

- Start and End retain their compact timestamp presentation but date and time are separate 48 dp-minimum touch targets. Date opens the existing calendar **only**; time opens the new wheel **only**. This applies to new and existing session editors. Other non-session timestamp controls retain their existing behavior.
- Date replacement preserves local time, including seconds/milliseconds. Time replacement preserves date and the unexposed seconds/milliseconds, making a change and exact visible revert reversible. Merely opening/dismissing or confirming an unchanged value does not touch timing authority.
- Both operations retain the old overlap offset where valid; nonexistent DST local times resolve forward using the existing Java time-zone policy. Normal basic/detailed/timer authority remains in SessionEditor; pickers deliver one confirmed Start/End edit, never intermediate scrolling edits.

### Native wheel implementation

- Use Android **NumberPicker** embedded through **AndroidView**, with native dragging, fling, snap, wrapping, accessibility and direct numeric entry. No new dependency or custom gesture engine. Native programmatic value synchronization does not call its user-change listener; current callbacks use updated Compose state.
- 12-hour mode: 1–12 hour, 00–59 minute, tappable AM/PM. Crossing 11→12 or 12→11 flips period; 12↔1 wrapping does not. 24-hour mode: 00–23, no period control. Minutes are independent one-minute steps (minute wrapping does not advance the hour/date).
- Temporary hour/minute state survives activity recreation. Done validates any native typed value and applies once; Cancel, Back or switching to another picker discards the temporary choice. Only one time picker expands. Record Save is disabled while it is open; users explicitly Done/Cancel first.
- Normal inline height is **156 dp** (108 dp wheel + action row), within the 130–170 dp target. Narrow content below 240 dp, font scale above 1.3, landscape, or a window below 480 dp high uses a compact AlertDialog fallback. Native wheels retain theme-appropriate contrast, touch handling and keyboard entry. Calendar dialogs are disposed safely with the editor lifecycle.

### Semantic discard policy

- Capture a normalized private saved-state snapshot **once**, after default pump lookup, existing phase load and existing allocation load finish. Until then, fields show Loading and Save/unit controls are disabled; Cancel/Back can close the untouched editor. This prevents loading/recomposition/defaults from becoming edits or swallowing a real first edit into the baseline.
- Retain the baseline as a compact private SHA-256 fingerprint of length-prefixed normalized values, avoiding duplicate notes/large text in saved state. Compare current values to that baseline on both Cancel and system Back. Include timing and duration draft, all milk drafts/split/no-milk, notes, context, pump ID, power classification, ordered phase kinds/duration drafts, stash switch/custom allocation drafts/locations, and reminder choice/custom deadline.
- Normalize valid numbers to stored precision; preserve blank/invalid/incomplete text distinctly. Unit switching itself persists immediately and does not lose work on Cancel; equal physical volumes compare equal across units. Include hidden custom drafts, but normalize untouched auto allocations/default reminder times as derived state. Phase snapshots project valid pending phase durations without mutating editor timing. Stable IDs, authority history, expansion/focus and internal derived flags are not independent user edits.
- Reverting all editable values clears the warning. No permanent hasEverEdited flag. Canceling the discard dialog retains the entire draft, including picker state. Opening/dismissing a picker is not dirty; native wheel values are not session edits until Done.
- Room remains **schema 6**, native JSON remains **v4** (older supported imports retained). No database/backup/signing/identity changes and no record rewrite. Immediate Duration synchronization, both power modes, pause handling, stash links, context/profiles, reminder/future-End rules and existing history indicators are preserved.

### Verification and owner gate

- [x] Full debug **290/290** and release **290/290** unit tests passed, including 8 new editor-UX tests, DST boundaries, compact snapshot serialization, prior migrations, backups, reminders, timing and inventory regressions.
- [x] Full API 36.1 disposable-emulator suite **68/68** passed, no skips: the 49 previous workflows plus 16 editor-UX and 3 layout tests. Coverage includes real native wheel drag/snap and accessibility steps, period boundaries, 24-hour mode, temporary picker recreation, independent date/time edits, detailed timing, discard/revert/invalid drafts, hidden allocation drafts, default pump loading and system Back.
- [x] Python tooling **13/13** passed. Debug and release lint each **0 errors / 20 existing warnings**, with no new warnings.
- [x] `:app:assembleDebug` automatically staged and independently verified the APK: **1.4.7-dev/code 14**, debug package/label, API 26 minimum / 36 target / 37 compile, unchanged debug signing certificate. SHA-256: `762cf3d7e3e58f16efd23fc1c11289e03fa224af944ee2beec59a196d445b295`.
- [x] Actual APK **1.4.6→1.4.7 in-place upgrade** passed on a fresh read-only API 36.1 emulator without uninstall/clear-data: every stored table was identical after upgrade; reopening/saving synthetic basic and detailed sessions preserved all data except expected updated timestamps; five phase IDs/order/totals, context, pump, stash ledger, mL preference and cold restart survived. Published production beta and Dev coexisted under distinct UIDs with independent synthetic records.
- [x] Additional actual-APK display check passed at normal size, font scale **1.6**, and landscape plus font scale **1.6**: native wheels and Done/Cancel remained visible; screenshots were visually inspected for clipping, and dismissing the picker/editor required no false discard warning. Screenshots/results stay local in the version directory.
- [x] Owner accepted 1.4.7 as the development baseline for Stage 4A, committed/tagged `40c8e04` / `v1.4.7-dev`. This records owner acceptance; no additional device observations are invented.

The accepted 1.4.6 APK remains byte-identical (`3439d663f9eb044015a9c5047e7fdddc26ce2c5c5620b3e655b682e156c0db67`). New version: **1.4.7-dev/code 14**, ID **dev.killermelon1458.pumping.debug**, label **Pumping Dev**, staged by the existing workflow under `dev-builds/1.4.7-dev/`. New source is to be staged for review only; no 1.4.7 commit/tag, push, publication or production signing. Stop for owner physical-device review. Stage 4 remains unauthorized.


## Stage 4A — Filterable statistics (1.4.8-dev / code 15)

**Scope:** Statistics only, above accepted `40c8e04` / `v1.4.7-dev`. Stage 4B legacy-export changes are explicitly deferred; no lossy-export preview or safety-rule changes belong in this milestone.

### Implementation decisions

- Retain the existing Stats journey streak, countdown/goal editor, daily output chart, previous-period comparison/navigation, discoveries, left/right information, Stash ledger analytics, current inventory and feeding savings. Streak/goal describe the whole journey. All-time feeding savings and Stash analytics remain independent of pump-specific filters.
- Pump analytics default to **Last 30 days**, all pumps, all breastfeeding contexts, all modes. An expandable in-page panel supports Last 7 / Last 30 / All time / inclusive Custom dates, one or multiple pump IDs (including archived profiles and explicit Unspecified), Yes / No / Not specified context, and Normal / Power Pump / Not specified mode. All predicates combine with **AND**. Active selections remain visible and Reset restores defaults. Filters survive tab/activity recreation through saved UI state, not new database/settings fields.
- Date membership uses the parent's **start timestamp** in the device's current local time zone: inclusive first local midnight, exclusive midnight after the final date. Session milk is assigned once to its start date, including midnight-crossing sessions. Rolling 7/30-day and custom windows navigate in equal-length calendar-day blocks; previous-period totals use identical non-date filters. All time includes every saved timestamp, including intentional future records. Invalid custom date order shows an error/empty result rather than silently applying all time.
- Reusable pure Kotlin `Statistics.kt` filters and aggregates parent sessions separately from Compose. Stable parent IDs are deduplicated defensively. Quantities stay integer microliters; BigDecimal calculates fractional means, even medians and rates without mutating records. The shared volume formatter accepts decimal canonical values and uses the existing exact US-fluid-ounce conversion and Display Units.
- Summary: count, total, mean/median volume. Efficiency: total/mean active time, aggregate milk per pumping minute, recorded left/right totals/sample count, known elapsed time/rate and eligible-session sample count. Empty means/rates are **N/A**, not zero; a true zero-volume result remains zero. Small samples (1–4) are cautioned.
- Active rate = sum of parent milk / sum of recorded active minutes, never the mean of per-session rates. Elapsed rate uses **both milk and elapsed time from the same eligible subset**. Reuse `actualElapsedMillis`: exclude elapsedKnown=false and phase-less manual power pumps even if their flag is true; complete validated power phases and recorded paused timers are eligible. Invalid timing is excluded from elapsed measurements without changing records. Phases never count as sessions or supply milk volumes.
- Comparisons group the same filtered cohort by stable pump ID or breastfeeding context. Current nickname/archive metadata is used consistently; rename/archive never changes attribution. Pump selection is multi-select. Context groups show all three legitimate categories, including zero samples. No ranking, intake inference, or causal effectiveness claim; explain sample/context/time-of-day differences. Optional pump-category/time-of-day controls are not added in this focused stage; the model remains available for future approved extensions.
- Charts follow active Pump filters; ranges longer than 62 days and All time use chronological monthly totals to bound chart work. Ordinary short ranges retain daily bars and correct per-date counts across month boundaries. Stash retains its original separate Week / calendar Month / All controls and inventory/ledger totals.
- Room remains **6**, native JSON **v4**, migrations/imports/deletion history/signing/identities unchanged. Add only a read-only Room phase Flow for existing rows. No editor, timer, stash transaction, reminder or legacy-export behavior changes.

### Verification / acceptance

- [x] **310/310 debug unit and 310/310 release unit tests passed**, including 20 new pure statistics cases and all existing migration/native/legacy/timer/inventory/reminder regressions. Added combined predicates, local-midnight/DST/time-zone boundaries, exact mean/median and aggregate rates, eligible-only elapsed numerator/denominator, split quantities, zero/empty, archived/renamed ID attribution, deduplicated parent counting and realistic Spectra/Momcozy cohort tests.
- [x] Full disposable API 36.1 emulator suite **78/78 passed**, no skips/errors/failures (68 prior workflows + 8 statistics workflows + 2 layout cases). Focused new 10-case subset passed. Two existing cases were then strengthened and rerun: **2/2 passed**, verifying exact displayed totals/means/medians, active time, both rates and the retained core active-duration assertion; these are not additional distinct tests.
- [x] Python tooling **13/13 passed**. Debug and release lint each **0 errors / 20 existing warnings**, no new warnings. Final `testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug --max-workers=1` succeeded in 2m17s.
- [x] Actual staged APK **1.4.7/code 14 → 1.4.8/code 15** upgrade passed without uninstall/clear-data on a fresh read-only API 36.1 emulator. Every stored table was identical immediately after upgrade; reopening/saving both basic and detailed sessions changed only normal updated timestamps. Phase IDs/order/timing, quantities, profile/context/stash/ledger and mL preference survived; cold restart and independent production/Dev UIDs/records passed.
- [x] Version-derived staged APK verified independently with aapt2/apksigner: **1.4.8-dev/code 15**, package **dev.killermelon1458.pumping.debug**, label **Pumping Dev**, min/target/compile **26/36/37**, unchanged standard debug certificate (`67d8d07c36e1f9d040ed59d66be1c6f38d4102f1ae16743cc0f4c18675410be4`). APK: `dev-builds/1.4.8-dev/Pumping-1.4.8-dev.apk`; SHA-256 **`cfa330b306af080403043c1d1f5f6a247ea99a50855b8b2b5d0b07b841969b5a`**. Prior 1.4.7 and public beta APKs remain unchanged.
- [x] Actual staged-APK Stats inspection passed: two parents (not five phases) give 180 mL total / 90 mL mean and median / 3 mL per active minute, formatted in oz; only one of two is elapsed-eligible. No-context matches show N/A and Reset restores both. Every stored database table was unchanged after viewing/filtering. Retained screenshots were visually inspected; these are emulator observations, not physical acceptance.
- [x] Owner confirmed physical-device acceptance of 1.4.8-dev and authorized the 1.4.8-beta promotion. Production-phone upgrade testing is not claimed; signed upgrade/fresh-install coverage is disposable API 36.1, with API 26/35 Robolectric migration tests.

Source is prepared for staging/review only. No commit/tag/push/publication or production signing. Stage **4B remains unimplemented and unauthorized**: confirmed lossy legacy-export preview/field-loss reporting and final export integration require a separate task after physical-device acceptance.


## 1.4.8-beta promotion and release verification

The owner explicitly authorized committing/tagging the accepted Stage 4A checkpoint and publishing a production-signed prerelease. The exact reviewed 19-file tree was committed as `70e373f7027cd97843475554f9b1904a4aa2c306` and annotated `v1.4.8-dev`. Signing was completed privately by the owner; no key was generated or credentials requested. The APK was built from that clean application commit, version 1.4.8-beta/code 15, production ID `dev.killermelon1458.pumping`, label Pumping, non-debuggable, min/target/compile 26/36/37.

APK SHA-256: `9c9501f72a2abe714a0228d98fd8416e54b68ad2df3ec8499649cbe44ed27027`. The certificate `017ec08d4f6be27763340de84449be449f3dcd3b1082d4c52498935fc3ae77a4` matches the original APK freshly downloaded from GitHub. The release verifier passed identity, SDK, signature, manifest and absence-of-test-fixtures/components checks. No application content was changed or rebuilt for documentation preparation; final release provenance records the documentation-only tag commit and proves identical application/build inputs while retaining the actual APK build commit.

Complete rerun: 310 debug + 310 release unit tests, 78 Compose workflows, 13 tooling tests passed; debug/release lint each 0 errors / 20 existing warnings. On the unrooted production emulator image, signed migration integrity was checked through strict Room-backed startup and complete native v1/v4 exports, not private database extraction. Source versions/migration chain are 3→4→5→6 and API26/35 migration tests pass. Four original native/iOS parents, notes/left-right/IDs/timestamps, three bags, six transactions, three deletion markers, raw compatibility metadata, balances and supported preferences survived the in-place production-signed update from downloaded 1.4.0-beta.1. Repeated native v1 and iOS imports did not duplicate or resurrect records; restart preserved data.

The actual signed APK also passed independent time-wheel/clean-Cancel, immediate 40-minute timing, basic and detailed power routines, explicit breastfeeding, profile/archive associations, linked new inventory, paused timer recovery and all-time parent-session statistics checks. A separate empty signed installation restored and re-exported all v4 record metadata and supported preference values, then survived restart. Restoring preferences intentionally regenerates only the internal acknowledgement token; user-record IDs and values remain unchanged.

Live signed reminder checks passed: app backgrounded/killed with display locked, one notification with actual elapsed text, Snooze without opening the app, actual five-minute redelivery, Dismiss retaining a future alarm and no record/inventory mutation. Runtime notification and exact-alarm access were granted for this disposable-emulator check. Samsung sound/vibration/heads-up, battery restrictions and real-phone upgrades are not claimed from emulator testing.

Stage **4B is intentionally not part of this beta**. Unsupported metadata/timing/inventory still blocks legacy export; native JSON v4 remains the complete-fidelity saved-record option. The earlier combined Stage 4 release checklist does not authorize or require implementing 4B for this owner-authorized partial-stage release. No further development begins after publication.


## 1.4.9-dev / code 16 — History, Stash and editor-layout UX

Owner-authorized usability milestone above published 1.4.8-beta; not Stage 4B. Work proceeds through three internal checkpoints with one final versioned APK.

### Decisions

- History hides only UNKNOWN breastfeeding text. YES/NO labels and independent baby/power/stash indicators retain their meaning; no record/category/filter/backup reinterpretation.
- New Bag and Use Milk reuse Quick Log's independent date calendar and configurable time picker. Snapshot baselines wait for asynchronous number/quantity initialization; input-unit conversion is compared by physical quantity, incomplete drafts remain protected, and Back dismisses a time picker first. Inventory, expiry and transaction semantics are unchanged.
- Independent keyed sections separate power, breastfeeding and pump selection instead of dragging combined cards. Each editor retains ownership of form values, timing authority, phases, allocations and errors.
- Pinned Edit Layout toolbar; handle-only long-press drag with shared-scroll auto-scroll, lift and placement animation; accessible Up/Down. Ordinary form interactions and focus-loss commits are disabled during rearrangement. Done persists; Cancel/Back restores saved layout without closing or mutating the record draft. Reset is pending until Done.
- Stable per-editor DataStore IDs/order/hidden sets; required sections cannot hide. Invalid encodings recover defaults; missing future sections append visibly. Layouts are **device-local**, excluded from unchanged native JSON v4. Room remains 6, original iOS import/export behavior unchanged.
- Hidden breastfeeding defaults to UNKNOWN for a fresh session. Existing meaningful breastfeeding, pump, power/interval, notes and stash information overrides hidden preferences for that edit. Hiding unsaved values never erases them.

### Internal verification

- [x] Phase 1: 310 debug unit tests and 8 focused API 36.1 emulator tests passed. Initial test-selector ambiguities and synthetic usage-before-storage fixture were corrected; production inventory validation remained intact.
- [x] Phase 2: independent-section definition test and 31 emulator duration/power/stash regression tests passed before customization began.
- [x] Phase 3 and picker amendment: full unit/UI/tooling/lint, synthetic 1.4.8→1.4.9 in-place upgrade, APK metadata/signature/checksum verification completed; results below.
- [x] Owner physically accepted 1.4.9 functionality; remaining visual/organizational corrections are addressed in the separately versioned 1.4.10 milestone below.

No intermediate development artifact is distributed. Retain older immutable APKs. Prepare source/tests/docs for review only; do not commit, tag, push or publish. Stage 4B remains deferred and no new public beta is authorized.


### Owner amendment — Global configurable time picker (same 1.4.9 milestone)

No 1.4.9 APK had been finalized/distributed; retain code 16 and build the complete scope once. This supersedes a wheels-only default without removing the accepted wheel implementation.

- Global DataStore preference defaults to **Clock face** when absent/invalid. **Scroll wheels** remains selectable from a menu beside Edit Layout, outside section-reordering mode. The choice also appears in Next Pump. It is independent of per-editor layouts, record dirty state and 12/24-hour format.
- Native clock mode is explicitly themed to request the dial rather than OEM-default spinners. Wheels retain their existing gestures, AM/PM rules and responsive fallback. Both share a saved temporary hour/minute draft; Done commits once through existing `editorTime`, Cancel/Back commits nothing. Date selection is independent and uses unchanged `editorDate`/DST/precision rules.
- Suppress focus-loss commits while the customization menu is open, so choosing presentation cannot finalize a pending power-phase draft. Layout reset/cancel and backup imports leave the global picker preference alone.
- Quick Log/Edit Pump, New Bag, Use Milk, Next Pump and Quick Log reminder controls use the preference. Reminder timestamps now use independent date/time controls; no scheduling, interval, timestamp, volume or inventory calculation change.
- Like layout preferences, this presentation setting remains device-local and outside native v4. Room remains 6; original iOS format/export behavior is unchanged and Stage 4B remains deferred.
- Pre-amendment verification (327 debug + 327 release unit, 102 UI, 13 tooling, lint 0 errors / 20 existing warnings) is historical only. The amended source needs complete rerun plus actual 1.4.8-dev upgrade and final identity/signature/SHA-256 checks. Results belong in the final build report, not inferred from the earlier internal candidate.

### Final 1.4.9 verification and delivery

- **337/337 debug and 337/337 release unit tests passed**, including API 26/35 persistence/preference coverage. **117/117 API 36.1 emulator/Compose tests passed** on the final source, with zero failures/errors/skips; 39 additional UI cases cover stash parity, layouts/drag/autoscroll, both picker styles and their shared draft. **13/13 Python tooling tests passed**. Debug/release lint each report **0 errors / 20 existing warnings**.
- An existing pump-selector timing test was synchronized with the Room-backed profile flow and uses the actual accessibility click action to avoid an IME-resize touch-target race. Separate profile workflows retain touch selection coverage. The final complete suite passed after this test-only adjustment; no application behavior was weakened to accommodate the test.
- Actual standard-debug-signed **1.4.8-dev/code 15 → 1.4.9-dev/code 16** in-place upgrade passed on a disposable read-only API 36.1 emulator, using `adb install -r` without clearing/uninstalling during the upgrade. All tables stayed identical: 7 sessions, 4 bags, 7 movements, 3 deletion markers, 1 pump and 5 phases, plus raw iOS metadata, notes, split quantities, links and balances. Native v4 export comparisons, repeated native/iOS merge imports, clean stash cancellation and cold restart passed. New layout and global-picker preferences survived restart. Only synthetic data was used.
- Both presentations were checked at normal size and 1.6 font scale, including landscape. The native landscape clock uses its stock compact layout (without an extra visible title) so Done/Cancel fit; an accessibility pane title retains timestamp context. Physical Samsung ergonomics/keyboard/theme/drag acceptance remains pending.
- Version-derived staging produced `dev-builds/1.4.9-dev/Pumping-1.4.9-dev.apk`, SHA-256 **`17bba77f81a16248660899aed71e2f1659b5b7a7a0c42a60c447377a1c226e0a`**, identical to the upgrade-tested candidate. Verified embedded 1.4.9-dev/code 16, `dev.killermelon1458.pumping.debug`, Pumping Dev, API 26/36/37, debuggable/not test-only, valid unchanged debug certificate. Local `BUILD_INFO.md` and verification evidence accompany it.
- Room 6, native JSON v4, legacy format/export behavior and production signing/identity remain unchanged. Layouts and picker style are intentionally device-local and excluded from backups. Historical Unknown values are unchanged. Previous development and published beta APK hashes are unchanged. No owner/production data was touched; emulator checks are not physical-device acceptance.

All three phases and the picker amendment are ready for owner review. Relevant source/tests/docs are staged only; no commit/tag/push/publication. **Stage 4B remains deferred.**

## 1.4.10-dev / code 17 — Editor organization correction

Owner physically accepted all 1.4.9 functionality. Preserve its APK/checksum and cumulative staged source. This revision changes presentation only: move the exact compact customization buttons to the scrollable footer in all three editors; retain sticky Save/Cancel; consolidate section controls as checkbox → name → Up → Down → rightmost handle. Required checkbox is disabled/checked, long names ellipsize, 48 dp action targets and accessibility labels remain. Keep layout-mode Reset/Done/Cancel, drag/autoscroll/animations, form state, DataStore encodings, Room 6, native v4 and all calculations unchanged. Stage 4B stays deferred.

Verification and artifact results will be recorded after the focused and regression suites complete. No commit/tag/push/publication; stop for owner physical review.

### 1.4.10 verification and delivery

- **337 debug + 337 release unit tests**, **13 tooling tests** passed; debug/release lint each **0 errors / 20 existing warnings**.
- **122 distinct emulator/Compose cases have passing results**: final full sweep 121/122, then all 10 picker workflows passed after correcting a test helper that tried to scroll the unchanged sticky reminder control. No APK source change for that correction. Earlier test waits/selectors were updated for async completion, unselected bags and footer scrolling. Local reports retain actual invocation counts; a single full 122/122 pass is not claimed.
- New tests verify all three real-editor footers, ancestry outside sticky header, side-by-side placement after fields, unchanged clean cancellation, horizontal five-control order/rightmost handle, required visibility lock and 1.8 font scale. Existing drag/autoscroll, saved-layout/recreation, hidden-data and form-draft checks passed.
- Actual debug-signed **1.4.9/code16 → 1.4.10/code17** update passed on disposable API36.1 without uninstall/data clear during upgrade. All table values preserved: 7 sessions, 4 bags, 7 movements, 3 deletion markers, 1 profile, 5 phases. Saved custom visibility and global picker style, reminders, native v4 exports, repeated native/iOS merge imports and cold restart preserved. Only synthetic data; no production phone touched.
- Version-derived APK `dev-builds/1.4.10-dev/Pumping-1.4.10-dev.apk`, SHA-256 **`008af286e60c66382ac638a13bc94d34eff66f0890ab8779f310fed66ca70af6`**. Identity/code/SDK/debug signature verified; same bytes as upgrade-tested candidate. Accepted 1.4.9 APK/hash and published beta remain unchanged. Local build report and screenshots accompany artifact.
- Screenshots visually confirm compact footer and one-row controls. Owner physical acceptance of 1.4.10 is pending. Room 6/native v4 unchanged; no Stage4B, commit, tag, push or publication. Cumulative 33-file checkpoint is prepared for review only.

## Owner-authorized 1.4.10-beta promotion

The owner physically accepted 1.4.10-dev/code 17, including both editor organization corrections and the cumulative 1.4.9 features. Phase A prepares/commits this complete checkpoint and release documentation, runs targeted unsigned preflight only, and stops for owner-local signing. Reuse documented 337 debug + 337 release unit, 122 distinct UI, 13 tooling and lint results; no full campaign is repeated. Phase B must verify the permanent certificate and production identity, signed in-place 1.4.8-beta upgrade with synthetic data, restart/preferences/relationships and focused fresh-install editor/picker checks before tag/push/prerelease publication. Stage 4B and 1.5.0 remain deferred. Physical development acceptance does not imply a production-phone upgrade test.
