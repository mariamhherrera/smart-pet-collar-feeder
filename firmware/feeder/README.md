# Feeder (ESP32)

`feeder_bluetooth/` is the feeder sketch. It has two tabs:

- `feeder_bluetooth.ino`: the servo plate (portions), the schedule, the meal limit, the IR food level, and the Bluetooth / Serial Monitor commands.
- `firebase_link.ino` (COM-3.3, FD-1.7): Wi-Fi, the time from the internet, and the commands from Firebase.

`food_level_test/` is the IR sensor test used to calibrate the food level (FD-4.1).

## Arduino IDE settings

- Board: **ESP32 Dev Module**
- Tools > Partition Scheme: **Huge APP (3MB No OTA/1MB SPIFFS)**. Bluetooth and Wi-Fi together do not fit in the default.
- Libraries (Library Manager): **ESP32Servo** (Kevin Harrington) and **ArduinoJson** (Benoit Blanchon).

## Wi-Fi and Firebase (COM-3.3)

1. Copy `feeder_bluetooth/secrets.example.h` to `feeder_bluetooth/secrets.h`.
2. Fill it in: the home Wi-Fi (2.4 GHz), the Firebase Web API key and database URL (same as the collar), and the **same email and password you use in the app**.
3. Upload. The Serial Monitor (115200) shows `Time from the internet`, then `Signed in to Firebase!`.

`secrets.h` is in `.gitignore`, so the passwords never go to GitHub. Without it, the feeder works by Bluetooth only.

**Wi-Fi first, Bluetooth as the backup.** The ESP32 does not have enough memory for Bluetooth and a secure (https) connection at the same time (about 35 KB free with Bluetooth on, a secure connection needs 40 to 50 KB). So with `secrets.h`, Bluetooth stays off and the feeder uses Wi-Fi. Bluetooth turns on only if:
- the feeder is not online 45 seconds after starting, or
- Wi-Fi is lost for 2 minutes.

Then Wi-Fi is turned off and the app talks to the feeder by Bluetooth, like before. Restart the feeder (EN button or unplug) to try Wi-Fi again.

What the feeder reads and writes, under `owners/<your account>/`:

| Every | Reads / writes | Path |
|---|---|---|
| 3 s | Reads new commands. Feed Now: gives the portion. Schedule: saves it. Then marks the command `done`, `failed` or `expired` | `feeder/commands` |
| after a meal | Marks the meal `fed` or `failed` (with the reason, like `ERR DAILY LIMIT`) | `feeder/feedings/<id>` |
| 60 s | Reads the meal limit and the schedule | `settings/mealLimit`, `feeder/schedule` |
| 30 s | Writes `connected`, `lastSeen`, `foodPercent`, `feedsToday`, `mealLimit` | `feeder/status` |

Safety:
- A command is only run once. The newest command handled is kept in flash, so a restart never feeds the same meal twice.
- No command is run before the feeder knows the date, and commands older than 10 minutes are marked `expired` instead of fed (for example, Feed Now taps made while the feeder was unplugged).
- The same rules as Bluetooth apply: 3 s cooldown and the daily meal limit.

The app shows the feeder Online only if it wrote its status in the last 90 seconds.
