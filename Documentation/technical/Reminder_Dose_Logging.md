# Reminder dose logging

A reminder can carry an insulin dose, for example a daily long acting dose at the same time
every day. When the reminder goes off, the dose can be logged as a treatment with one tap.

## Setting it up

1. Turn on **Settings → Multiple insulin types**. Without it xDrip models every dose with the
   rapid acting curve, so a long acting dose would badly inflate insulin on board and predictions.
   Reminder doses are refused until it is on.
2. Open **Reminders**, add or edit a reminder, tick **Log insulin dose**, then enter the units and
   choose the insulin type. The picker defaults to the selected basal profile, or the first long
   acting profile.
3. Optionally tick **Log automatically when due**.

The reminder list shows the dose next to the title, e.g. `Basal insulin (14 U Lantus)`.

## Logging the dose

| Where | What happens |
|-------|--------------|
| Notification **Taken: 14 U Lantus** button | Logs the dose at the time of the tap, dismisses the notification and moves the reminder to its next occurrence. |
| Notification **Snooze** button | Same as snoozing the reminder; nothing is logged. |
| Swipe right in the reminders list | Asks **Log and done** (amount can be changed for this occurrence only), **Done only** or **Cancel**. |
| Automatic mode | The dose is logged when the reminder fires; the notification sounds once and offers **Undo**. |

The treatment time is when the dose is confirmed (or when the reminder fired, in automatic mode),
not the scheduled time.

**Undo** removes the logged treatment, puts the reminder back to that occurrence and snoozes it
for 30 minutes. Automatic logging is switched off for that occurrence so it does not log itself
again; it can still be confirmed with **Taken**.

If logging fails (for example Multiple insulin types is off, or the insulin type was removed) the
reason is shown and the reminder stays due.

## Safety

* Each occurrence is logged at most once. The treatment uuid is derived from the reminder id and
  its due time, so the notification button, the swipe and automatic mode cannot double log.
* Doses must be above 0 and at most 100 units.
* Automatic mode logs a dose that may not have been taken. Confirm mode is the default and the
  safer choice.

## Implementation

| File | Role |
|------|------|
| `models/Reminder.java` | `dose_units`, `dose_insulin`, `dose_mode`, `dose_logged_for` columns (added by schema patch) |
| `insulin/reminder/ReminderDoseLogger.java` | Validation, logging, de-duplication, undo |
| `Reminders.java` | Notification buttons, automatic logging in `doAlert`, swipe dialog, edit dialog section |
| `receiver/ReminderReceiver.java` | Handles the Taken and Undo buttons |
| `models/JoH.java` | `showNotification` overload that accepts notification actions |

`dose_logged_for` holds the `next_due` of the occurrence that was logged. After an undo it holds
`-next_due`, which marks automatic logging as suppressed for that occurrence.

Tests: `ReminderDoseLoggerTest`

```bash
./gradlew :app:testFastDebugUnitTest --tests 'com.eveningoutpost.dexdrip.insulin.reminder.*'
```
