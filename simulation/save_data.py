
# SIM-4.2 - Add noise and save simulated motion data
# Johan Quezada - ELEC/COEN 390

import csv
import random
from pathlib import Path
from generate_data import make_session, SAMPLE_RATE

# Generate motion data using SIM-4.1
x, y, z, labels = make_session(total_seconds=180, seed=42)

# Add small random noise to simulate sensor imperfections
rng = random.Random(43)
noise_std = 0.02  # Assumed noise in g

# Save the simulated readings to a CSV file
output_file = Path(__file__).with_name("motion_data_noisy.csv")

with output_file.open("w", newline="") as file:
    writer = csv.writer(file)

    writer.writerow(["time_s", "x_g", "y_g", "z_g", "activity"])

    for i in range(len(labels)):
        noisy_x = x[i] + rng.gauss(0, noise_std)
        noisy_y = y[i] + rng.gauss(0, noise_std)
        noisy_z = z[i] + rng.gauss(0, noise_std)

        writer.writerow([
            round(i / SAMPLE_RATE, 3),
            round(noisy_x, 4),
            round(noisy_y, 4),
            round(noisy_z, 4),
            labels[i]
        ])

print("SIM-4.2 completed successfully!")
print(f"Total readings saved: {len(labels)}")
print(f"File saved: {output_file}")
