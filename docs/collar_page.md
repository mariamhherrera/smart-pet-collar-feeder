# Collar page (UI-5)

The Collar page shows the pet's live heart rate and activity from Firebase (`owners/{uid}/collar/latest`).
Open it from the **Collar** tab in the bottom bar.

## Normal heart rate ranges (UI-5.1)

The app uses the pet's species from the pet profile to pick a default normal range.

| Species | Range used by the app | Sources |
|---|---|---|
| Dog | 60-120 BPM | Merck Vet Manual: 70-120. Animal Medical Center: 60-90 (large dogs), 90-120 (small dogs). |
| Cat | 120-180 BPM | Merck Vet Manual: 120-140. Animal Medical Center: about 140-180. |

The app range covers both sources. These are **resting** ranges, so a red value during play or right after exercise is expected.
The Settings page (UI-8) can later let the owner set their own range.

Sources:

- Merck Veterinary Manual, "Resting Heart Rates": https://www.merckvetmanual.com/multimedia/table/resting-heart-rates
- Animal Medical Center (New York), "Vital Signs - Know What's Normal for Your Pet": https://www.amcny.org/pet_health_library/vital-signs/

## How the page decides what to show

- **Connected:** the newest reading is less than 30 seconds old (the collar uploads every 5 seconds).
- **Heart rate colour (UI-5.3):** green inside the normal range (the ends count as normal), red outside it.
- **Activity:** shows `activity` from Firebase once COL-2 saves it. Until then it shows a rough "Still" or "Moving" from the gyroscope (moving above 30 °/s).

The rules are in `CollarVitals.kt`, and the unit tests are in `CollarVitalsTest.kt`.

## Test cases (UI-5.4)

Black-box tests, done in the app.

For cases 3 to 6, turn the collar off (it overwrites `latest` every 5 seconds). Then in the Firebase console, under `owners/{uid}/collar/latest`, set `heartRateBpm` and set `receivedAt` to the current time in milliseconds (for example from currentmillis.com). The page treats it as live for 30 seconds.

| # | Setup | Expected result |
|---|---|---|
| 1 | Collar on and uploading, dog profile | "Collar connected" in green, heart rate and activity update every few seconds |
| 2 | Turn the collar off, wait 30 s | "Collar disconnected" in red, heart rate and activity show "—" |
| 3 | Dog profile, `heartRateBpm` = 90 | 90 BPM in green, "Normal" |
| 4 | Dog profile, `heartRateBpm` = 150 | 150 BPM in red, "Higher than normal" |
| 5 | Cat profile, `heartRateBpm` = 100 | 100 BPM in red, "Lower than normal" |
| 6 | Collar on, no `heartRateBpm` field | "Waiting for heart rate" |
| 7 | No pet profile | Heart rate shows with no colour, "Add a pet profile to check if this is normal" |
| 8 | Shake the collar, then leave it still | Activity changes from "Moving" to "Still" |
