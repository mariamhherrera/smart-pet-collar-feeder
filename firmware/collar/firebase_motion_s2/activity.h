/*
  COL-2: Activity classification (resting / walking / running)

  Same method and numbers as the simulation (SIM-4, simulation/method_steps.py):
    - Every step makes the collar bounce: a peak in the total acceleration.
    - Count the peaks in each 4-second window (200 readings at 50 per second).
    - Steps per second = peaks / 4.
    - Below 1.25 = resting, below 2.3 = walking, otherwise running.

  Plain C++ (no Arduino code), so it can also be tested on a computer.
*/

#pragma once

#include <math.h>
#include <stdint.h>

// ---------- Numbers from the simulation ----------

const int SAMPLE_RATE_HZ = 50;                   // readings per second
const int WINDOW_SECONDS = 4;
const int WINDOW_SAMPLES = WINDOW_SECONDS * SAMPLE_RATE_HZ;  // 200 readings
const float PEAK_HEIGHT_G = 0.10f;               // a bounce rises 0.1 g above gravity
const int MIN_GAP_SAMPLES = SAMPLE_RATE_HZ / 5;  // peaks closer than 0.2 s count as one

const float RESTING_BELOW = 1.25f;               // steps per second (SIM-4.6)
const float RUNNING_FROM = 2.30f;

// ---------- Activity ----------

enum Activity { RESTING = 0, WALKING = 1, RUNNING = 2 };

const char *activityName(Activity activity) {
  if (activity == WALKING) return "walking";
  if (activity == RUNNING) return "running";
  return "resting";
}

Activity activityFromPace(float stepsPerSecond) {
  if (stepsPerSecond < RESTING_BELOW) return RESTING;
  if (stepsPerSecond < RUNNING_FROM) return WALKING;
  return RUNNING;
}

// ---------- Step counter ----------

class StepCounter {
public:
  /*
    Give it one reading: the total acceleration in g
    (sqrt(ax*ax + ay*ay + az*az), about 1 g when still).
    Returns true every 200 readings, when a 4-second window is done.
    Then read pace() and activity().
  */
  bool add(float totalG) {
    // Gravity: a slow average of the total (about 4 seconds).
    // The simulation uses exactly 1 g, but a real sensor can read 1.03 g
    // when still, so the collar learns its own "still" value.
    if (count == 0) gravity = totalG;
    else gravity += (totalG - gravity) * 0.005f;

    // The middle reading (previous) is a peak if it is higher than both
    // neighbours, high enough above gravity, and far enough from the last peak.
    if (count >= 2) {
      long index = count - 1;
      bool isTop = previous > beforePrevious && previous >= totalG;
      bool isHigh = previous > gravity + PEAK_HEIGHT_G;
      bool isFar = index - lastPeak >= MIN_GAP_SAMPLES;

      if (isTop && isHigh && isFar) {
        peaksInWindow++;
        lastPeak = index;
      }
    }

    beforePrevious = previous;
    previous = totalG;
    count++;
    samplesInWindow++;

    if (samplesInWindow < WINDOW_SAMPLES) return false;

    // Window done: work out the pace and start a new window
    lastPace = (float)peaksInWindow / WINDOW_SECONDS;
    peaksInWindow = 0;
    samplesInWindow = 0;
    return true;
  }

  float pace() { return lastPace; }                         // steps per second
  Activity activity() { return activityFromPace(lastPace); }

private:
  long count = 0;              // readings so far
  long lastPeak = -1000;       // reading number of the last peak
  int samplesInWindow = 0;
  int peaksInWindow = 0;
  float previous = 1.0f;
  float beforePrevious = 1.0f;
  float gravity = 1.0f;
  float lastPace = 0.0f;
};
