# Pump statistics

Stats → Pump uses saved parent pumping sessions. Expand **Filters** to combine a date range, one or more pumps, breastfeeding context and pumping mode. Conditions combine with **AND**. Reset restores **Last 30 days / All pumps / All contexts / All modes**. Filters stay on the page and survive activity recreation; they are temporary UI state, not backup preferences.

- Date ranges: last 7 calendar days, last 30 calendar days, all time, or inclusive custom dates. Membership uses the session's **start**, in the device's current local time zone, from local midnight through midnight after the last selected date. Milk is counted once on the start date even if the session crosses midnight. All time includes intentional future records.
- Previous/Next moves by an equal-length calendar-day period; previous-period comparisons retain the same non-date filters. Custom date controls show the window being viewed. Invalid date order shows an error, not all-time results.
- Pump selection supports multiple profiles and explicit **Unspecified**. No selected profiles means **All pumps**. Archived profiles remain available. Identity is the stable profile ID; current saved nicknames/archive labels are used after a rename.
- Breastfeeding: All, Yes, No, Not specified. Mode: All, Normal, Power Pump, Not specified. Historical unknown values are never inferred from notes or brand.

## Measurements

Volumes remain fixed-point microliters in storage. Means, medians and rates use decimal arithmetic, then the same US-fluid-ounce/mL conversion as other app screens and the saved **Display Units** preference. Viewing statistics never rewrites records.

| Measurement | Definition |
| --- | --- |
| Sessions | Number of matching distinct parent session IDs |
| Total expressed milk | Sum of parent volumes |
| Mean volume | Total volume / session count |
| Median volume | Middle sorted volume; mean of the two middle volumes for even samples |
| Active pumping time | Sum of recorded activeSeconds |
| Mean active duration | Total active time / session count |
| Milk per pumping minute | Total expressed milk / total active pumping minutes |
| Left/right | Recorded left and right totals, with split-session count; unclassified milk is not assigned a side |
| Known elapsed time | Sum of actual elapsed milliseconds for eligible sessions |
| Milk per known elapsed minute | Milk from eligible sessions / elapsed minutes of those same eligible sessions |

The pumping rate is a **ratio of sums**, not an unweighted mean of per-session rates. Elapsed eligibility uses existing timing validation and elapsedKnown: basic power pumps with unrecorded rests are excluded, including manual power records lacking complete phases. Valid complete power routines and recorded paused timers can contribute. The elapsed sample count is displayed. Invalid elapsed timing is excluded without changing stored records.

Empty means, medians and undefined rates display **N/A**. A recorded zero-volume session contributes a real zero, including its recorded pumping time. Totals and counts can legitimately be zero.

## Comparisons and existing analytics

Compare by Pump or Breastfeeding context. Every group uses the same active filters. Breastfeeding groups retain Yes, No and Not specified, even if a category has zero matches. Milk belongs to the parent; phases never multiply milk or session count. Stash additions/withdrawals never become expressed milk.

Groups with 1–4 sessions carry a small-sample caution. Results describe **expressed milk**, not infant intake or inherent pump effectiveness. Feeding conditions, time of day and other differences can still affect comparisons.

Daily output, discoveries and previous-period Pump totals follow the Pump filters. All-time/ranges longer than 62 days use chronological monthly chart totals. Journey streak/countdown/goal remain journey-wide. Stash keeps its separate Week/calendar Month/All dates, ledger analytics and current inventory; Pump filters do not affect them. Feeding savings remains an independent all-time inventory-use estimate.

Room schema 6, native JSON v4 and supported iOS imports are unchanged. Stage 4B legacy-export improvements are separate and are not implemented in this development milestone.
