"""
SIM-4.1: MAKE FAKE MOTION DATA FOR THE PET COLLAR.

Creates pretend accelerometer readings for a pet that switches between
resting, walking and running. Every reading also gets a label with the
true activity, so later we can check if our detection methods guess right.
"""

import math
import random

import matplotlib.pyplot as plt

SAMPLE_RATE = 50    # readings per second (must match the real collar)
ACTIVITIES = ["resting", "walking", "running"]


def make_session(total_seconds, seed):
    """
    Make fake collar data lasting at least total_seconds.
    The same seed always gives exactly the same data.
    Returns four lists of the same length: x, y, z and labels.
    """
    random.seed(seed)
    x = []
    y = []
    z = []
    labels = []

    while len(labels) < total_seconds * SAMPLE_RATE:

        # 1. Pick an activity and how long the pet keeps doing it
        activity = random.choice(ACTIVITIES)
        seconds = random.uniform(20, 120)

        # 2. Pick how fast the steps are and how big each bounce is (in g).
        #    These numbers are our guesses until we have real collar data.
        #    Walking and running overlap a little on purpose.
        if activity == "resting":
            steps_per_second = 0
            bounce = 0
        elif activity == "walking":
            steps_per_second = random.uniform(1.5, 2.5)
            bounce = random.uniform(0.2, 0.6)
        else:
            steps_per_second = random.uniform(2.2, 4.0)
            bounce = random.uniform(0.5, 1.0)

        # 3. Make the readings. On our fake collar, z points up:
        #    gravity (1 g) plus one bounce per step. x and y stay at 0.
        for i in range(int(seconds * SAMPLE_RATE)):
            time = i / SAMPLE_RATE
            up_down = bounce * math.sin(2 * math.pi * steps_per_second * time)
            x.append(0.0)
            y.append(0.0)
            z.append(1.0 + up_down)
            labels.append(activity)

    return x, y, z, labels


# This part only runs when you run this file directly.
# It draws 5 seconds of each activity so you can check they look different.
if __name__ == "__main__":
    x, y, z, labels = make_session(total_seconds=1800, seed=1)
    print("Made", len(labels), "readings")

    for activity in ACTIVITIES:
        start = labels.index(activity)      # first reading of this activity
        end = start + 5 * SAMPLE_RATE       # 5 seconds later
        plt.plot(z[start:end], label=activity)

    plt.xlabel("reading number (50 readings = 1 second)")
    plt.ylabel("z acceleration (g)")
    plt.legend()
    plt.show()