/*
  PetTracker feeder: everything on one ESP32
  FD-2.2 - Send manual feed command (feeder side)      Isabel Al-Haddad
  FD-1.3 - Send feeding command to the feeder (schedule)
  FD-1.4 - Servo plate that gives the portions         Maria (portion_test.ino)
  FD-4   - Food level with the GP2Y0E03 IR sensor      Isabel Al-Haddad
  COM-3.3 - Reads Feed Now and the schedule from Firebase over Wi-Fi (firebase_link.ino)
  FD-1.7 - Gets the time from the internet               Isabel Al-Haddad

  Wiring:
    Servo (MG996R): signal -> GPIO 13, red -> 5 V adapter +, brown -> GND
    IR sensor (GP2Y0E03): pins 1, 4, 5 -> 3V3 | pin 3 -> GND | pin 7 (SDA) -> GPIO 21 | pin 6 (SCL) -> GPIO 22
    All grounds (adapter -, servo, IR, ESP32) connected together.
  Libraries: ESP32Servo (by Kevin Harrington) and ArduinoJson (by Benoit Blanchon).
  Wire, BluetoothSerial, Preferences, WiFi and HTTPClient come with the ESP32 core.
  Tools > Partition Scheme > "Huge APP (3MB No OTA/1MB SPIFFS)": Bluetooth + Wi-Fi do not fit in the default.

  Wi-Fi and Firebase (COM-3.3): copy secrets.example.h to secrets.h (same folder) and fill it in.
  Without secrets.h the feeder works by Bluetooth only, like before.
  With secrets.h the feeder uses Wi-Fi. Bluetooth is the backup: it turns on only if Wi-Fi does not
  work (not online after 45 s, or Wi-Fi lost for 2 minutes). Restart the feeder to try Wi-Fi again.

  Commands (one per line, from the phone or the Serial Monitor):
    FEED                 feed one medium portion
    FEED,<portion>       feed one portion: 1 = small, 2 = medium, 3 = large
    SCHEDULE,HH:MM,<p>   save the daily feeding time and portion
    TIME,HH:MM:SS        tell the feeder what time it is
    LIMIT,<n>            most meals per day, 1 to 6 (UI-8.3, from the app's Settings)
    LEVEL                food left in the container, in % (reply: OK LEVEL <n>)
    STATUS               show the saved schedule, the time, today's feeds and the food level

  At the scheduled time, the feeder feeds by itself and sends
    SCHEDULED OK FED <n>   or   SCHEDULED ERR DAILY LIMIT

  Overfeeding protection:
  - A second feed within 3 seconds is ignored (double taps).
  - At most <limit> feeds per day (set with LIMIT, never more than 6).
    The scheduled meal counts too, and is skipped once the limit is reached.
*/

// Wi-Fi is on only if secrets.h exists (it is never pushed to GitHub)
#if __has_include("secrets.h")
#include "secrets.h"
#define USE_WIFI 1
#else
#define USE_WIFI 0
#endif

#include <BluetoothSerial.h>
#include <Preferences.h>
#include <ESP32Servo.h>
#include <Wire.h>
#if USE_WIFI
#include <WiFi.h>              // COM-3.3 (firebase_link.ino). Here so the Arduino IDE knows the types
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>
#endif

BluetoothSerial SerialBT;
bool bluetoothOn = false;    // COM-3.3: with Wi-Fi, Bluetooth only starts if Wi-Fi does not work
Preferences storage;         // saves the schedule in flash, so it survives a restart
String btMessage = "";
String usbMessage = "";

// Overfeeding protection settings
const unsigned long COOLDOWN_MS = 3000;            // 3 seconds between feeds
const int MAX_FEEDS_PER_DAY = 6;                   // the limit can be lowered, never raised above this
int dailyLimit = MAX_FEEDS_PER_DAY;                // UI-8.3: set by the app with LIMIT,<n>
const unsigned long ONE_DAY_MS = 24UL * 60 * 60 * 1000;

