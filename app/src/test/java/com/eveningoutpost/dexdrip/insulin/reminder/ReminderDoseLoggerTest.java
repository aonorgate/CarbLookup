package com.eveningoutpost.dexdrip.insulin.reminder;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.DAY_IN_MS;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;
import static com.google.common.truth.Truth.assertThat;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.insulin.InsulinManager;
import com.eveningoutpost.dexdrip.insulin.reminder.ReminderDoseLogger.Result;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Reminder;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link ReminderDoseLogger} and the dose fields on {@link Reminder}.
 */
public class ReminderDoseLoggerTest extends RobolectricTestWithConfig {

    private static final String LONG_ACTING = "Lantus";

    @Before
    public void setUpDose() {
        Treatments.delete_all();
        InsulinManager.getDefaultInstance();
        Pref.setBoolean("multiple_insulin_types", true);
    }

    /** A daily reminder with a dose, due a minute ago. */
    private static Reminder dueDoseReminder(final double units, final int mode) {
        final Reminder reminder = Reminder.create("Basal insulin", DAY_IN_MS);
        reminder.next_due = JoH.tsl() - MINUTE_IN_MS;
        reminder.dose_units = units;
        reminder.dose_insulin = LONG_ACTING;
        reminder.dose_mode = mode;
        reminder.save();
        return reminder;
    }

    private static List<Treatments> allTreatments() {
        return new Select().from(Treatments.class).execute();
    }

    @Test
    public void doseFields_persist() {
        final Reminder reminder = dueDoseReminder(14.5, Reminder.DOSE_MODE_AUTO);

        final Reminder loaded = Reminder.byid(reminder.getId());

        assertThat(loaded.dose_units).isEqualTo(14.5);
        assertThat(loaded.dose_insulin).isEqualTo(LONG_ACTING);
        assertThat(loaded.dose_mode).isEqualTo(Reminder.DOSE_MODE_AUTO);
        assertThat(loaded.hasDose()).isTrue();
        assertThat(loaded.isAutoDose()).isTrue();
    }

