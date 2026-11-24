package com.eveningoutpost.dexdrip.insulin.reminder;

import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.insulin.Insulin;
import com.eveningoutpost.dexdrip.insulin.InsulinManager;
import com.eveningoutpost.dexdrip.insulin.MultipleInsulins;
import com.eveningoutpost.dexdrip.models.InsulinInjection;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Reminder;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.xdrip;

import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Logs the insulin dose attached to a {@link Reminder} as a treatment.
 * <p>
 * Each reminder occurrence (identified by its next_due time) maps to a deterministic treatment
 * uuid, so the same occurrence can never be logged twice, whichever path (notification button,
 * swipe in the reminders list, automatic logging) gets there first.
 */
public class ReminderDoseLogger {

    private static final String TAG = "ReminderDose";
    public static final double MAX_UNITS = 100;

    public enum Result {
        LOGGED,
        ALREADY_LOGGED,
        NO_DOSE,
        MULTIPLE_INSULINS_DISABLED,
        UNKNOWN_INSULIN,
        INVALID_UNITS,
        FAILED;

        public boolean isSuccess() {
            return this == LOGGED || this == ALREADY_LOGGED;
        }
    }

    private ReminderDoseLogger() {
    }

    /** Treatment uuid for a given reminder occurrence. */
    public static String uuidFor(final long reminderId, final long dueTime) {
        return UUID.nameUUIDFromBytes(("xdrip-reminder-dose:" + reminderId + ":" + dueTime)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String uuidFor(final Reminder reminder) {
        return uuidFor(reminder.getId(), reminder.next_due);
    }

    /** True when the reminder has a dose which has not yet been logged for its current occurrence. */
    public static boolean isDosePending(final Reminder reminder) {
        return reminder != null && reminder.hasDose()
                && !reminder.isDoseLoggedForCurrentOccurrence()
                && Treatments.byuuid(uuidFor(reminder)) == null;
    }

    /** Checks whether a dose could be logged right now, without logging it. */
    public static Result check(final Reminder reminder, final double units) {
        if (reminder == null || !reminder.hasDose()) return Result.NO_DOSE;
        if (!(units > 0) || units > MAX_UNITS) return Result.INVALID_UNITS;
        // without multiple insulin types xDrip models every dose with the bolus curve,
        // so a long acting dose would massively distort insulin on board and predictions
        if (!MultipleInsulins.isEnabled()) return Result.MULTIPLE_INSULINS_DISABLED;
        if (InsulinManager.getProfile(reminder.dose_insulin) == null) return Result.UNKNOWN_INSULIN;
        return null;
    }

    public static Result logDose(final Reminder reminder, final long timestamp) {
        return logDose(reminder, timestamp, reminder != null ? reminder.dose_units : 0);
    }

    /**
     * Log the dose for the reminder's current occurrence.
     *
     * @param units amount to log, normally {@code reminder.dose_units} but may be adjusted for this occurrence only
     */
    public static Result logDose(final Reminder reminder, final long timestamp, final double units) {
        return logDoseForOccurrence(reminder, reminder != null ? reminder.next_due : 0, timestamp, units);
    }

    /**
     * Log the dose for a specific occurrence (identified by its due time). A notification button
     * may be tapped after the reminder has already moved on, e.g. a second tap or a copy of the
     * notification on a watch; it must log the occurrence it was shown for, never the next one.
     */
    public static synchronized Result logDoseForOccurrence(final Reminder reminder, final long dueTime, final long timestamp, final double units) {
        final Result problem = check(reminder, units);
        if (problem != null) {
            UserError.Log.e(TAG, "Not logging dose for reminder: " + problem);
            return problem;
        }
        final boolean current = dueTime == reminder.next_due;
        final String uuid = uuidFor(reminder.getId(), dueTime);
        if ((current && reminder.isDoseLoggedForCurrentOccurrence()) || Treatments.byuuid(uuid) != null) {
            if (current) markLogged(reminder);
            return Result.ALREADY_LOGGED;
        }

        final Insulin insulin = InsulinManager.getProfile(reminder.dose_insulin);
        final List<InsulinInjection> injections = new ArrayList<>();
        injections.add(new InsulinInjection(insulin, units));
        final Treatments treatment = Treatments.create(0, units, injections, timestamp, uuid);
        if (treatment == null) {
            UserError.Log.e(TAG, "Treatment creation failed for reminder dose");
            return Result.FAILED;
        }
        if (current) markLogged(reminder);
        UserError.Log.uel(TAG, "Logged " + formatUnits(units) + "U " + insulin.getName()
                + " from reminder: " + reminder.getTitle());
        return Result.LOGGED;
    }

    private static void markLogged(final Reminder reminder) {
        if (reminder.dose_logged_for != reminder.next_due) {
            reminder.dose_logged_for = reminder.next_due;
            reminder.save();
        }
    }

    /**
     * Undo an automatically logged dose: remove the treatment and put the reminder back to the
     * occurrence it was logged for, snoozed, so it reminds again and can be confirmed manually.
     * Automatic logging is suppressed for that occurrence (dose_logged_for = -dueTime) so it
     * does not simply log itself again.
     */
    public static synchronized boolean undo(final Reminder reminder, final long dueTime, final long snoozeMs) {
        if (reminder == null) return false;
        final String uuid = uuidFor(reminder.getId(), dueTime);
        final boolean existed = Treatments.byuuid(uuid) != null;
        Treatments.delete_by_uuid(uuid, true);
        reminder.next_due = dueTime;
        reminder.dose_logged_for = -dueTime;
        reminder.snoozed_till = JoH.tsl() + snoozeMs;
        reminder.enabled = true;
        reminder.save();
        UserError.Log.uel(TAG, "Undo reminder dose for: " + reminder.getTitle() + " existed: " + existed);
        return existed;
    }

    /** True when the dose should be logged automatically for the current occurrence. */
    public static boolean shouldAutoLog(final Reminder reminder) {
        return reminder != null && reminder.isAutoDose()
                && reminder.dose_logged_for != -reminder.next_due
                && isDosePending(reminder);
    }

    /**
     * Completes the current occurrence after the dose was handled: repeating reminders move to
     * their next due time, one-off reminders are disabled.
     */
    public static void completeOccurrence(final Reminder reminder) {
        if (reminder.repeating) {
            reminder.schedule_next();
        } else {
            reminder.enabled = false;
            reminder.save();
        }
    }

    public static String formatUnits(final double units) {
        return new DecimalFormat("0.##").format(units);
    }

    /** e.g. "14 U Lantus" */
    public static String describe(final Reminder reminder) {
        return describe(reminder.dose_units, reminder.dose_insulin);
    }

    public static String describe(final double units, final String insulin) {
        return xdrip.gs(R.string.reminder_dose_description, formatUnits(units), insulin);
    }

    public static String message(final Result result, final Reminder reminder) {
        switch (result) {
            case LOGGED:
                return xdrip.gs(R.string.reminder_dose_logged, describe(reminder));
            case ALREADY_LOGGED:
                return xdrip.gs(R.string.reminder_dose_already_logged);
            case MULTIPLE_INSULINS_DISABLED:
                return xdrip.gs(R.string.reminder_dose_needs_multiple_insulins);
            case UNKNOWN_INSULIN:
                return xdrip.gs(R.string.reminder_dose_unknown_insulin, reminder.dose_insulin);
            case INVALID_UNITS:
                return xdrip.gs(R.string.reminder_dose_invalid_units);
            case NO_DOSE:
                return xdrip.gs(R.string.reminder_dose_none);
            default:
                return xdrip.gs(R.string.reminder_dose_failed);
        }
    }

    /**
     * Notification "Taken" button: log the occurrence the notification was shown for at the time
     * of the tap, and complete that occurrence if the reminder is still on it.
     */
    public static Result handleTaken(final long reminderId, final long dueTime) {
        final Reminder reminder = Reminder.byid(reminderId);
        if (reminder == null) return Result.FAILED;
        final Result result = logDoseForOccurrence(reminder, dueTime, JoH.tsl(), reminder.dose_units);
        if (result.isSuccess() && reminder.next_due == dueTime) {
            completeOccurrence(reminder);
        }
        JoH.static_toast_long(message(result, reminder));
        return result;
    }
}
