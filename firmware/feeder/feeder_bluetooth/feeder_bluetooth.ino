/*
  FD-2.2 - Send manual feed command (feeder side)
  FD-1.3 - Send feeding command to the feeder (schedule)
  Isabel Al-Haddad

  Commands (one per line, from the phone or the Serial Monitor):
    FEED                 feed one medium portion
    FEED,<portion>       feed one portion: 1 = small, 2 = medium, 3 = large
    SCHEDULE,HH:MM,<p>   save the daily feeding time and portion
    TIME,HH:MM:SS        tell the feeder what time it is
    STATUS               show the saved schedule, the time and today's feeds

  At the scheduled time, the feeder feeds by itself and sends
    SCHEDULED OK FED <n>   or   SCHEDULED ERR DAILY LIMIT

  Overfeeding protection:
  - A second feed within 3 seconds is ignored (double taps).
  - At most 6 feeds per day.
*/

#include <BluetoothSerial.h>
#include <Preferences.h>

BluetoothSerial SerialBT;
Preferences storage;         // saves the schedule in flash, so it survives a restart
String btMessage = "";
String usbMessage = "";

// Overfeeding protection settings
const unsigned long COOLDOWN_MS = 3000;            // 3 seconds between feeds
const int MAX_FEEDS_PER_DAY = 6;
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

// Sends a reply to both the phone and the Serial Monitor
void reply(String text) {
  Serial.println(text);
  if (SerialBT.hasClient()) {
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

// Gives one portion of food (1 = small, 2 = medium, 3 = large).
// The motor code (FD-1.4) goes inside this function.
void feedOnce(int portion) {
  Serial.println("(motor would give portion " + String(portion) + " here)");
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
  if (feedsToday >= MAX_FEEDS_PER_DAY) {
    return "ERR DAILY LIMIT";
  }

  feedOnce(portion);
  lastFeedTime = millis();
  hasFedBefore = true;
  feedsToday++;
  return "OK FED " + String(feedsToday);
}

// Saves the schedule in flash and in the variables
void saveSchedule(int hour, int minute, int portion) {
  hasSchedule = true;
  scheduleDoneToday = false;      // a new schedule can still happen today
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
  text += " feedsToday=" + String(feedsToday);
  reply(text);
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
    reply("SCHEDULED " + tryToFeed(schedulePortion, false));
  }
}

// Decides what to do with one full message.
// This part stays the same if we add Firebase later.
void handleCommand(String msg) {
  msg.toUpperCase();

  if (msg.startsWith("FEED")) {
    handleFeed(msg);
  } else if (msg.startsWith("SCHEDULE,")) {
    handleSchedule(msg);
  } else if (msg.startsWith("TIME,")) {
    handleTime(msg);
  } else if (msg == "STATUS") {
    handleStatus();
  } else {
    reply("ERR UNKNOWN " + msg);
  }
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

  if (SerialBT.begin("PetFeeder_ESP32")) {
    Serial.println("Bluetooth started! Waiting for messages...");
  } else {
    Serial.println("Bluetooth failed to start!");
  }
}

void loop() {
  checkForMessage(SerialBT, btMessage);   // messages from the phone
  checkForMessage(Serial, usbMessage);    // messages typed in the Serial Monitor

  if (millis() - lastClockCheck >= 1000) { // once per second
    lastClockCheck = millis();
    checkClock();
  }
}
