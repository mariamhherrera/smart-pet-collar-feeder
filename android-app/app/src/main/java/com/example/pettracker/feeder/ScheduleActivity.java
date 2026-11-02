package com.example.pettracker.feeder;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.app.Activity;
import android.app.AlertDialog;

import com.example.pettracker.R;

/**FD-1.1: Screen where the owner picks the feeding time and portion.
 */
public class ScheduleActivity extends Activity {

    private TimePicker timePicker;
    private RadioGroup portionGroup;
    private TextView currentScheduleText;
    private ScheduleStorage storage;
    private RadioGroup feedNowPortionGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feeder_schedule);

        // Connect the code to the views in the layout
        timePicker = findViewById(R.id.timePicker);
        portionGroup = findViewById(R.id.portionGroup);
        currentScheduleText = findViewById(R.id.currentScheduleText);
        Button saveButton = findViewById(R.id.saveButton);
     // Back arrow: closes this screen and returns to Home
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        timePicker.setIs24HourView(true);
        storage = new ScheduleStorage(this);

        loadSavedSchedule();
        saveButton.setOnClickListener(v -> onSaveClicked());
        // FD-2.1: Feed now
        feedNowPortionGroup = findViewById(R.id.feedNowPortionGroup);
        Button feedNowButton = findViewById(R.id.feedNowButton);
        feedNowButton.setOnClickListener(v -> onFeedNowClicked());
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
        Toast.makeText(this, "Schedule saved", Toast.LENGTH_SHORT).show();


        // FD-1.3 (Isabel): send the schedule to the feeder here, e.g.
        // "SCHEDULE," + formatTime(hour, minute) + "," + portion
    }

    private void showCurrentSchedule(int hour, int minute, int portion) {
        currentScheduleText.setText("Saved: " + formatTime(hour, minute)
                + ", " + portionName(portion) + " portion");
    }

    /**Time written as 07:30 instead of 7:30.
     */
    private String formatTime(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
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
    private void onFeedNowClicked() {
        int portion = getFeedNowPortion();
        new AlertDialog.Builder(this)
                .setTitle("Feed now?")
                .setMessage("This gives your pet one " + portionName(portion).toLowerCase() + " portion right away.")
                .setPositiveButton("Feed", (dialog, which) -> {
                    Toast.makeText(this, "Feeding " + portionName(portion).toLowerCase() + " portion", Toast.LENGTH_SHORT).show();
                    // FD-2.2: send the command to the feeder here, e.g. "FEED," + portion
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

