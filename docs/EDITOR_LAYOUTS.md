# Editor layouts (1.4.10-dev)

Quick Log/Edit Pump, New Bag and Use Milk each have an independent, device-local layout. Use **Edit Layout** at the bottom of the editor’s scrollable content. Ordinary controls are disabled while arranging sections. Hold the 48 dp handle to drag; Up/Down provide an alternative without gestures. The same scroll container auto-scrolls near its edges. Other sections animate into place; the dragged section is lifted. Normal entry has no handles or placement animation.

**Done** saves order and optional-section visibility. **Cancel** (or Back when no time picker is open) abandons layout changes and keeps the record draft open. Reset to Default is a draft change until Done. Layout changes never save a pumping session, add/use milk, or enter the record's discard comparison.

| Editor | Required sections | Optional sections |
| --- | --- | --- |
| Quick Log / Edit Pump | Timing; Milk output | Pump used; Power Pump; Breastfeeding; Stash It; Next Pump; Notes |
| New Bag | Bag number; Volume; Storage date/time; Storage location/expiry | Notes |
| Use Milk | Bag selection; Quantity; Usage reason; Transaction date/time | Notes |

The default order follows the previous general presentation, splitting the previously combined power/breastfeeding/pump card into three independent sections. Each section has a stable internal ID; labels and positions are not persistence keys. The editor owns all values, text drafts, timing authority and validation. Keyed sections only present that state. Switching layout modes suppresses focus-loss commits, including pending detailed-interval drafts.

Hiding a section does not erase its data. A new session starts with Unknown breastfeeding context, displayed as Not specified when its section is visible. Existing sessions with recorded Yes/No, a pump, Power Pump/intervals, notes or stash associations expose those sections even if hidden in the saved layout. A small “Recorded information” label explains that override. An unsaved value hidden in the current draft remains intact and can be shown again through Edit Layout. Saving still preserves that value.

Layouts use separate DataStore keys (`editor-layout-pump`, `editor-layout-bag`, `editor-layout-usage`) with a versioned stable-ID order and hidden-ID set. Invalid/unsupported encodings fall back to defaults. Normalization removes unknown/duplicate IDs, enforces required visibility and appends missing sections visibly, so future additions remain discoverable. Saved layouts survive restarts and normal upgrades; unfinished layout drafts survive activity/process saved-state recreation. Reordering does not rewrite pumping or inventory records.

These UI preferences are intentionally **not in native JSON v4 or original iOS backups**. Moving to another installation or restoring a backup retains that installation's layout defaults/preferences. Room stays at schema 6 and native backups at v4. Stage 4B legacy-export changes are not included.

All editable timestamps share Quick Log’s date-only calendar and a global choice of **Clock face** (the default) or **Scroll wheels**, including 12/24-hour display, minute precision and AM/PM rules. Done makes one timestamp change; Cancel/Back abandons the temporary picker value. Date/time replacements preserve the other component and existing precision using the shared local-time/DST policy. Expiry and inventory timestamps retain their previous calculation/storage rules.

New Bag and Use Milk compare normalized semantic snapshots on both Cancel and Back. Number/quantity initialization completes before capturing the baseline; automatic units/defaults are not independent edits. Valid equivalent quantities compare equal, while blank/incomplete/invalid text is retained as draft work. Reverting every editable value clears the warning. Back dismisses an open time picker before attempting to close the form.

History omits breastfeeding text for Unknown only; Yes keeps its baby icon and label, No keeps its negative label. Classification, statistics predicates, backup values, and independent power/stash icons are unchanged.

## Global time-picker presentation

The **Time picker: Clock face / Scroll wheels** menu sits alongside Edit Layout in the editor’s scrollable footer, outside section-reordering mode. The same menu is available in Next Pump. It is one application-wide, immediately persisted DataStore preference (`timePickerStyle`), independent of each editor’s layout and the 12/24-hour format. Reset/Cancel Layout do not reset this preference. Missing or unrecognized values use Clock face.

Clock face uses a themed native clock-dial dialog explicitly configured in clock mode, with Done and Cancel. Landscape uses the framework’s compact arrangement without an extra visible title to keep both actions fully visible at large font scales; an accessibility pane title retains the Start/End context. Scroll wheels retain the existing compact inline native wheel interface and its small-screen/large-font modal fallback. Both presentations use one saved temporary hour/minute draft and one shared `editorTime` conversion path. Done applies exactly one timestamp edit; intermediate clock/wheel changes and Cancel/Back do not change the record. Date-only confirmation never opens a time picker. Clock-dial and wheel drafts survive ordinary saved-state recreation; switching presentation preserves an open picker’s temporary selection.

Opening the style menu suppresses focus-loss commits of pending editor inputs, including power-pump phase drafts. Choosing a style does not modify timestamps, timing authority, duration, quantities, notes, record dirty state, layout, or reminders. Quick Log, Edit Pump, New Bag, Use Milk, Next Pump and Quick Log’s reminder timestamp all use the choice. Reminder date and time now have the same independent controls; reminder calculations and scheduling semantics are unchanged.

Like section layouts, picker presentation is **device-local**, excluded from unchanged native JSON v4 and original iOS backups. Importing a backup with supported preferences does not override it. Physical Samsung review should check the clock theme and both presentations with the user’s display/font settings; emulator observations do not constitute physical-device acceptance.

## Compact organization (1.4.10)

The existing Time picker and Edit Layout text buttons retain their size, theme, spacing and callbacks. They now follow ordinary fields inside the one scroll container, outside keyed/reorderable sections. Save/Cancel stay sticky. In layout mode, Reset/Cancel/Done remain in the layout toolbar. Each section uses a single row: checkbox, ellipsized section name, accessible Up, accessible Down, unchanged drag handle at the far right. Required sections show a checked disabled checkbox. The interactive targets remain 48 dp; larger fonts truncate the name rather than wrapping controls or removing accessibility labels. Drag detection, auto-scroll/animations, stable IDs and stored preferences are unchanged.
