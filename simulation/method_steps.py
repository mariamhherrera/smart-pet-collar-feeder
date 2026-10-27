"""
SIM-4.4: Second detection method - count the steps.

Every step makes the collar bounce, which shows up as a peak in the
total acceleration. We count the peaks in each 4-second window, turn
that into steps per second, and use two thresholds to guess the activity:
almost no steps = resting, a medium pace = walking, a fast pace = running.
"""

from generate_data import SAMPLE_RATE
from method_strength import get_windows, pick_thresholds, guess_activity

PEAK_HEIGHT = 0.1                   # a bounce must rise at least 0.1 g above gravity (1 g)
MIN_GAP = int(0.2 * SAMPLE_RATE)    # peaks closer than 0.2 s (10 readings) count as one bounce


def count_peaks(window):
    """How many step-like peaks there are in one window."""
    count = 0
    last_peak = -MIN_GAP              # so the very first peak is allowed
    for i in range(1, len(window) - 1):
        is_top = window[i] > window[i - 1] and window[i] >= window[i + 1]
        is_high = window[i] > 1.0 + PEAK_HEIGHT
        is_far = i - last_peak >= MIN_GAP
        if is_top and is_high and is_far:
            count += 1
            last_peak = i
    return count


def steps_per_second(window):
    """The window's pace: number of peaks divided by its length in seconds."""
    seconds = len(window) / SAMPLE_RATE
    return count_peaks(window) / seconds


# This part only runs when you run this file directly.
if __name__ == "__main__":

    # 1. Choose the thresholds using one set of fake data (seed 1)
    windows, labels = get_windows(seed=1)
    paces = []
    for window in windows:
        paces.append(steps_per_second(window))
    rest_max, walk_max = pick_thresholds(paces, labels)
    print("Resting if below", round(rest_max, 2), "steps per second")
    print("Running if above", round(walk_max, 2), "steps per second")

    # 2. Test them on new fake data (seed 2) that the method has never seen
    windows, labels = get_windows(seed=2)
    correct = 0
    for i in range(len(windows)):
        guess = guess_activity(steps_per_second(windows[i]), rest_max, walk_max)
        if guess == labels[i]:
            correct += 1
    print("Correct:", correct, "out of", len(windows), "windows")
    print("Accuracy:", round(100 * correct / len(windows), 1), "%")