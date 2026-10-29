"""
SIM-4.6: Improve the chosen method (step counting).

Tries many positions for the two cut-off lines (rest_max and walk_max)
on practice data (seed 1), keeps the pair that guesses right most often,
then tests that pair on new data (seeds 2 to 5).
"""

from method_strength import get_windows, guess_activity
from method_steps import steps_per_second


def accuracy(paces, labels, rest_max, walk_max):
    """% of windows guessed right with these two cut-off lines."""
    correct = 0
    for i in range(len(paces)):
        if guess_activity(paces[i], rest_max, walk_max) == labels[i]:
            correct += 1
    return 100 * correct / len(paces) #return percentage of windows guesed correctly


# 1. Make the practice data (seed 1) and the pace of every window
windows, labels = get_windows(seed=1)
paces = []
for window in windows:
    paces.append(steps_per_second(window))

print("Windows:", len(windows))
print("Slowest pace:", round(min(paces), 2), "steps per second")
print("Fastest pace:", round(max(paces), 2), "steps per second")

# 2. Slide line 1 in six different postions (.25 to 1.5) and line 2 in 21 different positions (1.5 to 3.5) for 126 combinations in total.Remember the best pair with their accuracy.
best_score = 0
best_rest = 0
best_walk = 0

for r in range(1, 7):                 # line 1: 0.25, 0.50, ... 1.50
    rest_max = r * 0.25
    for w in range(15, 36):           # line 2: 1.5, 1.6, ... 3.5
        walk_max = w / 10
        score = accuracy(paces, labels, rest_max, walk_max)
        if score > best_score:
            best_score = score
            best_rest = rest_max
            best_walk = walk_max

print("Best line 1 (rest_max):", best_rest)
print("Best line 2 (walk_max):", best_walk)
print("Training accuracy:", round(best_score, 1), "%")

# 3. Test the best lines on new data the tuning never saw (seeds 2 to 5)
total = 0
for seed in [2, 3, 4, 5]:
    test_windows, test_labels = get_windows(seed)
    test_paces = []
    for window in test_windows:
        test_paces.append(steps_per_second(window))
    score = accuracy(test_paces, test_labels, best_rest, best_walk)
    print("Seed", seed, "| tuned steps:", round(score, 1), "%")
    total += score

print("Average with tuned lines:", round(total / 4, 1), "%")
print("Before tuning (SIM-4.5): 89.8 %")