unsigned long lastFeedTime = 0;   // when the last feed happened (in ms)
bool hasFedBefore = false;        // false until the first feed
int feedsToday = 0;               // feeds since the start of the current day
unsigned long dayStartTime = 0;   // when the current day started (in ms)

// Saved schedule (FD-1.3)
bool hasSchedule = false;
int scheduleHour = 0;
int scheduleMinute = 0;
int schedulePortion = 2;          // 1 = small, 2 = medium, 3 = large

// Clock (FD-1.3): the phone tells the feeder the time with TIME,HH:MM:SS
bool clockSet = false;
long secondsAtSync = 0;           // time of day (in seconds) when TIME was received
unsigned long millisAtSync = 0;   // millis() when TIME was received
long lastSecondsOfDay = 0;        // the time at the last check, to notice midnight
bool scheduleDoneToday = false;   // true once today's scheduled meal was given (or tried)
unsigned long lastClockCheck = 0; // millis() of the last check, to check once per second

// ---------- Servo plate (FD-1.4, from Maria's portion_test.ino) ----------
const int SERVO_PIN = 13;
const int CLOSED_ANGLE = 120;     // hole covered
const int OPEN_ANGLE = 175;       // hole open
const int STEP_DELAY_MS = 4;      // swing speed (bigger = slower)

// How long the hole stays open for each portion. Tune these with the portion test.
const int SMALL_OPEN_MS = 500;
const int MEDIUM_OPEN_MS = 1000;
const int LARGE_OPEN_MS = 1500;

Servo plate;
int plateAngle = CLOSED_ANGLE;

// ---------- Food level (FD-4, GP2Y0E03 IR sensor) ----------
const uint8_t IR_ADDRESS = 0x40;  // the sensor's I2C address (0x80 in the datasheet = 0x40 in Arduino)
const float EMPTY_CM = 9.6;       // distance to the bottom when the container is empty
const float FULL_CM = 3.7;        // distance to the food at the max fill line
const unsigned long LEVEL_EVERY_MS = 5000;   // measure the food every 5 seconds
int foodLevel = -1;               // last food level in %, -1 = no reading yet
unsigned long lastLevelCheck = 0;

// Sends a reply to both the phone and the Serial Monitor
void reply(String text) {
  Serial.println(text);
  if (bluetoothOn && SerialBT.hasClient()) {
    SerialBT.println(text);
  }
}

// Writes a time as 07:30 instead of 7:30
String twoDigits(int number) {
  if (number < 10) return "0" + String(number);
  return String(number);
}

// The current time of day in seconds (0 to 86399). Only correct once clockSet is true.
long secondsOfDayNow() {
  long passed = (millis() - millisAtSync) / 1000;
  return (secondsAtSync + passed) % 86400;
}

// Turns the plate slowly to an angle, 2 degrees at a time (Maria's code)
void movePlate(int target) {
  int step = (target > plateAngle) ? 2 : -2;
  while (abs(target - plateAngle) > 2) {
    plateAngle += step;
    plate.write(plateAngle);
    delay(STEP_DELAY_MS);
  }
  plateAngle = target;
  plate.write(plateAngle);
}

// Gives one portion of food (1 = small, 2 = medium, 3 = large):
// opens the hole, waits, closes it. Bigger portion = open longer.
void feedOnce(int portion) {
  int openMs = MEDIUM_OPEN_MS;
  if (portion == 1) openMs = SMALL_OPEN_MS;
  if (portion == 3) openMs = LARGE_OPEN_MS;
  movePlate(OPEN_ANGLE);
  delay(openMs);
  movePlate(CLOSED_ANGLE);
}

// ---------- Food level helpers ----------

