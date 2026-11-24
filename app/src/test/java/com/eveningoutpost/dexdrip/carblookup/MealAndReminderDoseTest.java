package com.eveningoutpost.dexdrip.carblookup;

import static com.eveningoutpost.dexdrip.utilitymodels.Constants.DAY_IN_MS;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.activeandroid.query.Select;
import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.carblookup.db.MealRepository;
import com.eveningoutpost.dexdrip.carblookup.model.MealItem;
import com.eveningoutpost.dexdrip.insulin.InsulinManager;
import com.eveningoutpost.dexdrip.insulin.reminder.ReminderDoseLogger;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Reminder;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * CarbLookup meals and reminder insulin doses both write treatments; they must stay separate
 * records, and deleting a meal must never remove a dose logged at the same moment.
 */
public class MealAndReminderDoseTest extends RobolectricTestWithConfig {

    @Before
    public void setUpDose() {
        Treatments.delete_all();
        InsulinManager.getDefaultInstance();
        Pref.setBoolean("multiple_insulin_types", true);
    }

    private static Reminder dueDoseReminder(final long due) {
        final Reminder reminder = Reminder.create("Basal insulin", DAY_IN_MS);
        reminder.next_due = due;
        reminder.dose_units = 14;
        reminder.dose_insulin = "Lantus";
        reminder.save();
        return reminder;
    }

    private static List<Treatments> allTreatments() {
        return new Select().from(Treatments.class).execute();
    }

    private static MealSummary logMeal(final long timestamp) {
        final MealLogService service = new MealLogService(mock(MealRepository.class));
        final List<MealItem> items = new ArrayList<>();
        return service.logMeal("Dinner", 45, items, null, "dinner", timestamp);
    }

    @Test
    public void mealAndDoseAtSameMoment_areSeparateTreatments() {
        final long now = JoH.tsl();
        final Reminder reminder = dueDoseReminder(now - 60_000);

        final MealSummary meal = logMeal(now);
        assertThat(ReminderDoseLogger.logDose(reminder, now)).isEqualTo(ReminderDoseLogger.Result.LOGGED);

        assertThat(allTreatments()).hasSize(2);
        final Treatments mealTreatment = Treatments.byuuid(meal.treatmentUuid);
        final Treatments dose = Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder));
        assertThat(mealTreatment.carbs).isEqualTo(45.0);
        assertThat(mealTreatment.insulin).isEqualTo(0.0);
        assertThat(dose.carbs).isEqualTo(0.0);
        assertThat(dose.insulin).isEqualTo(14.0);
        assertThat(dose.isBasalOnly()).isTrue();
    }

    @Test
    public void deletingMeal_keepsDoseLoggedAtSameMoment() {
        final long now = JoH.tsl();
        final Reminder reminder = dueDoseReminder(now - 60_000);
        final MealSummary meal = logMeal(now);
        ReminderDoseLogger.logDose(reminder, now);

        // what MealHistoryActivity / MealDetailActivity do when a meal is deleted
        Treatments.delete_by_uuid(meal.treatmentUuid, true);

        assertThat(Treatments.byuuid(meal.treatmentUuid)).isNull();
        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder))).isNotNull();
    }

    @Test
    public void undoingDose_keepsMealLoggedAtSameMoment() {
        final long now = JoH.tsl();
        final Reminder reminder = dueDoseReminder(now - 60_000);
        final long due = reminder.next_due;
        final MealSummary meal = logMeal(now);
        ReminderDoseLogger.logDose(reminder, now);

        ReminderDoseLogger.undo(reminder, due, 30 * 60_000);

        assertThat(Treatments.byuuid(ReminderDoseLogger.uuidFor(reminder.getId(), due))).isNull();
        assertThat(Treatments.byuuid(meal.treatmentUuid)).isNotNull();
    }
}
