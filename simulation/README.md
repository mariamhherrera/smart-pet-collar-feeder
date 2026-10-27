# Simulation: activity detection (SIM-4)

Fake collar data is used to test two ways of guessing whether the pet is resting, walking or running, before the real collar exists.

## Files

- `generate_data.py` (SIM-4.1): makes fake collar data, with the true activity of every reading.
- `method_strength.py` (SIM-4.3): method 1, guesses from how strong the movement is.
- `method_steps.py` (SIM-4.4): method 2, guesses from steps per second.
- `compare_methods.py` (SIM-4.5): tests both methods on the same data and picks the best.

## How to run

1. `python3 -m venv .venv` (first time only)
2. `source .venv/bin/activate`
3. `pip install matplotlib` (first time only)
4. `python simulation/compare_methods.py`

## Results (SIM-4.5)

Thresholds were picked on seed 1. Both methods were then tested on new data: seeds 2 to 5, 2 hours of fake data each.

| Seed | Movement strength | Step counting |
|---|---|---|
| 2 | 85.1 % | 93.5 % |
| 3 | 93.4 % | 84.6 % |
| 4 | 86.2 % | 89.2 % |
| 5 | 91.9 % | 92.1 % |
| **Average** | **89.2 %** | **89.8 %** |

**Best method: step counting (SIM-4.4).** Both methods meet the sprint goal of 80 %.

## Notes

- The fake data uses guesses (50 readings per second, step speeds and bounce sizes). They should be replaced with real collar recordings.
- The noise added in `get_windows` is temporary, until SIM-4.2 is ready.