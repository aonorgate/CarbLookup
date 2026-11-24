package com.eveningoutpost.dexdrip.receiver;

import android.content.BroadcastReceiver;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Reminder;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.Reminders;
import com.eveningoutpost.dexdrip.insulin.reminder.ReminderDoseLogger;
import com.eveningoutpost.dexdrip.xdrip;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = ReminderReceiver.class.getSimpleName();

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            UserError.Log.e(TAG, "Null action intent received");
            return;
        }
        if (action.equals(Reminders.REMINDER_ACTION)) {
            final Bundle bundle = intent.getExtras();
            if (bundle != null && handleDoseAction(bundle)) return;
            if (bundle != null && bundle.getString(Reminders.EXTRA_FROM_ACTION) != null) {
                // notification buttons do not dismiss the notification by themselves
                Reminders.cancelAlert();
            }
            new Reminders().processIncomingBundle(bundle);
        }
    }

    private static boolean handleDoseAction(final Bundle bundle) {
        final long id = bundle.getLong("snooze_id");
        if (bundle.getString(Reminders.EXTRA_DOSE_TAKEN) != null) {
            UserError.Log.uel(TAG, "Reminder dose taken from notification, id: " + id);
            if (ReminderDoseLogger.handleTaken(id).isSuccess()) {
                Reminders.cancelAlert();
            }
            return true;
        }
        if (bundle.getString(Reminders.EXTRA_DOSE_UNDO) != null) {
            UserError.Log.uel(TAG, "Reminder dose undo from notification, id: " + id);
            Reminders.cancelAlert();
            ReminderDoseLogger.undo(Reminder.byid(id), bundle.getLong(Reminders.EXTRA_DOSE_DUE), Reminders.DOSE_UNDO_SNOOZE);
            JoH.static_toast_long(xdrip.gs(R.string.reminder_dose_undone, JoH.niceTimeScalar(Reminders.DOSE_UNDO_SNOOZE)));
            return true;
        }
        return false;
    }
}
