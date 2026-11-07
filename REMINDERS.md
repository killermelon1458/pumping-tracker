# Pumping reminders and interval reference

## Behavior

Profile → Reminders → **Measure time between pumps from** controls Start of last pump (default) or End of last pump. This preference is independent of history's interval-visibility switch. It controls Last Pump, history intervals (current start minus the selected reference of the previous session), automatic deadlines, session-editor reminder defaults and notification wording. Both saved timestamps remain intact.

The notification says **It's time to pump**, with delivery-time text such as **Last pump started 3h 20m ago** or **Last pump ended 3h ago**. Whole hours omit zero minutes; shorter intervals use minutes. With no completed pump it says **No previous pump recorded.** Invalid old timing has a safe unavailable-time message. Original iOS sessions missing `startTime` already derive start from end minus valid recorded duration during import; invalid timing rejects the import transaction with an error. The original `.pumping` format is unchanged.

Enable Pump reminders in reminder settings, or save a Next Pump time. Logging/editing/deleting the latest pump or changing the reference/interval recalculates an enabled reminder from the selected reference. Older history edits do not move it. Deleting the final pump cancels its automatic deadline while leaving reminders enabled, waiting for a new valid pump. Manual time adjustments and Skip remain available; a new latest pump or changed reference/interval supersedes the adjustment.

Snooze 5 min removes the current notification and saves a separate five-minute deadline without changing records, reference or the recurring reminder's next time. Dismiss (including swiping away) removes only the current notification. Neither action disables future reminders. Obsolete actions cannot affect a newer notification. Reminders repeat on their existing interval cadence; recovery skips deadlines more than one interval old rather than replaying historical alerts.

## Scheduling and persistence

The previous implementation used only `setAndAllowWhileIdle`, without exact-alarm special access, and canceled/re-armed even unchanged alarms on every restore. Opening the activity could consequently cause an overdue alarm to be re-armed for immediate delivery, masking background inexact-alarm delay. It also lacked durable delivery generations and used a DEFAULT-importance channel and the opaque launcher icon.

The scheduler now uses explicit immutable broadcast PendingIntents owned by AlarmManager, not activity timers or a foreground service. With exact access it calls `setExactAndAllowWhileIdle`; without access it uses `setAndAllowWhileIdle`. Each logical deadline retains an inexact safety alarm even when precise alarms are available, because Android cancels exact alarms when special access is revoked. Both paths carry the same durable generation and due time; only the first valid delivery may notify. One physical alarm per path is intentional, not duplicate notifications.

DataStore persists enabled state, `nextPump`, reference/interval basis, generation IDs, snooze deadline and the currently actionable notification ID. Receipt/actions are serialized with a Mutex; consumption is saved before posting. A process crash at that small boundary can miss that particular alert rather than post it twice; the next recurring deadline is already saved. No notification scheduler can guarantee delivery during force-stop, power-off or OS/vendor restrictions.

Boot, time/time-zone changes, application updates and exact-access grants restore schedules through a manifest receiver. Reboot recovery runs after the first unlock, when credential-protected Room/DataStore are available. Data edits while the UI process exists trigger reconciliation; notification delivery itself works without that process remaining alive. Returning from Android settings refreshes permissions and schedules. Cancel/replace removes both alarm paths. Existing timer/parts/expiry alerts retain their functions and gain the transparent small icon; duplicate timer/parts deliveries are suppressed.

Upgrade assumption: older builds saved only a `nextPump` timestamp, with no indication of whether it was an automatic or manually adjusted time. A positive existing deadline is preserved on initial upgrade and classified as an explicit chosen time, while the new reference defaults to Start. Subsequent session/reference/interval changes apply the selected reference. Reminders that were off remain off. No Room schema or record migration is needed.

## Alerts, permissions and channels

Fresh installations receive a dedicated HIGH-importance pumping channel with normal notification sound and vibration defaults. The app respects notification permission, blocked channels, silent mode, Do Not Disturb and OS heads-up rules. The notification stays in the shade until dismissed, opened or superseded; it has no timeout and is not an ongoing foreground-service notification.

Existing `pumping` channel settings are preserved exactly, including their use for timer/parts/stash alerts on upgraded installations. The app continues using that channel until the user explicitly confirms **Set up dedicated reminder alerts**. This creates the new channel and opens its settings; it never deletes, raises or overwrites the old channel. Existing users can instead edit their current channel through **Notification sound, vibration & pop-up settings**.

Android 13+ notification permission is requested through the existing activity permission flow. Android 12+ precise alarms use optional `SCHEDULE_EXACT_ALARM` special access, with a direct settings link and a visible approximate-timing explanation when unavailable. The app does not request restricted `USE_EXACT_ALARM` access or a permanent foreground service.

The notification small icon is an original white-alpha 24 dp milk-bottle vector with transparent background and cut-out measurement marks. The launcher icon is unchanged.

## Physical-device checks still required

Use a Samsung One UI device to verify the status-bar silhouette, collapsed and expanded notifications, sound/vibration, heads-up configuration, locked-screen actions and notification-category settings. Test with Samsung sleeping/deep-sleeping/background restrictions, silent/DND modes, exact access granted/revoked, and POST_NOTIFICATIONS allowed/denied. An AOSP emulator cannot establish Samsung-specific rendering or battery behavior. Inexact alarms can be substantially delayed; Android idle alarm quotas can also delay a five-minute snooze even with precise access. A user force-stop in Android Settings suppresses alarms until the app is opened again.

Android platform references: [alarm scheduling and restrictions](https://developer.android.com/develop/background-work/services/alarms), [exact access on Android 14+](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms), [notification channels and user settings](https://developer.android.com/develop/ui/compose/notifications/channels).
