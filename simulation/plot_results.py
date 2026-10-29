"""
SIM-4.7: Plot the simulation results.

Graph 1 (activity_data.png): 5 seconds of fake collar data for each activity,
so you can see what resting, walking and running look like to the collar.

Graph 2 (accuracy.png): how often each detection method guessed right on
each set of test data (seeds 2 to 5) and on average, with the 80 % sprint goal.
It also shows step counting with the tuned cut-off lines from SIM-4.6.

The graphs are saved in simulation/results/ for the Sprint 1 report.
Run again after SIM-4.6 is merged to update them.
"""

import random
from pathlib import Path

import matplotlib.pyplot as plt

from generate_data import make_session, SAMPLE_RATE, ACTIVITIES
from method_strength import get_windows, movement_strength, pick_thresholds, guess_activity
from method_steps import steps_per_second
from compare_methods import test_both, TEST_SEEDS

RESULTS_FOLDER = Path(__file__).with_name("results")
COLOURS = {"resting": "tab:green", "walking": "tab:blue", "running": "tab:red"}
SECONDS_SHOWN = 5
NOISE = 0.03        # same sensor noise as get_windows() in method_strength.py
SPRINT_GOAL = 80    # % of windows that must be guessed right

# Best cut-off lines found by tune_steps.py (SIM-4.6).
# If tune_steps.py finds new values, copy them here.
TUNED_REST = 1.25   # below this pace: resting
TUNED_WALK = 2.3    # below this pace: walking, above: running


def plot_activity_data():
    """Graph 1: 5 seconds of fake collar data for each activity, one above the other."""
    x, y, z, labels = make_session(total_seconds=1800, seed=1)

    figure, axes = plt.subplots(3, 1, figsize=(8, 6), sharex=True, sharey=True)

    for row in range(len(ACTIVITIES)):
        activity = ACTIVITIES[row]

        # 1. Find the first time the pet does this activity, and take 5 seconds of it
        start = labels.index(activity)
        end = start + SECONDS_SHOWN * SAMPLE_RATE

        # 2. Add a little sensor noise, like the detection methods see
        times = []
        readings = []
        for i in range(start, end):
            times.append((i - start) / SAMPLE_RATE)
            readings.append(z[i] + random.gauss(0, NOISE))

        # 3. Draw it
        axes[row].plot(times, readings, color=COLOURS[activity])
        axes[row].axhline(1.0, color="gray", linestyle=":", linewidth=1)   # gravity
        axes[row].set_title(activity.capitalize(), loc="left", fontsize=10)
        axes[row].set_ylabel("Acceleration (g)")

    axes[-1].set_xlabel("Time (seconds)")
    figure.suptitle("Simulated collar data for each activity")
    figure.tight_layout()

    file = RESULTS_FOLDER / "activity_data.png"
    figure.savefig(file, dpi=150)
    plt.close(figure)
    print("Saved", file)


def tuned_accuracy(seed):
    """% of windows step counting guesses right on one seed, with the SIM-4.6 lines."""
    windows, labels = get_windows(seed)
    correct = 0
    for i in range(len(windows)):
        if guess_activity(steps_per_second(windows[i]), TUNED_REST, TUNED_WALK) == labels[i]:
            correct += 1
    return 100 * correct / len(windows)


def plot_accuracy():
    """Graph 2: accuracy of both methods for each test seed, side by side."""

    # 1. Pick the thresholds of both methods on seed 1, exactly like compare_methods.py
    windows, labels = get_windows(seed=1)
    strengths = []
    paces = []
    for window in windows:
        strengths.append(movement_strength(window))
        paces.append(steps_per_second(window))
    strength_rest, strength_walk = pick_thresholds(strengths, labels)
    steps_rest, steps_walk = pick_thresholds(paces, labels)

    # 2. Test both methods on each new seed (same function as compare_methods.py),
    #    and step counting again with the tuned lines from SIM-4.6
    group_names = []
    strength_results = []
    steps_results = []
    tuned_results = []
    for seed in TEST_SEEDS:
        strength_accuracy, steps_accuracy = test_both(seed, strength_rest, strength_walk,
                                                      steps_rest, steps_walk)
        group_names.append("Seed " + str(seed))
        strength_results.append(strength_accuracy)
        steps_results.append(steps_accuracy)
        tuned_results.append(tuned_accuracy(seed))

    # 3. Add the average as the last group of bars
    group_names.append("Average")
    strength_results.append(sum(strength_results) / len(strength_results))
    steps_results.append(sum(steps_results) / len(steps_results))
    tuned_results.append(sum(tuned_results) / len(tuned_results))

    # 4. Draw three bars per group: grey = strength, purple = steps, orange = tuned steps
    bar_width = 0.27
    left = []
    middle = []
    right = []
    for i in range(len(group_names)):
        left.append(i - bar_width)
        middle.append(i)
        right.append(i + bar_width)

    figure = plt.figure(figsize=(9, 4.5))
    plt.bar(left, strength_results, bar_width, color="tab:gray",
            label="Movement strength (SIM-4.3)")
    plt.bar(middle, steps_results, bar_width, color="tab:purple",
            label="Step counting (SIM-4.4)")
    plt.bar(right, tuned_results, bar_width, color="tab:orange",
            label="Step counting, tuned (SIM-4.6)")
    plt.axhline(SPRINT_GOAL, color="black", linestyle="--", linewidth=1,
                label="Sprint goal (80 %)")

    # 5. Write the exact value above each bar
    for i in range(len(group_names)):
        plt.text(left[i], strength_results[i] + 0.5, str(round(strength_results[i], 1)),
                 ha="center", fontsize=8)
        plt.text(middle[i], steps_results[i] + 0.5, str(round(steps_results[i], 1)),
                 ha="center", fontsize=8)
        plt.text(right[i], tuned_results[i] + 0.5, str(round(tuned_results[i], 1)),
                 ha="center", fontsize=8)

    plt.xticks(range(len(group_names)), group_names)
    plt.ylim(50, 100)
    plt.ylabel("Windows guessed right (%)")
    plt.title("Accuracy of each detection method on new fake data")
    plt.legend(loc="upper center", bbox_to_anchor=(0.5, -0.1), ncol=4, fontsize=8)   # under the graph
    plt.tight_layout()

    file = RESULTS_FOLDER / "accuracy.png"
    figure.savefig(file, dpi=150)
    plt.close(figure)
    print("Saved", file)


# This part only runs when you run this file directly.
if __name__ == "__main__":
    RESULTS_FOLDER.mkdir(exist_ok=True)
    plot_activity_data()
    plot_accuracy()