// Reads one byte from a register of the IR sensor. Returns -1 if it does not answer.
int readIrRegister(uint8_t reg) {
  Wire.beginTransmission(IR_ADDRESS);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  Wire.requestFrom(IR_ADDRESS, (uint8_t)1);
  return Wire.available() ? Wire.read() : -1;
}

// One distance reading in cm, or -1 if the sensor does not answer.
float readDistanceCm() {
  int shift = readIrRegister(0x35);     // the sensor's range setting
  int high = readIrRegister(0x5E);      // distance, top 8 bits
  int low = readIrRegister(0x5F);       // distance, bottom 4 bits
  if (shift < 0 || high < 0 || low < 0) return -1;
  int raw = (high << 4) | (low & 0x0F); // 12-bit value
  return raw / 16.0 / (1 << shift);     // formula from the datasheet
}

// 7 readings, ignores "no reading" (60 cm or more), returns the middle one. -1 if none.
float readDistanceStable() {
  float good[7];
  int count = 0;
  for (int i = 0; i < 7; i++) {
    float cm = readDistanceCm();
    if (cm > 0 && cm < 60) good[count++] = cm;   // 64 cm means "nothing seen"
    delay(20);
  }
  if (count == 0) return -1;
  for (int i = 0; i < count; i++)                 // sort, then pick the middle one
    for (int j = i + 1; j < count; j++)
      if (good[j] < good[i]) { float t = good[i]; good[i] = good[j]; good[j] = t; }
  return good[count / 2];
}

// Food left in %, from 0 (empty) to 100 (full). -1 if there was no reading.
int foodLevelPercent(float cm) {
  if (cm < 0) return -1;
  float percent = (EMPTY_CM - cm) / (EMPTY_CM - FULL_CM) * 100.0;
  if (percent < 0) percent = 0;         // a bit below empty: still 0%
  if (percent > 100) percent = 100;     // a bit above the fill line: still 100%
  return (int)(percent + 0.5);
}

// Measures the food and keeps the result in foodLevel.
void measureFoodLevel() {
  int level = foodLevelPercent(readDistanceStable());
  if (level >= 0) foodLevel = level;    // keep the last good value if this reading failed
}

// Starts a new day once 24 hours have passed.
// Only used until the feeder knows the real time. After that, the day starts at midnight
// (see checkClock).
void startNewDayIfNeeded() {
  if (clockSet) return;
  if (millis() - dayStartTime >= ONE_DAY_MS) {
    dayStartTime = millis();
    feedsToday = 0;
  }
}

// Checks the protection rules, then feeds if it is allowed.
// Returns the result: "OK FED <n>", "ERR COOLDOWN" or "ERR DAILY LIMIT".
// useCooldown is false for the scheduled meal: it must not be skipped
// just because someone tapped Feed Now a second before.
String tryToFeed(int portion, bool useCooldown) {
  startNewDayIfNeeded();

  // Rule 1: too soon after the last feed
  if (useCooldown && hasFedBefore && millis() - lastFeedTime < COOLDOWN_MS) {
    return "ERR COOLDOWN";
  }

  // Rule 2: already fed the maximum today (the scheduled meal counts too)
  if (feedsToday >= dailyLimit) {
    return "ERR DAILY LIMIT";
  }

  feedOnce(portion);
  lastFeedTime = millis();
  hasFedBefore = true;
  feedsToday++;
  return "OK FED " + String(feedsToday);
}

// Saves the schedule in flash and in the variables.
// The app sends the schedule every time it connects, so most of the time nothing changed:
// then nothing is written, and today's meal is not given a second time.
void saveSchedule(int hour, int minute, int portion) {
  bool sameTime = hasSchedule && hour == scheduleHour && minute == scheduleMinute;
  if (sameTime && portion == schedulePortion) return;   // nothing changed
  if (!sameTime) scheduleDoneToday = false;              // a new time can still happen today

  hasSchedule = true;
  scheduleHour = hour;
  scheduleMinute = minute;
  schedulePortion = portion;

  storage.putBool("has", true);
  storage.putInt("hour", hour);
  storage.putInt("minute", minute);
  storage.putInt("portion", portion);
}

