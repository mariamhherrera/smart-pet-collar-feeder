/*
  COL-10.1 Detect sleep, COL-14.1 Count wake-ups

  The night is 10 PM to 7 AM. Every minute the collar looks at its 15 activity
  windows (4 s each, from activity.h):
    - mostly resting  -> a resting minute
    - otherwise       -> a moving minute

  Sleep:    resting for 10 minutes or more in a row. Shorter rests while awake do not count.
  Wake-up:  moving for 3 minutes or more in a row after falling asleep.
            1 or 2 minutes of moving (a twitch, changing position) is ignored
            and does not end the sleep.

  Plain C++ (no Arduino code), so it can also be tested on a computer.
*/

#pragma once

#include <stdint.h>

const int NIGHT_START_HOUR = 22;          // 10 PM
const int NIGHT_END_HOUR = 7;             // 7 AM
const int FALL_ASLEEP_MINUTES = 10;       // resting this long in a row = asleep
const int WAKE_UP_MINUTES = 3;            // moving this long in a row = a wake-up
const int WINDOWS_PER_MINUTE = 60 / 4;    // 15 activity windows of 4 s

bool isNightHour(int hour) {
  return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
}

// A minute is resting when at least half of its windows were resting
bool isRestingMinute(int restingWindows, int windows) {
  return restingWindows * 2 >= windows;
}

// Plain struct (only numbers), so the collar can save it in memory as it is
struct SleepCounter {
  uint16_t sleepMinutes = 0;   // minutes asleep this night
  uint16_t wakeUps = 0;        // wake-ups this night
  uint16_t restRun = 0;        // resting minutes in a row
  uint16_t movingRun = 0;      // moving minutes in a row
  bool asleep = false;

  // One finished minute of the night. Returns true when this minute is a new wake-up.
  bool addMinute(bool resting) {
    if (resting) {
      movingRun = 0;
      restRun++;

      if (asleep) {
        sleepMinutes++;
      } else if (restRun >= FALL_ASLEEP_MINUTES) {
        asleep = true;
        sleepMinutes += restRun;          // the minutes it took to fall asleep count too
      }
      return false;
    }

    // Moving minute
    movingRun++;

    if (!asleep) {
      restRun = 0;                       // still awake: start counting the rest again
      return false;
    }

    if (movingRun >= WAKE_UP_MINUTES) {  // a real wake-up, not a twitch
      wakeUps++;
      asleep = false;
      restRun = 0;
      return true;
    }
    return false;                        // a twitch: still asleep
  }

  void reset() {
    *this = SleepCounter();
  }
};
