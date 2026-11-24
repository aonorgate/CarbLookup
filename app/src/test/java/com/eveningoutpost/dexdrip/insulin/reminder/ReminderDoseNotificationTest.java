package com.eveningoutpost.dexdrip.insulin.reminder;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.DAY_IN_MS;
import static com.eveningoutpost.dexdrip.utilitymodels.Constants.MINUTE_IN_MS;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.Reminders;
import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.insulin.InsulinManager;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Reminder;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.receiver.ReminderReceiver;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.eveningoutpost.dexdrip.xdrip;

import org.junit.Before;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End to end tests for reminder doses: the real reminder notification is posted and its
 * buttons are "tapped" by delivering their intents to {@link ReminderReceiver}.
 */
public class ReminderDoseNotificationTest extends RobolectricTestWithConfig {

    private static final String LONG_ACTING = "Lantus";

    @Before
    public void setUpDose() {
        Treatments.delete_all();
        InsulinManager.getDefaultInstance();
        Pref.setBoolean("multiple_insulin_types", true);
        ((NotificationManager) xdrip.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
    }

    private static ShadowNotificationManager notifications() {
        return shadowOf((NotificationManager) xdrip.getAppContext().getSystemService(Context.NOTIFICATION_SERVICE));
    }

    private static Reminder dueReminder(final double units, final int mode) {
        final Reminder reminder = Reminder.create("Basal insulin", DAY_IN_MS);
        reminder.next_due = JoH.tsl() - MINUTE_IN_MS;
        reminder.dose_units = units;
        reminder.dose_insulin = LONG_ACTING;
        reminder.dose_mode = mode;
        reminder.save();
        return reminder;
    }

    /** Fires the reminder and returns the posted notification. */
    private static Notification alert(final Reminder reminder) {
        Reminders.doAlert(reminder);
        ShadowLooper.idleMainLooper(11, TimeUnit.SECONDS); // doAlert posts after 10 seconds
        return notifications().getNotification(Reminders.NOTIFICATION_ID);
    }

    private static List<String> buttons(final Notification notification) {
        final List<String> titles = new ArrayList<>();
        if (notification.actions != null) {
            for (final Notification.Action action : notification.actions) {
                titles.add(action.title.toString());
            }
        }
        return titles;
    }

    private static String text(final Notification notification) {
        return String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    /** Delivers the button's broadcast to the receiver, as Android would on a tap. */
    private static void tap(final Notification notification, final String titlePrefix) {
        for (final Notification.Action action : notification.actions) {
            if (action.title.toString().startsWith(titlePrefix)) {
                final Intent intent = shadowOf(action.actionIntent).getSavedIntent();
                new ReminderReceiver().onReceive(xdrip.getAppContext(), intent);
                ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS); // cancelAlert is posted
                return;
            }
        }
        throw new AssertionError("No button starting with " + titlePrefix + " in " + buttons(notification));
    }

    private static List<Treatments> allTreatments() {
        return new Select().from(Treatments.class).execute();
    }

    @Test
    public void plainReminder_hasNoButtons() {
        final Reminder reminder = Reminder.create("Change sensor", DAY_IN_MS);
        reminder.next_due = JoH.tsl() - MINUTE_IN_MS;
        reminder.save();

        final Notification notification = alert(reminder);

        assertThat(notification).isNotNull();
        assertThat(buttons(notification)).isEmpty();
    }

    @Test
    public void confirmMode_offersTakenAndSnooze_withoutLogging() {
        final Notification notification = alert(dueReminder(14, Reminder.DOSE_MODE_CONFIRM));

        assertThat(buttons(notification)).containsExactly("Taken: 14 U Lantus", "Snooze").inOrder();
        assertThat(allTreatments()).isEmpty();
    }

    @Test
    public void tappingTaken_logsDose_movesToTomorrow_andDismisses() {
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long due = reminder.next_due;
        final Notification notification = alert(reminder);

        tap(notification, "Taken");

        final Treatments treatment = Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder.getId(), due));
        assertThat(treatment).isNotNull();
        assertThat(treatment.insulin).isEqualTo(14.0);
        assertThat(treatment.isBasalOnly()).isTrue();
        assertThat(Reminder.byid(reminder.getId()).next_due).isGreaterThan(JoH.tsl());
        assertThat(notifications().getNotification(Reminders.NOTIFICATION_ID)).isNull();
    }

    @Test
    public void tappingTakenTwice_logsOnce() {
        final Notification notification = alert(dueReminder(14, Reminder.DOSE_MODE_CONFIRM));

        tap(notification, "Taken");
        tap(notification, "Taken"); // stale notification, e.g. shown on a watch

        assertThat(allTreatments()).hasSize(1);
    }

    @Test
    public void tappingTaken_whenLoggingRefused_keepsReminderDue() {
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final long due = reminder.next_due;
        final Notification notification = alert(reminder);
        Pref.setBoolean("multiple_insulin_types", false);

        tap(notification, "Taken");

        assertThat(allTreatments()).isEmpty();
        assertThat(Reminder.byid(reminder.getId()).next_due).isEqualTo(due);
        assertThat(notifications().getNotification(Reminders.NOTIFICATION_ID)).isNotNull();
    }

    @Test
    public void tappingSnooze_snoozesWithoutLogging() {
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_CONFIRM);
        final Notification notification = alert(reminder);

        tap(notification, "Snooze");

        assertThat(allTreatments()).isEmpty();
        assertThat(Reminder.byid(reminder.getId()).isSnoozed()).isTrue();
        assertThat(notifications().getNotification(Reminders.NOTIFICATION_ID)).isNull();
    }

    @Test
    public void autoMode_logsWhenFired_offersUndo_andMovesToTomorrow() {
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;

        final Notification notification = alert(reminder);

        assertThat(buttons(notification)).containsExactly("Undo");
        assertThat(text(notification)).contains("Logged 14 U Lantus automatically");
        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder.getId(), due))).isNotNull();
        assertThat(Reminder.byid(reminder.getId()).next_due).isGreaterThan(JoH.tsl());
    }

    @Test
    public void autoMode_undo_removesDose_andNextAlertAsksInsteadOfLogging() {
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;
        final Notification notification = alert(reminder);

        tap(notification, "Undo");

        assertThat(allTreatments()).isEmpty();
        final Reminder undone = Reminder.byid(reminder.getId());
        assertThat(undone.next_due).isEqualTo(due);
        assertThat(undone.isSnoozed()).isTrue();

        // when the snooze ends it alerts again, asking rather than logging by itself
        undone.snoozed_till = 0;
        undone.save();
        final Notification again = alert(undone);

        assertThat(buttons(again)).containsExactly("Taken: 14 U Lantus", "Snooze").inOrder();
        assertThat(allTreatments()).isEmpty();
    }

    @Test
    public void autoMode_withoutMultipleInsulins_explainsAndOffersTaken() {
        Pref.setBoolean("multiple_insulin_types", false);
        final Reminder reminder = dueReminder(14, Reminder.DOSE_MODE_AUTO);
        final long due = reminder.next_due;

        final Notification notification = alert(reminder);

        assertThat(text(notification)).contains("Could not log 14 U Lantus automatically");
        assertThat(buttons(notification)).containsExactly("Taken: 14 U Lantus", "Snooze").inOrder();
        assertThat(allTreatments()).isEmpty();
        assertThat(Reminder.byid(reminder.getId()).next_due).isEqualTo(due);
    }
}
