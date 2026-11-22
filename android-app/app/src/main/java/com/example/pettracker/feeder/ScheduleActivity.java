package com.example.pettracker.feeder;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;

import com.example.pettracker.FeederLink;
import com.example.pettracker.BottomNav;
import com.example.pettracker.FeederStatus;
import com.example.pettracker.FoodLevel;
import com.example.pettracker.FoodLevelStorage;
import com.example.pettracker.MealLimit;
import com.example.pettracker.SettingsStorage;
import com.example.pettracker.R;
import com.example.pettracker.PetCloudRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.example.pettracker.bluetooth.FeederConnection;
import com.example.pettracker.bluetooth.FeederMessage;
import com.example.pettracker.bluetooth.FeederReply;
import org.jetbrains.annotations.NotNull;
import java.util.Date;
import java.util.Locale;
import kotlin.Unit;
import android.view.View;

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
    private PetCloudRepository statusCloud;                                   // COM-3.3
    private com.google.firebase.database.ValueEventListener statusListener;  // COM-3.3
    private FeederLink feederLink;   // COM-7
    private String pairedFeederId;   // COM-7: null = not paired
    // The newest feeder/status from Firebase, and whether it has loaded yet
    private java.util.Map<String, ?> lastStatus;
    private boolean statusLoaded = false;
    // The feeder cannot write "offline" once unplugged, so the card is re-checked every 15 s
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            showFoodLevel();
            ticker.postDelayed(this, 15_000L);
        }
    };

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

        // FD-4.3: food level card, shows the last level until the feeder sends a new one
        showFoodLevel();
        findViewById(R.id.buttonCheckFood).setOnClickListener(v -> onCheckFoodClicked());

        // FD-6.2: every meal, newest first
        findViewById(R.id.buttonFeedingHistory).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, com.example.pettracker.FeedingHistoryActivity.class)));

        // FD-1.3: connection to the feeder, used by Save and Feed now
        feeder = new FeederConnection(this, new FeederConnection.Listener() {
            @Override
            public void onConnectionChanged(boolean connected) {
                showFeederStatus(connected);
                showFoodLevel();
                if (connected) {
                    // UI-8.3: the feeder also needs the meal limit for scheduled meals
                    feeder.send(FeederMessage.limitCommand(new SettingsStorage(ScheduleActivity.this).getMealLimit()));
                    if (bluetoothScheduleBackupRequired) sendTimeAndSchedule();
                    feeder.send(FeederMessage.LEVEL); // FD-4.3: fresh food level every time it connects
                }
            }

            @Override
            public void onReply(@NotNull FeederReply reply) {
                if (reply instanceof FeederReply.TimeSet) return; // nothing to tell the owner
                if (reply instanceof FeederReply.LimitSet) return;
                if (reply instanceof FeederReply.FoodLevel) {
                    onFoodLevel(((FeederReply.FoodLevel) reply).getPercent());
                    return; // the card shows it, no toast needed
                }
                if (reply instanceof FeederReply.NoFoodLevel) {
                    ((TextView) findViewById(R.id.textFoodLevelNote)).setText(
                            "The feeder can't measure the food. Check the IR sensor wires.");
                    return;
                }
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

        // COM-7: Pair feeder / Unpair button
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid != null) feederLink = new FeederLink(myUid);
        findViewById(R.id.buttonPairFeeder).setOnClickListener(v -> onPairButtonClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        feeder.start();
        showPairing();   // COM-7
        ticker.post(tick);
        // COM-3.3: once on Wi-Fi, the feeder writes its food level to Firebase every 30 s
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            statusCloud = new PetCloudRepository(uid);
            statusListener = statusCloud.listenToFeederStatus(status -> {
                // Only trust the numbers while the feeder is online: an unplugged feeder
                // leaves its last status in Firebase (for example 100%).
                boolean online = FeederStatus.isOnline(status, System.currentTimeMillis());
                Integer percent = online ? FoodLevel.fromStatus(status) : null;

                // FD-3: show the current bowl weight (hidden while the feeder is off)
                Number grams = online && status != null ? (Number) status.get("bowlGrams") : null;
                runOnUiThread(() -> {
                    lastStatus = status;
                    statusLoaded = true;
                    if (percent != null) onFoodLevel(percent); else showFoodLevel();
                    showBowlWeight(grams != null ? grams.intValue() : null);
                });

                return Unit.INSTANCE;
            }, message -> Unit.INSTANCE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        feeder.stop();
        ticker.removeCallbacks(tick);
        if (statusCloud != null && statusListener != null) statusCloud.removeFeederStatusListener(statusListener);
        statusCloud = null;
        statusListener = null;
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

    /** FD-4.3: "Check food level" asks the feeder over Bluetooth. */
    private void onCheckFoodClicked() {
        if (!feeder.isConnected()) {
            Toast.makeText(this, "On Wi-Fi the feeder sends its food level by itself every 30 seconds. This button is for the Bluetooth backup.", Toast.LENGTH_LONG).show();
            return;
        }
        ((TextView) findViewById(R.id.textFoodLevelNote)).setText("Checking…");
        feeder.send(FeederMessage.LEVEL);
    }

    /** FD-4.3: a new level from the feeder. Saves it, shows it, and warns once when it goes low. */
    private void onFoodLevel(int percent) {
        int lowPercent = new SettingsStorage(this).getLowFoodPercent();
        boolean alert = new FoodLevelStorage(this).save(percent, lowPercent, System.currentTimeMillis());
        if (alert) {
            String uid = FirebaseAuth.getInstance().getUid();
            if (uid != null) new PetCloudRepository(uid).addAlert("low_food", FoodLevel.alertText(percent));
            Toast.makeText(this, FoodLevel.alertText(percent), Toast.LENGTH_LONG).show();
        }
        showFoodLevel();
    }

    /** FD-4.3: Food left card: "63%", the bar, and when it was checked (red when low). */
    private void showFoodLevel() {
        FoodLevelStorage saved = new FoodLevelStorage(this);
        TextView text = findViewById(R.id.textFoodLevel);
        ProgressBar bar = findViewById(R.id.progressFoodLevel);
        TextView note = findViewById(R.id.textFoodLevelNote);
        Integer percent = saved.getPercent();
        // Feeder unplugged or not connected: say so instead of showing the old % as live
        if (!feederOnline()) {
            text.setText(statusLoaded ? "Off" : "--%");
            text.setTextColor(getColor(R.color.text_secondary));
            bar.setProgress(0);
            String last = saved.getCheckedAt() > 0
                    ? android.text.format.DateFormat.getTimeFormat(this).format(new Date(saved.getCheckedAt())) : null;
            note.setText(statusLoaded ? FoodLevel.offlineNote(percent, last) : "Checking the feeder…");
            note.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        if (percent == null) {
            text.setText("--%");
            bar.setProgress(0);
            note.setText("Not checked yet. Connect by Bluetooth and tap Check food level.");
            return;
        }
        int lowPercent = new SettingsStorage(this).getLowFoodPercent();
        boolean low = FoodLevel.isLow(percent, lowPercent);
        text.setText(percent + "%");
        text.setTextColor(low ? 0xFFDC2626 : getColor(R.color.text_primary));
        bar.setProgress(percent);
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date(saved.getCheckedAt()));
        note.setText(low ? "Low, refill the feeder · checked " + time : "Checked " + time);
        note.setTextColor(low ? 0xFFDC2626 : getColor(R.color.text_secondary));
    }
    /** Online over Wi-Fi (status written in the last 90 s) or connected by Bluetooth. */
    private boolean feederOnline() {
        boolean bluetooth = feeder != null && feeder.isConnected();
        return bluetooth || FeederStatus.isOnline(lastStatus, System.currentTimeMillis());
    }

    /** COM-7: shows "Paired with PF-..." or "Not paired", and the right button text. */
    private void showPairing() {
        if (feederLink == null) return;
        feederLink.load(id -> {
            pairedFeederId = id;
            TextView text = findViewById(R.id.textFeederPairing);
            Button button = findViewById(R.id.buttonPairFeeder);
            if (id != null) {
                text.setText("Paired with " + id);
                button.setText("Unpair");
            } else {
                text.setText("Not paired");
                button.setText("Pair feeder");
            }
            return Unit.INSTANCE;
        });
    }
    /** FD-3: shows the bowl weight in the Smart feeder card, or hides it if none yet. */
    private void showBowlWeight(Integer grams) {
        TextView view = findViewById(R.id.textBowlWeight);
        if (grams == null) {
            view.setVisibility(View.GONE);
        } else {
            view.setText("Bowl: " + grams + " g");
            view.setVisibility(View.VISIBLE);
        }
    }


    /** COM-7: Pair if not paired, Unpair if paired. */
    private void onPairButtonClicked() {
        if (feederLink == null) return;

        if (pairedFeederId != null) {
            showStyledDialog("Unpair feeder?",
                    "Feeder " + pairedFeederId + " will stop following your schedule and Feed now.",
                    null, "Unpair", input -> {
                        feederLink.unpair(pairedFeederId, error -> afterPairing(error));
                        return null;
                    });
            return;
        }

        showStyledDialog("Pair feeder", "Looking for your feeder...", "", "Pair", null);

        feederLink.findFeederThatIsOn(found -> {
            showStyledDialog("Pair feeder",
                    found != null
                            ? "Feeder found nearby. Tap Pair to link it to your account."
                            : "No feeder found. Make sure it's on and connected to Wi-Fi, or type its ID.",
                    found != null ? found : "",
                    "Pair",
                    input -> {
                        String id = feederLink.cleanId(input);
                        if (id == null) return "That's not a feeder ID. It looks like PF-C9EDE0.";
                        feederLink.pair(id, error -> afterPairing(error));
                        return null;
                    });
            return Unit.INSTANCE;
        });
    }

    private AlertDialog currentDialog;

    private void showStyledDialog(String titleText, String messageText, String inputPrefill,
                                  String confirmText, java.util.function.Function<String, String> onConfirm) {
        if (currentDialog != null && currentDialog.isShowing()) currentDialog.dismiss();

        View view = getLayoutInflater().inflate(R.layout.dialog_pair_feeder, null);
        TextView title = view.findViewById(R.id.dialogTitle);
        TextView message = view.findViewById(R.id.dialogMessage);
        EditText input = view.findViewById(R.id.dialogInput);
        TextView error = view.findViewById(R.id.dialogError);
        Button cancel = view.findViewById(R.id.dialogCancel);
        Button confirm = view.findViewById(R.id.dialogConfirm);

        title.setText(titleText);
        message.setText(messageText);
        confirm.setText(confirmText);

        if (inputPrefill == null) {
            input.setVisibility(View.GONE);
        } else {
            input.setText(inputPrefill);
        }

        if (onConfirm == null) {
            confirm.setEnabled(false);
            confirm.setAlpha(0.5f);
        }

        AlertDialog dialog = new AlertDialog.Builder(this).setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
        if (onConfirm != null) {
            confirm.setOnClickListener(v -> {
                String errMsg = onConfirm.apply(input.getText().toString());
                if (errMsg != null) {
                    error.setText(errMsg);
                    error.setVisibility(View.VISIBLE);
                } else {
                    dialog.dismiss();
                }
            });
        }
        currentDialog = dialog;
        dialog.show();
    }

    private Unit afterPairing(String error) {
        if (error != null) {
            if (currentDialog != null && currentDialog.isShowing()) {
                TextView err = currentDialog.findViewById(R.id.dialogError);
                if (err != null) {
                    err.setText(error);
                    err.setVisibility(View.VISIBLE);
                    return Unit.INSTANCE;
                }
            }
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        } else {
            if (currentDialog != null) currentDialog.dismiss();
            Toast.makeText(this, pairedFeederId == null ? "Feeder paired!" : "Feeder unpaired", Toast.LENGTH_SHORT).show();
        }
        showPairing();
        return Unit.INSTANCE;
    }
    private void showPairDialog(String foundId) {
        View view = getLayoutInflater().inflate(R.layout.dialog_pair_feeder, null);
        TextView title = view.findViewById(R.id.dialogTitle);
        TextView message = view.findViewById(R.id.dialogMessage);
        EditText input = view.findViewById(R.id.dialogInput);
        Button cancel = view.findViewById(R.id.dialogCancel);
        Button confirm = view.findViewById(R.id.dialogConfirm);

        title.setText("Pair feeder");
        message.setText(foundId != null
                ? "Feeder found nearby. Tap Pair to link it to your account."
                : "Type your feeder's ID from the Serial Monitor.");
        if (foundId != null) input.setText(foundId);
        confirm.setText("Pair");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            String id = feederLink.cleanId(input.getText().toString());
            if (id == null) {
                Toast.makeText(this, "Not a feeder ID. Looks like PF-C9EDE0.", Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            feederLink.pair(id, error -> afterPairing(error));
        });
        dialog.show();
    }

    private void showUnpairDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_pair_feeder, null);
        view.findViewById(R.id.dialogInput).setVisibility(View.GONE);
        TextView title = view.findViewById(R.id.dialogTitle);
        TextView message = view.findViewById(R.id.dialogMessage);
        Button cancel = view.findViewById(R.id.dialogCancel);
        Button confirm = view.findViewById(R.id.dialogConfirm);

        title.setText("Unpair feeder?");
        message.setText("Feeder " + pairedFeederId + " will stop following your schedule and Feed now.");
        confirm.setText("Unpair");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            feederLink.unpair(pairedFeederId, error -> afterPairing(error));
        });
        dialog.show();
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