// Loads the schedule saved before the last restart (if any)
void loadSchedule() {
  hasSchedule = storage.getBool("has", false);
  scheduleHour = storage.getInt("hour", 0);
  scheduleMinute = storage.getInt("minute", 0);
  schedulePortion = storage.getInt("portion", 2);
  dailyLimit = storage.getInt("limit", MAX_FEEDS_PER_DAY);
}

// FEED or FEED,<portion>
void handleFeed(String msg) {
  int portion = 2;                         // plain FEED = medium
  if (msg != "FEED") {
    if (sscanf(msg.c_str(), "FEED,%d", &portion) != 1 || portion < 1 || portion > 3) {
      reply("ERR BAD FEED");
      return;
    }
  }
  reply(tryToFeed(portion, true));
}

// SCHEDULE,HH:MM,<portion>   for example SCHEDULE,07:30,2
void handleSchedule(String msg) {
  int hour, minute, portion;
  if (sscanf(msg.c_str(), "SCHEDULE,%d:%d,%d", &hour, &minute, &portion) != 3
      || hour < 0 || hour > 23 || minute < 0 || minute > 59
      || portion < 1 || portion > 3) {
    reply("ERR BAD SCHEDULE");
    return;
  }
  saveSchedule(hour, minute, portion);
  reply("OK SCHEDULE " + twoDigits(hour) + ":" + twoDigits(minute) + " " + String(portion));
}

// TIME,HH:MM:SS   for example TIME,21:45:00
void handleTime(String msg) {
  int hour, minute, second;
  if (sscanf(msg.c_str(), "TIME,%d:%d:%d", &hour, &minute, &second) != 3
      || hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
    reply("ERR BAD TIME");
    return;
  }
  secondsAtSync = hour * 3600L + minute * 60L + second;
  millisAtSync = millis();
  lastSecondsOfDay = secondsAtSync;
  clockSet = true;
  reply("OK TIME");
}

// Saves the most meals per day (1 to 6). Only writes to flash when it changed.
void saveLimit(int limit) {
  if (limit != dailyLimit) {
    dailyLimit = limit;
    storage.putInt("limit", limit);
  }
}

// LIMIT,<n>   for example LIMIT,3 (UI-8.3: meals per day from the app's Settings)
void handleLimit(String msg) {
  int limit;
  if (sscanf(msg.c_str(), "LIMIT,%d", &limit) != 1 || limit < 1 || limit > MAX_FEEDS_PER_DAY) {
    reply("ERR BAD LIMIT");
    return;
  }
  saveLimit(limit);               // the app sends it on every connection
  reply("OK LIMIT " + String(limit));
}

// STATUS: shows what the feeder knows, to check that the other commands worked
void handleStatus() {
  String text = "STATUS";
  if (hasSchedule) {
    text += " schedule=" + twoDigits(scheduleHour) + ":" + twoDigits(scheduleMinute)
          + " portion=" + String(schedulePortion);
  } else {
    text += " schedule=none";
  }
  if (clockSet) {
    long now = secondsOfDayNow();
    text += " time=" + twoDigits(now / 3600) + ":" + twoDigits((now / 60) % 60);
  } else {
    text += " time=unknown";
  }
  text += " feedsToday=" + String(feedsToday) + " limit=" + String(dailyLimit);
  text += " food=" + (foodLevel >= 0 ? String(foodLevel) + "%" : String("unknown"));
  reply(text);
}

// LEVEL: food left right now, in %
void handleLevel() {
  measureFoodLevel();
  if (foodLevel < 0) reply("ERR NO LEVEL");
  else reply("OK LEVEL " + String(foodLevel));
}

