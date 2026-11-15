# Pairing and activity tests (COM-4.4, COL-2.4)

Before testing: flash the new collar firmware (`firmware/collar/firebase_motion_s2`), deploy `database.rules.json`, and install the app from this branch.

## Pairing (COM-4.4)

| # | Steps | Expected result | Pass? |
|---|---|---|---|
| P1 | Register a new account, fill in the pet profile, tap Save profile | "Pair your collar", Step 2 of 2, "Looking for your collar…" | |
| P2 | Turn the collar on (on Wi-Fi) | Within about 20 s: "Collar found", the collar ID, "Online · sent data just now", battery and signal | |
| P3 | Tap "Link to (pet name)" | "Collar … linked", Home shows the collar online and the activity | |
| P4 | On P2, tap "Not my collar" | Back to "Looking for your collar…", the same collar is not shown again | |
| P5 | Type `PC-123` and tap Link collar | Error: collar IDs look like PC-3C71F2 | |
| P6 | Type a correct-looking ID that never connected (e.g. `PC-000000`) | Error: no collar with this ID has connected yet. Can try again | |
| P7 | Type the real ID from the Serial Monitor | "Collar found" for that ID | |
| P8 | Second account: try to link a collar that the first account linked | Error: linked to another account | |
| P9 | Pet profile > Unlink > Unlink | "No collar linked", Home and Collar page say "No collar linked" | |
| P10 | Pet profile > Pair collar › (or Change collar) | Pairing screen opens, linking a new collar replaces the old one | |
| P11 | Skip for now (after registering) | Home, "No collar linked". Can pair later from the pet profile | |

## Activity and minutes (COL-2.4)

Wear the collar (or hold it against your leg) for each activity, at least 1 minute each.

| # | Steps | Expected result | Pass? |
|---|---|---|---|
| A1 | Leave the collar still on a table for 1 minute | Home and Collar page: Resting, about 0 steps/s | |
| A2 | Walk normally for 1 minute | Walking, about 1.3 to 2.3 steps/s | |
| A3 | Run or jog for 1 minute | Running, above 2.3 steps/s | |
| A4 | After A1 to A3, look at Collar page > Today | Resting, walking and running each about 1 min more than before | |
| A5 | History > Week | Today's bar shows the walking + running minutes, the line under shows each activity | |
| A6 | History > Pick a day > today | Same minutes as the Collar page | |
| A7 | Restart the collar (unplug, plug back) | Today's minutes keep going from where they were (at most 1 minute lost) | |

Accuracy for the testing document: for A1 to A3, count how many 5-second updates show the right activity.

## Offline

| # | Steps | Expected result | Pass? |
|---|---|---|---|
| O1 | Turn off the Wi-Fi router (or move the collar out of range) for 2 minutes, while walking | App: "Offline since …" after 30 s | |
| O2 | Turn the Wi-Fi back on | Collar reconnects by itself in under 30 s; today's minutes include the time it was offline | |
| O3 | Phone in airplane mode, open the app | Last known activity and minutes still show, collar "Offline since …" | |
| O4 | Phone in airplane mode, unlink the collar, then turn airplane mode off | The unlink reaches Firebase by itself | |
