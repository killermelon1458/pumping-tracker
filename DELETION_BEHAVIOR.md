# Linked deletion and bag numbers

## User flow

Open a bag from Use soon, a transaction, or **Show all bag records**, then choose **Delete bag**. All bag records includes exhausted and historical bags. The confirmation identifies the bag's number, date, location, original quantity, remaining quantity, and every ledger row that will be removed.

If a source pump exists, **Also delete originating pump** is off by default. Turning it on lists the pump to be deleted and every sibling bag that will be kept with its source link removed. It never silently deletes sibling bags. Keeping the pump preserves its measured output and other bag links.

From the session editor, **Delete session** opens the same reviewed flow. It shows every linked bag and their combined original/remaining volume. **Also delete linked bags** is off by default. Keeping the bags removes only the pump and detaches their source links. Turning the option on deletes exactly the listed bags and their transactions with that pump.

Cancel and Android Back dismiss the confirmation without changes. **Delete permanently** commits the entire reviewed operation in one Room transaction. If records, links or balances changed after the preview, deletion is rejected; **Refresh details** loads a new review. Database errors roll back the operation, including its deletion markers.

## Accounting

Deletion is removal of records, not milk consumption. It does not create Feeding, Discard, Donation, Other, or adjustment transactions. Removing a bag removes all of its ADD, withdrawal and transfer rows; retained rows are unchanged. Inventory totals, movement period totals and feeding-based savings derive from the remaining records. Pump statistics retain a kept pump's measured output; deleting its session removes that output and recomputes history intervals/statistics.

## Number policy

Internal UUID/source IDs and `Bag.sourceSession` remain stable; display numbers are labels. Next number is the maximum retained bag/reserved source number plus one. Empty history starts at #1, deleting the highest number permits reuse, and deleting a middle number never renumbers survivors. Exhausted/discarded bags reserve their number until explicitly deleted. Retained uninterpreted legacy bag records also reserve their numbers; deleting a known imported bag removes its associated source reservation.

Deletion markers contain stable IDs only and do not reserve numbers. Reusing a label cannot attach a new bag to an old pump or resurrect the deleted bag.

## Backups and migration

Room schema 2 → 3 adds the `deletions` table. Native records and legacy rows for retained records stay intact; implicit pump deletions from the previous version become explicit ID markers, with their obsolete source payload removed. The 1 → 2 → 3 upgrade path is registered and tested without destructive fallback.

Deleting imported records removes their raw source payload as well as native rows. Kept pump allocations and kept bag projections are reconciled so neither native JSON nor legacy `.pumping` exports retain deleted records or dangling confirmed links. The original measured quantities, dates and notes of kept pumps remain unchanged.

Ordinary legacy Merge reports previously deleted IDs and skips them, including their old number reservations. This survives native backup/export/restore. Native Merge also skips locally deleted IDs; conflicting incoming deletion claims cannot delete existing records implicitly. A deliberate full legacy Replace, after its safety backup and explicit confirmation, clears markers and can restore the original file.

The pre-existing limits of undocumented legacy usage/multiple-bag serialization and unverified iPhone export acceptance remain described in `LEGACY_COMPATIBILITY.md`. No real backups are bundled or used as test fixtures.
