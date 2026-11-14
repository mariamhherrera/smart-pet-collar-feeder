package com.example.pettracker.feeder;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.app.Activity;
import android.app.AlertDialog;

import com.example.pettracker.BottomNav;
import com.example.pettracker.MealLimit;
import com.example.pettracker.SettingsStorage;
import com.example.pettracker.R;
import com.example.pettracker.PetCloudRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.example.pettracker.bluetooth.FeederConnection;
import com.example.pettracker.bluetooth.FeederMessage;
import com.example.pettracker.bluetooth.FeederReply;
import org.jetbrains.annotations.NotNull;
import java.util.Locale;
import kotlin.Unit;

/**FD-1.1: Screen where the owner picks the feeding time and portion.
 */
public class ScheduleActivity extends Activity {

    private TimePicker timePicker;
    private RadioGroup portionGroup;
    private TextView currentScheduleText;
    private ScheduleStorage storage;
    private RadioGroup feedNowPortionGroup;
    private FeederConnection feeder;   // FD-1.3: Bluetooth connection to the feeder
    private TextView feederStatusText;
    // UI-8.3: meals the feeder says it gave today ("OK FED 2" = 2), -1 = not known yet
    private int feederMealsToday = -1;
    private boolean bluetoothScheduleBackupRequired = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feeder_schedule);

        // Connect the code to the views in the layout
        timePicker = findViewById(R.id.timePicker);
        portionGroup = findViewById(R.id.portionGroup);
        currentScheduleText = findViewById(R.id.currentScheduleText);
        Button saveButton = findViewById(R.id.saveButton);
        // Bottom bar: this is the Feeder tab
        BottomNav.setUp(this, BottomNav.FEEDER);
        feederStatusText = findViewById(R.id.textFeederStatus);
        timePicker.setIs24HourView(true);
        storage = new ScheduleStorage(this);

        loadSavedSchedule();
        saveButton.setOnClickListener(v -> onSaveClicked());
        // FD-2.1: Feed now
        feedNowPortionGroup = findViewById(R.id.feedNowPortionGroup);
        Button feedNowButton = findViewById(R.id.feedNowButton);
        feedNowButton.setOnClickListener(v -> onFeedNowClicked());

        // FD-1.3: connection to the feeder, used by Save and Feed now
        feeder = new FeederConnection(this, new FeederConnection.Listener() {
            @Override
            public void onConnectionChanged(boolean connected) {
                showFeederStatus(connected);
                if (connected) {
                    // UI-8.3: the feeder also needs the meal limit for scheduled meals
                    feeder.send(FeederMessage.limitCommand(new SettingsStorage(ScheduleActivity.this).getMealLimit()));
                    if (bluetoothScheduleBackupRequired) sendTimeAndSchedule();
                }
            }

            @Override
            public void onReply(@NotNull FeederReply reply) {
                if (reply instanceof FeederReply.TimeSet) return; // nothing to tell the owner
                if (reply instanceof FeederReply.LimitSet) return;
                if (reply instanceof FeederReply.Fed) feederMealsToday = ((FeederReply.Fed) reply).getFeedsToday();
                if (reply instanceof FeederReply.ScheduledFed) feederMealsToday = ((FeederReply.ScheduledFed) reply).getFeedsToday();
                Toast.makeText(ScheduleActivity.this, FeederMessage.screenText(reply),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSendFailed() {
                Toast.makeText(ScheduleActivity.this, "Could not reach the feeder",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        feeder.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        feeder.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        feeder.close();
    }

    /**FD-1.3: Gives the feeder the current time and the saved schedule.
     * Called every time the feeder connects, so they are right even after it restarted.
     */
    private void sendTimeAndSchedule() {
        feeder.send(FeederMessage.timeCommandNow());
        // UI-8.3: the meal limit from Settings, so the feeder also skips scheduled meals past it
        feeder.send(FeederMessage.limitCommand(new SettingsStorage(this).getMealLimit()));
        if (storage.hasSchedule()) {
            feeder.send(FeederMessage.scheduleCommand(
                    storage.getHour(), storage.getMinute(), storage.getPortion()));
        }
    }

    /** Green "Feeder connected" or red "Feeder not connected" at the top of the screen. */
    private void showFeederStatus(boolean connected) {
        // Commands go through Firebase; Bluetooth is only the backup
        feederStatusText.setText(connected ? "Bluetooth backup connected" : "Using Firebase (Bluetooth backup off)");
        feederStatusText.setTextColor(connected ? 0xFF15803D : 0xFF475569);
    }

    /**FD-1.2: If a schedule was saved before, show it on the screen.
     */
    private void loadSavedSchedule() {
        if (!storage.hasSchedule()) {
            return;
        }
        timePicker.setHour(storage.getHour());
        timePicker.setMinute(storage.getMinute());
        setSelectedPortion(storage.getPortion());
        showCurrentSchedule(storage.getHour(), storage.getMinute(), storage.getPortion());
    }

    /**
     Reads the choice, saves it and confirms it.
     */
    private void onSaveClicked() {
        int hour = timePicker.getHour();
        int minute = timePicker.getMinute();
        int portion = getSelectedPortion();

        storage.save(hour, minute, portion);
        showCurrentSchedule(hour, minute, portion);

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            bluetoothScheduleBackupRequired = true;
            if (feeder.isConnected()) {
                sendTimeAndSchedule();
                Toast.makeText(this, "Firebase sign-in unavailable; schedule sent by Bluetooth backup", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Schedule saved locally, but Firebase is not signed in", Toast.LENGTH_LONG).show();
            }
            return;
        }

        new PetCloudRepository(uid).saveSchedule(hour, minute, portion, (id, error) -> {
            runOnUiThread(() -> {
                if (error == null) {
                    bluetoothScheduleBackupRequired = false;
                    Toast.makeText(this, "Schedule saved to Firebase", Toast.LENGTH_SHORT).show();
                } else {
                    bluetoothScheduleBackupRequired = true;
                    if (feeder.isConnected()) {
                        sendTimeAndSchedule();
                        Toast.makeText(this, "Firebase unavailable; schedule sent by Bluetooth backup", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Schedule saved locally; Firebase error: " + error, Toast.LENGTH_LONG).show();
                    }
                }
            });
            return Unit.INSTANCE;
        });
    }

    private void showCurrentSchedule(int hour, int minute, int portion) {
        currentScheduleText.setText(String.format(Locale.getDefault(), "Saved: %s, %s portion", formatTime(hour, minute), portionName(portion)));
    }

    /**Time written as 07:30 instead of 7:30.
     */
    private String formatTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    /**Turns the selected button into 1 (small), 2 (medium) or 3 (large).
     */
    private int getSelectedPortion() {
        int checkedId = portionGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.portionSmall) return 1;
        if (checkedId == R.id.portionLarge) return 3;
        return 2;
    }

    /**Selects the button that matches a saved portion.
     */
    private void setSelectedPortion(int portion) {
        if (portion == 1) portionGroup.check(R.id.portionSmall);
        else if (portion == 3) portionGroup.check(R.id.portionLarge);
        else portionGroup.check(R.id.portionMedium);
    }

    private String portionName(int portion) {
        if (portion == 1) return "small";
        if (portion == 3) return "large";
        return "medium";
    }

    // FD-2.1: ask first, so the pet doesn't get an extra meal by accident
    /**
     * UI-8.3: check the daily meal limit from Settings before Feed now.
     * Counts today's meals from Firebase. Without Firebase, uses what the feeder said over Bluetooth.
     */
    private void onFeedNowClicked() {
        int limit = new SettingsStorage(this).getMealLimit();
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            checkLimitThenConfirm(Math.max(feederMealsToday, 0), limit);
            return;
        }
        long now = System.currentTimeMillis();
        new PetCloudRepository(uid).loadFeedingsBetween(MealLimit.startOfDay(now), now,
                rows -> {
                    int meals = MealLimit.mealsToday(rows, now);
                    runOnUiThread(() -> checkLimitThenConfirm(meals, limit));
                    return Unit.INSTANCE;
                },
                error -> {
                    // Could not count today's meals: fall back to what the feeder told us
                    runOnUiThread(() -> checkLimitThenConfirm(Math.max(feederMealsToday, 0), limit));
                    return Unit.INSTANCE;
                });
    }

    private void checkLimitThenConfirm(int mealsToday, int limit) {
        if (!MealLimit.canFeed(mealsToday, limit)) {
            Toast.makeText(this, "Daily limit reached (" + mealsToday + " of " + limit
                    + " meals). You can change it in Settings.", Toast.LENGTH_LONG).show();
            return;
        }
        int portion = getFeedNowPortion();
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Feed now?")
                .setMessage("This gives your pet one " + portionName(portion).toLowerCase(Locale.getDefault()) + " portion right away.")
                .setPositiveButton("Feed", (dialog, which) -> {
                    String uid = FirebaseAuth.getInstance().getUid();
                    if (uid != null) {
                        new PetCloudRepository(uid).createFeedNow(portion, (commandId, error) -> {
                            runOnUiThread(() -> {
                                if (error != null) {
                                    if (feeder.isConnected()) {
                                        feeder.send(FeederMessage.feedCommand(portion));
                                        Toast.makeText(this, "Firebase unavailable; Feed Now sent by Bluetooth backup", Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(this, "Firebase feeding record failed: " + error, Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    Toast.makeText(this, "Feed Now command saved to Firebase", Toast.LENGTH_SHORT).show();
                                }
                            });
                            return Unit.INSTANCE;
                        });
                    } else {
                        if (feeder.isConnected()) {
                            feeder.send(FeederMessage.feedCommand(portion));
                        } else {
                            Toast.makeText(this, "Firebase is not signed in and feeder is not connected", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // 1 = Small, 2 = Medium, 3 = Large (same numbers as the schedule)
    private int getFeedNowPortion() {
        int id = feedNowPortionGroup.getCheckedRadioButtonId();
        if (id == R.id.feedNowSmall) return 1;
        if (id == R.id.feedNowLarge) return 3;
        return 2;
    }
}
