package com.example.pettracker.feeder;

import android.content.Context;
import android.content.SharedPreferences;

public class ScheduleStorage {

    /**
     * FD-1.2: Saves the feeding schedule on the phone, so it is still
     * there after the app is closed.
     */
        private static final String FILE_NAME = "feeding_schedule";
        private static final String KEY_HOUR = "hour";
        private static final String KEY_MINUTE = "minute";
        private static final String KEY_PORTION = "portion";

        private final SharedPreferences prefs;

        public ScheduleStorage(Context context) {
            prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        }

        /** Saves the feeding time and portion (1 = small, 2 = medium, 3 = large). */
        public void save(int hour, int minute, int portion) {
            prefs.edit()
                    .putInt(KEY_HOUR, hour)
                    .putInt(KEY_MINUTE, minute)
                    .putInt(KEY_PORTION, portion)
                    .apply();
        }

        /** True if a schedule was saved before. */
        public boolean hasSchedule() {
            return prefs.contains(KEY_HOUR);
        }

        public int getHour()    { return prefs.getInt(KEY_HOUR, 7); }
        public int getMinute()  { return prefs.getInt(KEY_MINUTE, 0); }
        public int getPortion() { return prefs.getInt(KEY_PORTION, 2); }
    }



