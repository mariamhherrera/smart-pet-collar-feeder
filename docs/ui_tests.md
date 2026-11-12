# UI tests (Sprint 2)

Manual tests for the UI issues, run on the emulator or a phone after `Sync` and `Run`.
The logic behind them (heart rate ranges, meal limit, reading collar data) is also covered
by `UiLogicTest` in `android-app/app/src/test` (10 tests).

| Test | Issue | Steps | Expected | Result |
|---|---|---|---|---|
| T1 | UI-3.4 | Log in. | Home opens: greeting with the pet's name, Devices, Heart rate, Next meal, Feed now, Activity, Recent meals. | |
| T2 | UI-3.3 | Tap each tab at the bottom, then the phone's Back button. | Each page opens with its tab in teal. Back always returns to Home. | |
| T3 | UI-7.1 | Tap the round profile icon (top right) on any page. | Pet profile opens with a back arrow. Back returns to the page you were on. | |
| T4 | UI-7.2 | Change the pet's name, tap Save profile. | Home says "Hi, (new name)!". | |
| T5 | UI-4.2 | Feeder page: set a time and portion, tap Save schedule. | "Saved" text updates. Home's Next meal card shows the same time and portion. | |
| T6 | UI-4.2 | Feeder page: pick Large, tap Feed now, then Feed. | Confirmation pop-up first. With the feeder connected, it gives a large portion. | |
| T7 | UI-5.4 | Turn on the collar (Wi-Fi). Open the Collar page. | Status turns green "Online" within 5 seconds. Live motion changes when the collar moves. | |
| T8 | UI-5.4 | Turn off the collar, wait 30 seconds. | Status turns red "Offline since (time)". | |
| T9 | UI-5.1, UI-5.3 | With the collar off, put a test heart rate in Firebase (`owners/(uid)/collar/latest/heartRate`) for a dog: 60, then 90, then 130. | Number is blue "Low", then green "Normal", then red "High". Range text says 70 to 120 BPM. | |
| T10 | UI-6.3 | Keep the collar on with the app open for 10 minutes, then open History. | Movement graph shows a line for the last hour. | |
| T11 | UI-6.3 | Give 2 meals with Feed now, open History. | Today's bar in "Meals, last 7 days" goes up by 2. | |
| T12 | UI-8.4 | Settings: set Meals per day to 2, tap Save settings. | "Settings saved". Home shows "x of 2 meals today". | |
| T13 | UI-8.3 | With the limit at 2 and 2 meals given today, tap Feed now on Home. | Nothing is sent. Message: "Daily limit reached (2 of 2 meals)". | |
| T14 | UI-8.4 | Close and reopen the app, open Settings. | The saved values are still there. | |
| T15 | UI-8.1 | Settings: tap Log out. | Login screen opens. Back does not return to the app. | |