// Called once per second. Starts a new day at midnight, and gives the
// scheduled meal when the clock reaches the scheduled time.
void checkClock() {
  if (!clockSet) return;
  long now = secondsOfDayNow();

  // Midnight: the time jumped back from about 23:59 to about 00:00
  if (now + 43200 < lastSecondsOfDay) {
    feedsToday = 0;
    scheduleDoneToday = false;
  }
  lastSecondsOfDay = now;

  // The scheduled minute has come, and today's meal was not given yet
  long scheduledSecond = scheduleHour * 3600L + scheduleMinute * 60L;
  bool inScheduledMinute = now >= scheduledSecond && now < scheduledSecond + 60;
  if (hasSchedule && !scheduleDoneToday && inScheduledMinute) {
    scheduleDoneToday = true;     // only once, even if it fails
    String result = tryToFeed(schedulePortion, false);
    reply("SCHEDULED " + result);
    reportScheduledMeal(result);  // COM-3.3: saves the meal in Firebase (firebase_link.ino)
  }
}

// Decides what to do with one full message (from Bluetooth or the Serial Monitor).
// Commands from Firebase are handled in firebase_link.ino, with the same feeding rules.
void handleCommand(String msg) {
  msg.toUpperCase();

  if (msg.startsWith("FEED")) {
    handleFeed(msg);
  } else if (msg.startsWith("SCHEDULE,")) {
    handleSchedule(msg);
  } else if (msg.startsWith("TIME,")) {
    handleTime(msg);
  } else if (msg.startsWith("LIMIT,")) {
    handleLimit(msg);
  } else if (msg == "LEVEL") {
    handleLevel();
  } else if (msg == "STATUS") {
    handleStatus();
  } else {
    reply("ERR UNKNOWN " + msg);
  }
}

// Starts Bluetooth (the backup when there is no Wi-Fi)
void startBluetooth() {
  if (bluetoothOn) return;
  bluetoothOn = SerialBT.begin("PetFeeder_ESP32");
  Serial.println(bluetoothOn ? "Bluetooth started! Waiting for messages..." : "Bluetooth failed to start!");
}

// Reads letters from one source (Bluetooth or USB).
// When a full line has arrived, it calls handleCommand().
void checkForMessage(Stream &source, String &message) {
  while (source.available()) {
    char letter = source.read();

    if (letter == '\n') {
      message.trim();
      if (message.length() > 0) {
        handleCommand(message);
      }
      message = "";                  // empty it for the next message
    } else {
      message += letter;             // add the letter to the message
    }
  }
}

void setup() {
  Serial.begin(115200);

  storage.begin("feeder", false);    // "feeder" is the name of our storage space
  loadSchedule();

  // Servo plate: start closed (Maria's settings)
  plate.setPeriodHertz(50);
  plate.attach(SERVO_PIN, 500, 2500);   // MG996R pulse range
  plate.write(CLOSED_ANGLE);

  // IR food level sensor
  Wire.begin(21, 22);                   // SDA, SCL
  measureFoodLevel();

#if USE_WIFI
  // COM-3.3: Wi-Fi first. Bluetooth and a secure Wi-Fi connection do not fit in memory
  // together, so Bluetooth only starts if Wi-Fi does not work (see firebase_link.ino).
  firebaseSetup();
#else
  startBluetooth();
#endif
}

void loop() {
  if (bluetoothOn) checkForMessage(SerialBT, btMessage);   // messages from the phone
  checkForMessage(Serial, usbMessage);    // messages typed in the Serial Monitor

  if (millis() - lastClockCheck >= 1000) { // once per second
    lastClockCheck = millis();
    checkClock();
  }

  if (millis() - lastLevelCheck >= LEVEL_EVERY_MS) { // food level every 5 seconds
    lastLevelCheck = millis();
    measureFoodLevel();
  }

  firebaseLoop();    // COM-3.3: new commands, settings and status (does nothing without Wi-Fi)
}