    @Test
    public void plainReminder_hasNoDose() {
        final Reminder reminder = Reminder.create("Change sensor", DAY_IN_MS);

        assertThat(reminder.hasDose()).isFalse();
        assertThat(ReminderDoseLogger.isDosePending(reminder)).isFalse();
        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl())).isEqualTo(Result.NO_DOSE);
        assertThat(allTreatments()).isEmpty();
    }

    @Test
    public void logDose_createsLongActingTreatment() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long now = JoH.tsl();

        final Result result = ReminderDoseLogger.logDose(reminder, now);

        assertThat(result).isEqualTo(Result.LOGGED);
        final Treatments treatment = Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder));
        assertThat(treatment).isNotNull();
        assertThat(treatment.insulin).isEqualTo(14.0);
        assertThat(treatment.carbs).isEqualTo(0.0);
        assertThat(treatment.timestamp).isEqualTo(now);
        assertThat(treatment.getInsulinInjections()).hasSize(1);
        assertThat(treatment.getInsulinInjections().get(0).getInsulin()).isEqualTo(LONG_ACTING);
        assertThat(treatment.isBasalOnly()).isTrue();
        assertThat(reminder.isDoseLoggedForCurrentOccurrence()).isTrue();
        assertThat(ReminderDoseLogger.isDosePending(reminder)).isFalse();
    }

    @Test
    public void logDose_sameOccurrenceTwice_logsOnce() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl())).isEqualTo(Result.LOGGED);
        // e.g. notification button after the in-app swipe, using a stale copy of the reminder
        final Reminder stale = Reminder.byid(reminder.getId());
        stale.dose_logged_for = 0;
        assertThat(ReminderDoseLogger.logDose(stale, JoH.tsl() + 1000)).isEqualTo(Result.ALREADY_LOGGED);

        assertThat(allTreatments()).hasSize(1);
    }

    @Test
    public void logDose_adjustedUnits_logsAdjustedAmountOnly() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl(), 12)).isEqualTo(Result.LOGGED);

        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder)).insulin).isEqualTo(12.0);
        assertThat(Reminder.byid(reminder.getId()).dose_units).isEqualTo(14.0);
    }

    @Test
    public void logDose_refusesWithoutMultipleInsulins() {
        Pref.setBoolean("multiple_insulin_types", false);
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl())).isEqualTo(Result.MULTIPLE_INSULINS_DISABLED);
        assertThat(allTreatments()).isEmpty();
        assertThat(ReminderDoseLogger.isDosePending(reminder)).isTrue();
    }

    @Test
    public void logDose_refusesUnknownInsulin() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        reminder.dose_insulin = "NoSuchInsulin";

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl())).isEqualTo(Result.UNKNOWN_INSULIN);
        assertThat(allTreatments()).isEmpty();
    }

    @Test
    public void logDose_refusesInvalidUnits() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl(), -1)).isEqualTo(Result.INVALID_UNITS);
        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl(), ReminderDoseLogger.MAX_UNITS + 1)).isEqualTo(Result.INVALID_UNITS);
        assertThat(allTreatments()).isEmpty();
    }

    @Test
    public void handleTaken_repeating_movesToNextOccurrence() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long due = reminder.next_due;

        assertThat(ReminderDoseLogger.handleTaken(reminder.getId(), reminder.next_due)).isEqualTo(Result.LOGGED);

        final Reminder after = Reminder.byid(reminder.getId());
        assertThat(after.next_due).isGreaterThan(JoH.tsl());
        assertThat(after.enabled).isTrue();
        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder.getId(), due))).isNotNull();
        // tomorrow's occurrence is a new dose
        assertThat(ReminderDoseLogger.isDosePending(after)).isTrue();
    }

    @Test
    public void handleTaken_staleOccurrence_logsThatOccurrenceOnly() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long due = reminder.next_due;
        // swiped "Done only" in the app, so the reminder moved on without logging
        reminder.schedule_next();

        // then Taken is tapped on the notification that was still showing
        assertThat(ReminderDoseLogger.handleTaken(reminder.getId(), due)).isEqualTo(Result.LOGGED);
        assertThat(ReminderDoseLogger.handleTaken(reminder.getId(), due)).isEqualTo(Result.ALREADY_LOGGED);

        assertThat(allTreatments()).hasSize(1);
        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder.getId(), due))).isNotNull();
        final Reminder after = Reminder.byid(reminder.getId());
        assertThat(after.next_due).isEqualTo(reminder.next_due); // not moved on a second time
        assertThat(ReminderDoseLogger.isDosePending(after)).isTrue(); // tomorrow's dose is untouched
    }

    @Test
    public void handleTaken_oneOff_disablesReminder() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        reminder.repeating = false;
        reminder.save();

        assertThat(ReminderDoseLogger.handleTaken(reminder.getId(), reminder.next_due)).isEqualTo(Result.LOGGED);

        assertThat(Reminder.byid(reminder.getId()).enabled).isFalse();
    }

    @Test
    public void handleTaken_failure_keepsOccurrenceDue() {
        Pref.setBoolean("multiple_insulin_types", false);
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long due = reminder.next_due;

        assertThat(ReminderDoseLogger.handleTaken(reminder.getId(), reminder.next_due)).isEqualTo(Result.MULTIPLE_INSULINS_DISABLED);

        assertThat(Reminder.byid(reminder.getId()).next_due).isEqualTo(due);
    }

    @Test
    public void autoMode_logsOnce_thenNotifiedSchedulesNext() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;
        assertThat(ReminderDoseLogger.shouldAutoLog(reminder)).isTrue();

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl())).isEqualTo(Result.LOGGED);
        assertThat(ReminderDoseLogger.shouldAutoLog(reminder)).isFalse();
        reminder.notified();

        assertThat(reminder.next_due).isGreaterThan(due);
        assertThat(Reminder.byid(reminder.getId()).next_due).isGreaterThan(JoH.tsl());
    }

    @Test
    public void autoMode_notLogged_keepsAlerting() {
        Pref.setBoolean("multiple_insulin_types", false);
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;

        assertThat(ReminderDoseLogger.logDose(reminder, JoH.tsl()).isSuccess()).isFalse();
        reminder.notified();

        assertThat(reminder.next_due).isEqualTo(due);
    }

    @Test
    public void confirmMode_neverAutoLogs() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_CONFIRM);

        assertThat(ReminderDoseLogger.shouldAutoLog(reminder)).isFalse();
    }

    @Test
    public void undo_removesTreatment_snoozes_andStopsAutoLogging() {
        final Reminder reminder = dueDoseReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;
        ReminderDoseLogger.logDose(reminder, JoH.tsl());
        reminder.notified(); // auto mode moves on to tomorrow

        final boolean existed = ReminderDoseLogger.undo(Reminder.byid(reminder.getId()), due, 30 * MINUTE_IN_MS);

        assertThat(existed).isTrue();
        assertThat(allTreatments()).isEmpty();
        final Reminder after = Reminder.byid(reminder.getId());
        assertThat(after.next_due).isEqualTo(due);
        assertThat(after.isSnoozed()).isTrue();
        // it must ask again rather than silently log itself again
        assertThat(ReminderDoseLogger.isDosePending(after)).isTrue();
        assertThat(ReminderDoseLogger.shouldAutoLog(after)).isFalse();
        // and can still be confirmed manually
        assertThat(ReminderDoseLogger.logDose(after, JoH.tsl())).isEqualTo(Result.LOGGED);
    }

    @Test
    public void uuid_isStablePerOccurrence() {
        assertThat(ReminderDoseLogger.uuidFor(1, 1000)).isEqualTo(ReminderDoseLogger.uuidFor(1, 1000));
        assertThat(ReminderDoseLogger.uuidFor(1, 1000)).isNotEqualTo(ReminderDoseLogger.uuidFor(1, 2000));
        assertThat(ReminderDoseLogger.uuidFor(1, 1000)).isNotEqualTo(ReminderDoseLogger.uuidFor(2, 1000));
    }

    @Test
    public void formatUnits_dropsTrailingZeros() {
        assertThat(ReminderDoseLogger.formatUnits(14)).isEqualTo("14");
        assertThat(ReminderDoseLogger.formatUnits(14.5)).isEqualTo("14.5");
    }
}
