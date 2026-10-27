"""
SIM-4.3: First detection method - how strong is the movement?

A resting pet barely moves the collar, a walking pet shakes it a bit,
and a running pet shakes it a lot. We look at 4 seconds of readings
at a time, measure how much they wiggle, and compare that number with
two thresholds to guess the activity.
"""

import math
import random

from generate_data import make_session, SAMPLE_RATE, ACTIVITIES

WINDOW_SIZE = 4 * SAMPLE_RATE    # 4 seconds = 200 readings


def get_windows(seed, total_seconds=7200):
    """
    Make fake data (2 hours by default) and cut it into 4-second windows.
    Returns two lists: the windows, and the true activity of each window.
    Each window is a list of 200 numbers: the total acceleration of each reading.
    """
    x, y, z, labels = make_session(total_seconds, seed)

    # 1. Total acceleration of each reading, after adding a little noise.
    #    The noise is TEMPORARY, until Johan's SIM-4.2 is ready.
    totals = []
    for i in range(len(z)):
        noisy_x = x[i] + random.gauss(0, 0.03)
        noisy_y = y[i] + random.gauss(0, 0.03)
        noisy_z = z[i] + random.gauss(0, 0.03)
        totals.append(math.sqrt(noisy_x ** 2 + noisy_y ** 2 + noisy_z ** 2))

    # 2. Cut the readings into windows of 200
    windows = []
    window_labels = []
    number_of_windows = len(totals) // WINDOW_SIZE
    for w in range(number_of_windows):
        start = w * WINDOW_SIZE
        end = start + WINDOW_SIZE
        windows.append(totals[start:end])
        window_labels.append(most_common(labels[start:end]))

    return windows, window_labels


def most_common(labels):
    """The activity that appears most often in a list of labels."""
    best = labels[0]
    for activity in ACTIVITIES:
        if labels.count(activity) > labels.count(best):
            best = activity
    return best


def movement_strength(window):
    """
    How much the readings wiggle around their average (the standard deviation), in g.
    Close to 0 when resting, bigger when walking, biggest when running.
    """
    average = sum(window) / len(window)
    total = 0
    for value in window:
        total += (value - average) ** 2
    return math.sqrt(total / len(window))


def average_for(activity, values, labels):
    """Average of the values whose true label is this activity."""
    total = 0
    count = 0
    for i in range(len(values)):
        if labels[i] == activity:
            total += values[i]
            count += 1
    return total / count


def pick_thresholds(values, labels):
    """
    Put each threshold halfway between the averages of two neighbouring activities.
    Returns rest_max (below it: resting) and walk_max (below it: walking).
    """
    resting = average_for("resting", values, labels)
    walking = average_for("walking", values, labels)
    running = average_for("running", values, labels)
    rest_max = (resting + walking) / 2
    walk_max = (walking + running) / 2
    return rest_max, walk_max


def guess_activity(value, rest_max, walk_max):
    """Turn a window's number into a guess, using the two thresholds."""
    if value < rest_max:
        return "resting"
    elif value < walk_max:
        return "walking"
    else:
        return "running"


# This part only runs when you run this file directly.
if __name__ == "__main__":

    # 1. Choose the thresholds using one set of fake data (seed 1)
    windows, labels = get_windows(seed=1)
    strengths = []
    for window in windows:
        strengths.append(movement_strength(window))
    rest_max, walk_max = pick_thresholds(strengths, labels)
    print("Resting if below", round(rest_max, 3), "g")
    print("Running if above", round(walk_max, 3), "g")

    # 2. Test them on new fake data (seed 2) that the method has never seen
    windows, labels = get_windows(seed=2)
    correct = 0
    for i in range(len(windows)):
        guess = guess_activity(movement_strength(windows[i]), rest_max, walk_max)
        if guess == labels[i]:
            correct += 1
    print("Correct:", correct, "out of", len(windows), "windows")
    print("Accuracy:", round(100 * correct / len(windows), 1), "%")