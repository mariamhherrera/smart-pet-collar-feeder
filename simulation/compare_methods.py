"""
SIM-4.5: Compare ick the best method.

Tests both detection methods on exactly the same new data, four times
(seeds 2 to 5), prints the results side by side, and keeps the method
that is right most often on average.
"""

from method_strength import get_windows, movement_strength, pick_thresholds, guess_activity
from method_steps import steps_per_second

TEST_SEEDS = [2, 3, 4, 5]    # new data that was never used to pick thresholds


def test_both(seed, strength_rest, strength_walk, steps_rest, steps_walk):
    """Accuracy (in %) of both methods on the same windows from one seed."""
    windows, labels = get_windows(seed)
    strength_correct = 0
    steps_correct = 0
    for i in range(len(windows)):
        guess = guess_activity(movement_strength(windows[i]), strength_rest, strength_walk)
        if guess == labels[i]:
            strength_correct += 1
        guess = guess_activity(steps_per_second(windows[i]), steps_rest, steps_walk)
        if guess == labels[i]:
            steps_correct += 1
    strength_accuracy = 100 * strength_correct / len(windows)
    steps_accuracy = 100 * steps_correct / len(windows)
    return strength_accuracy, steps_accuracy


# This part only runs when you run this file directly.
if __name__ == "__main__":

    # 1. Pick the thresholds of both methods using seed 1
    windows, labels = get_windows(seed=1)
    strengths = []
    paces = []
    for window in windows:
        strengths.append(movement_strength(window))
        paces.append(steps_per_second(window))
    strength_rest, strength_walk = pick_thresholds(strengths, labels)
    steps_rest, steps_walk = pick_thresholds(paces, labels)

    # 2. Test both methods on the same new data, seed by seed
    strength_total = 0
    steps_total = 0
    for seed in TEST_SEEDS:
        strength_accuracy, steps_accuracy = test_both(seed, strength_rest, strength_walk,
                                                      steps_rest, steps_walk)
        print("Seed", seed, "| strength:", round(strength_accuracy, 1),
              "% | steps:", round(steps_accuracy, 1), "%")
        strength_total += strength_accuracy
        steps_total += steps_accuracy

    # 3. Average over all test seeds, and keep the better method
    strength_average = strength_total / len(TEST_SEEDS)
    steps_average = steps_total / len(TEST_SEEDS)
    print("Average  | strength:", round(strength_average, 1),
          "% | steps:", round(steps_average, 1), "%")

    if strength_average > steps_average:
        print("Best method: movement strength (SIM-4.3)")
    else:
        print("Best method: step counting (SIM-4.4)")