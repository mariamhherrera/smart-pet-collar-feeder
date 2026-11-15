# Collar firmware

`firebase_motion_s2/` is the collar code: Adafruit Feather ESP32-S2 + MPU-6500 motion sensor, sending to Firebase over Wi-Fi.
The other folders (`motion_reader`, `motion_reader_s2`) are older test sketches.

## Set it up

1. In `firebase_motion_s2/`, copy `secrets.example.h` to `secrets.h` and fill it in (Wi-Fi, Firebase API key, database URL, an email/password account).
2. Arduino IDE: board **Adafruit Feather ESP32-S2**, library **ArduinoJson 7**.
   Works with ESP32 core 3.x and 4.x. Core 4.x is better: it also checks Firebase's HTTPS certificate.
3. Upload, open the Serial Monitor (115200). It prints the collar ID, like:

   ```
   ==============================
     Smart collar ID: PC-3C71F2
   ==============================
   ```

   Write the ID on a sticker under the pod. The app finds the collar by itself, but the ID can also be typed in.
4. Deploy the new `database.rules.json` (Firebase console > Realtime Database > Rules, paste it, Publish).

## What it does

| Every | What | Where in Firebase |
|---|---|---|
| 20 ms | Reads the motion sensor (its own task, never paused by Wi-Fi) | |
| 4 s | Counts steps, decides resting / walking / running (`activity.h`), adds 4 s to today's total | |
| 5 s | Newest reading, activity, steps per second, today's seconds, battery, Wi-Fi signal | `collars/<ID>/latest`, `collars/<ID>/seenAt` |
| 1 min | Today's totals (and any days that ended while offline) | `collarDays/<ID>/<date>` |
| 1 min | Saves today's totals in flash, so a restart does not lose them | |

Activity: same method and numbers as the simulation (SIM-4): peaks above gravity + 0.1 g, at least 0.2 s apart, counted in 4-second windows.
Below 1.25 steps/s = resting, below 2.3 = walking, otherwise running.
Tested on the simulation data: 91 % correct on seeds 2 to 5 (the Python version got 89.8 %).

## Pairing (COM-4)

The collar does not know who owns it. It only writes under its own ID.
The app links the ID to the pet: `owners/<uid>/pet/collarId` and `collars/<ID>/owner`.
Only the owner can unlink it, and a collar linked to another account cannot be linked.

## Offline

- No Wi-Fi: the collar keeps counting and tries Wi-Fi again every 15 seconds.
- Midnight while offline: the day is kept (up to 7 days) and sent when Wi-Fi is back.
- Restart: today's totals come back from flash. The clock comes back from the internet.
- Phone offline: the app shows the last values it got, and a link/unlink is sent when the phone is back online.

## Battery

The Feather ESP32-S2 measures the battery with a MAX17048 chip on the same I2C wires (address 0x36).
If the chip is not found (older board revision with an LC709203F, or no battery), the collar just does not send the battery and the app shows "--".
