# UI tests (Sprint 2)

Manual tests for the UI issues, run on the emulator or a phone after `Sync` and `Run`.
The logic behind them (safe zone, meal limit, reading collar data) is also covered
by `UiLogicTest` in `android-app/app/src/test` (13 tests), and the feeder's LIMIT command was tested on a computer (10 checks).
The Week / Month / Year graphs (UI-6.2) are covered by `HistoryRangeTest` (13 tests).

Note on the History graphs: readings are saved while the app is open (Home or Collar page),
so the activity graph only covers that time until the collar saves its own history.

| Test | Issue | Steps | Expected | Result |
|---|---|---|---|---|
| T1 | UI-3.4 | Log in. | Home opens: greeting with the pet's name, Devices, Location, Next meal, Feed now, Activity, Recent meals. | |
| T2 | UI-3.3 | Tap each tab at the bottom, then the phone's Back button. | Each page opens with its tab in teal. Back always returns to Home. | |
| T3 | UI-7.1 | Tap the round profile icon (top right) on any page. | Pet profile opens with a back arrow. Back returns to the page you were on. | |
| T4 | UI-7.2 | Change the pet's name, tap Save profile. | Home says "Hi, (new name)!". | |
| T5 | UI-4.2 | Feeder page: set a time and portion, tap Save schedule. | "Saved" text updates. Home's Next meal card shows the same time and portion. | |
| T6 | UI-4.2 | Feeder page: pick Large, tap Feed now, then Feed. | Confirmation pop-up first. With the feeder connected, it gives a large portion. | |
| T7 | UI-5.4 | Turn on the collar (Wi-Fi). Open the Collar page. | Status turns green "Online" within 5 seconds. Live motion changes when the collar moves. | |
| T8 | UI-5.4 | Turn off the collar, wait 30 seconds. | Status turns red "Offline since (time)". | |
| T9 | COL-6 | With the collar off, put a test position in Firebase (`owners/(uid)/collar/latest`: `latitude`, `longitude`, and `receivedAt` = now in ms). Open the Collar page. | Location card shows "Location found", and "Open in Maps" opens the map at that spot. | |
| T9b | COL-6 | Settings: tap "Set home to the collar's location now", pick 100 m, Save. Then change `latitude` in Firebase by 0.002 (about 220 m). | Collar page: "Outside the safe zone" in red with the distance. Home: "Away" and the alert "(pet) left the safe zone". Changing it back shows "At home". | |
| T10 | UI-6.3 | Keep the collar on with the app open for 10 minutes, then open History. | Movement graph for Today shows a line. | |
| T10b | UI-6.1 | On History, tap the left arrow to go to yesterday, then the right arrow. | Yesterday's readings and meal count load (or "No collar readings on this day"). The right arrow stops at Today. | |
| T11 | UI-6.3 | Give 2 meals with Feed now, open History. | Today's bar in "Meals, past week" goes up by 2. | |
| T11b | UI-6.1 | Keep the collar on with the app open for 2 minutes. In Firebase, open `owners/(uid)/history/days/(today, like 2026-11-14)`. | It has `readings` (goes up every 5 seconds) and `activeReadings` (goes up when the collar moves). | |
| T11c | UI-6.2 | Fake data: in Firebase, add 3 days under `owners/(uid)/history/days`: today, 10 days ago and 100 days ago (key like `2026-11-04`), each with `readings` = 100 and `activeReadings` = 20, 50 and 80. Open History. | Week: 1 bar (today, 20%). Month: 2 bars (20%, 50%). Year: bars in 2 months. "Active ...% of the time" changes with each range. | |
| T11d | UI-6.2 | Tap Week, Month and Year. | Titles change to "past week / month / year". Week shows 7 day names ending with "Today", Month shows a date every 7 bars, Year shows 12 month letters. | |
| T11e | UI-6.2 | Delete `history/days` in Firebase (or log in with a new account), open History. | Activity says "Not enough collar data for this period". With no meals: "No meals in this period". | |
| T12 | UI-8.4 | Settings: set Meals per day to 2, tap Save settings. | "Settings saved". Home shows "x of 2 meals today". | |
| T13 | UI-8.3 | With the limit at 2 and 2 meals given today, tap Feed now on Home. | Nothing is sent. Message: "Daily limit reached (2 of 2 meals)". Home shows "Limit reached (2 of 2)" in red. | |
| T13b | UI-8.3 | Feeder page with the feeder connected: limit 2, give 2 meals, set the schedule to 1 minute from now. | At that minute the feeder skips the meal and the app shows "Scheduled meal skipped, daily limit reached". | |
| T14 | UI-8.4 | Close and reopen the app, open Settings. | The saved values are still there. | |
| T15 | UI-8.1 | Settings: tap Log out. | Login screen opens. Back does not return to the app. | |
