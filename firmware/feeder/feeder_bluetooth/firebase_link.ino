/*
  COM-3.3: the feeder reads its commands from Firebase over Wi-Fi.
  FD-1.7:  the feeder gets the time from the internet.
  Isabel Al-Haddad

  This file is a second tab of feeder_bluetooth (the Arduino IDE joins the tabs).
  It only runs when secrets.h exists (see secrets.example.h). Bluetooth keeps working
  as the backup, with the same feeding rules (tryToFeed in feeder_bluetooth.ino).

  What it does once signed in to Firebase (as the pet owner, like the app):
    every 3 s   reads owners/<you>/feeder/commands. For each new command:
                  FEED_NOW      -> gives the portion, then marks the command "done" or "failed"
                                   and its meal in feeder/feedings "fed" or "failed"
                  SET_SCHEDULE  -> saves the new daily time and portion
    every 60 s  reads the meal limit (settings/mealLimit) and the schedule (feeder/schedule)
    every 30 s  writes feeder/status: connected, lastSeen, foodPercent, feedsToday, mealLimit
    scheduled meal: marks the planned meal "fed" or "failed" (or adds a new one)

  A command is only done once: the newest command already handled is kept in flash, so a
  restart never gives the same meal twice. Commands older than 10 minutes are not fed
  (for example the feeder was unplugged all day): they are marked "expired".
*/

#if USE_WIFI
// The Wi-Fi libraries are included at the top of feeder_bluetooth.ino

const char *FEEDER_TIME_ZONE = "EST5EDT,M3.2.0,M11.1.0";   // Montreal / Toronto, with daylight saving

const unsigned long COMMANDS_EVERY_MS = 3000;
const unsigned long SETTINGS_EVERY_MS = 60000;
const unsigned long STATUS_EVERY_MS = 30000;
const unsigned long WIFI_RETRY_MS = 30000;
const unsigned long LOGIN_RETRY_MS = 30000;
const unsigned long LOGIN_EVERY_MS = 50UL * 60 * 1000;     // a sign-in lasts 1 hour
const unsigned long TIME_RETRY_MS = 10000;
const unsigned long TIME_EVERY_MS = 60UL * 60 * 1000;      // check the internet time every hour
const long long COMMAND_TOO_OLD_MS = 10LL * 60 * 1000;     // 10 minutes

String fbToken = "";
String fbUid = "";                    // the owner's account ID (owners/<fbUid>/...)
String lastCommandKey = "";           // newest command already handled (also in flash)
String nextScheduledFeedingId = "";   // the planned meal the app made for the next scheduled feed
bool timeFromInternet = false;

unsigned long lastWifiTry = 0, lastLoginMs = 0, lastLoginTry = 0;
unsigned long lastTimeTry = 0, lastTimeSync = 0;
unsigned long lastCommandsMs = 0, lastSettingsMs = 0, lastStatusMs = 0;

// Meal results waiting to be sent (when Wi-Fi was down at that moment)
const int WAITING_MAX = 6;
String waitingMethod[WAITING_MAX];
String waitingPath[WAITING_MAX];
String waitingBody[WAITING_MAX];
int waitingCount = 0;

String ownerPath(const String &rest) {
  return "owners/" + fbUid + "/" + rest;
}

// Sends one request to the Realtime Database. Returns the HTTP code (200 = OK).
int firebaseRequest(const char *method, const String &path, const String &query,
                    const String &body, String &response) {
  WiFiClientSecure client;
  client.setInsecure();               // same as the collar
  HTTPClient http;
  http.setReuse(false);
  http.setTimeout(10000);

  String url = String(FIREBASE_DATABASE_URL);
  if (url.endsWith("/")) url.remove(url.length() - 1);
  url += "/" + path + ".json?auth=" + fbToken + query;   // never print this URL: it has the token

  if (!http.begin(client, url)) return -1;
  int code;
  if (strcmp(method, "GET") == 0) {
    code = http.GET();
  } else {
    http.addHeader("Content-Type", "application/json");
    code = http.sendRequest(method, body);
  }
  response = code > 0 ? http.getString() : "";
  http.end();
  client.stop();

  if (code != 200) Serial.printf("Firebase %s %s failed. HTTP code: %d\n", method, path.c_str(), code);
  if (code == 401) fbToken = "";      // sign in again next time
  return code;
}

int firebaseWrite(const char *method, const String &path, const String &body) {
  String ignored;
  return firebaseRequest(method, path, "", body, ignored);
}

// Signs in with the owner's email and password (the same account as the app)
bool firebaseLogin() {
  lastLoginTry = millis();
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  http.setReuse(false);
  http.setTimeout(15000);

  String url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=";
  url += FIREBASE_API_KEY;
  if (!http.begin(client, url)) return false;
  http.addHeader("Content-Type", "application/json");

  JsonDocument login;
  login["email"] = FIREBASE_EMAIL;
  login["password"] = FIREBASE_PASSWORD;
  login["returnSecureToken"] = true;
  String body;
  serializeJson(login, body);

  int code = http.POST(body);
  String response = http.getString();
  http.end();
  client.stop();

  JsonDocument result;
  if (code != 200 || deserializeJson(result, response)) {
    Serial.printf("Firebase sign-in failed. HTTP code: %d\n", code);
    if (code < 0)
      Serial.printf("  Could not open a secure connection. Free memory: %u bytes (biggest block %u)\n",
                    ESP.getFreeHeap(), ESP.getMaxAllocHeap());
    else if (code == 400)
      Serial.println("  Check FIREBASE_EMAIL, FIREBASE_PASSWORD and FIREBASE_API_KEY in secrets.h");
    else if (code == 403)
      Serial.println("  The API key is not allowed here: use the Web API key from the Firebase console");
    if (code > 0) Serial.println("  " + response.substring(0, 200));
    fbToken = "";
    return false;
  }
  fbToken = result["idToken"].as<String>();
  fbUid = result["localId"].as<String>();
  lastLoginMs = millis();
  Serial.println("Signed in to Firebase!");
  return fbToken.length() > 0 && fbUid.length() > 0;
}

// FD-1.7: sets the feeder's clock from the internet time
void syncTimeFromInternet() {
  lastTimeTry = millis();
  struct tm now;
  if (!getLocalTime(&now, 2000)) return;
  secondsAtSync = now.tm_hour * 3600L + now.tm_min * 60L + now.tm_sec;
  millisAtSync = millis();
  if (!clockSet) lastSecondsOfDay = secondsAtSync;
  clockSet = true;
  timeFromInternet = true;
  lastTimeSync = millis();
  Serial.println("Time from the internet: " + twoDigits(now.tm_hour) + ":" + twoDigits(now.tm_min));
}

// Now in ms since 1970 (what the app uses), or 0 if the feeder does not know the date
long long epochMsNow() {
  if (!timeFromInternet) return 0;
  return (long long)time(nullptr) * 1000LL;
}

// Sends a meal result now, or keeps it until Wi-Fi is back
void sendOrKeep(const char *method, const String &path, const String &body) {
  if (WiFi.status() == WL_CONNECTED && fbToken.length() > 0
      && firebaseWrite(method, path, body) == 200) return;
  if (waitingCount < WAITING_MAX) {
    waitingMethod[waitingCount] = method;
    waitingPath[waitingCount] = path;
    waitingBody[waitingCount] = body;
    waitingCount++;
  }
}

void sendWaiting() {
  while (waitingCount > 0) {
    if (firebaseWrite(waitingMethod[0].c_str(), waitingPath[0], waitingBody[0]) != 200) return;
    for (int i = 1; i < waitingCount; i++) {
      waitingMethod[i - 1] = waitingMethod[i];
      waitingPath[i - 1] = waitingPath[i];
      waitingBody[i - 1] = waitingBody[i];
    }
    waitingCount--;
  }
}

// {"status":"fed","result":"OK FED 2","updatedAt":<server time>}
String mealResultJson(const String &result) {
  JsonDocument meal;
  meal["status"] = result.startsWith("OK") ? "fed" : "failed";
  meal["result"] = result;
  meal["updatedAt"][".sv"] = "timestamp";
  String json;
  serializeJson(meal, json);
  return json;
}

void markCommand(const String &key, const char *status, const String &result) {
  JsonDocument done;
  done["status"] = status;
  done["result"] = result;
  done["doneAt"][".sv"] = "timestamp";
  String json;
  serializeJson(done, json);
  firebaseWrite("PATCH", ownerPath("feeder/commands/" + key), json);
}

// Handles one new command from the app
void runCommand(const String &key, JsonObject command) {
  String type = command["type"] | "";
  String feedingId = command["feedingId"] | "";
  long long createdAt = command["createdAt"] | 0LL;
  long long now = epochMsNow();

  // Too old: the feeder was off when it was sent. Do not feed hours later.
  if (now > 0 && createdAt > 0 && now - createdAt > COMMAND_TOO_OLD_MS) {
    markCommand(key, "expired", "Too old: the feeder was offline");
    if (type == "FEED_NOW" && feedingId.length() > 0)
      firebaseWrite("PATCH", ownerPath("feeder/feedings/" + feedingId),
                    mealResultJson("ERR OFFLINE"));
    return;
  }

  if (type == "FEED_NOW") {
    int portion = command["portion"] | 2;
    if (portion < 1 || portion > 3) portion = 2;
    String result = tryToFeed(portion, true);     // same rules as Bluetooth
    reply("FIREBASE " + result);
    markCommand(key, result.startsWith("OK") ? "done" : "failed", result);
    if (feedingId.length() > 0)
      sendOrKeep("PATCH", ownerPath("feeder/feedings/" + feedingId), mealResultJson(result));
    lastStatusMs = 0;                               // send the new status right away
  } else if (type == "SET_SCHEDULE") {
    int hour = command["hour"] | -1;
    int minute = command["minute"] | -1;
    int portion = command["portion"] | 2;
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || portion < 1 || portion > 3) {
      markCommand(key, "failed", "ERR BAD SCHEDULE");
      return;
    }
    saveSchedule(hour, minute, portion);
    if (feedingId.length() > 0) {
      nextScheduledFeedingId = feedingId;
      storage.putString("nextFeed", feedingId);
    }
    String result = "OK SCHEDULE " + twoDigits(hour) + ":" + twoDigits(minute) + " " + String(portion);
    reply("FIREBASE " + result);
    markCommand(key, "done", result);
  } else {
    markCommand(key, "failed", "ERR UNKNOWN " + type);
  }
}

// Reads the 10 newest commands and runs the new ones, oldest first
void checkCommands() {
  lastCommandsMs = millis();
  String response;
  if (firebaseRequest("GET", ownerPath("feeder/commands"), "&orderBy=%22%24key%22&limitToLast=10",
                      "", response) != 200) return;
  JsonDocument doc;
  if (deserializeJson(doc, response) || !doc.is<JsonObject>()) return;   // "null" = no commands
  JsonObject all = doc.as<JsonObject>();

  // Firebase does not send them in order: sort the keys (they are in time order)
  String keys[10];
  int count = 0;
  for (JsonPair pair : all) {
    if (count < 10) keys[count++] = pair.key().c_str();
  }
  for (int i = 0; i < count; i++)
    for (int j = i + 1; j < count; j++)
      if (keys[j] < keys[i]) { String t = keys[i]; keys[i] = keys[j]; keys[j] = t; }

  for (int i = 0; i < count; i++) {
    if (keys[i] <= lastCommandKey) continue;             // already handled
    JsonObject command = all[keys[i]];
    String status = command["status"] | "";
    // Saved BEFORE running it: even if the feeder restarts now, it never feeds twice
    lastCommandKey = keys[i];
    storage.putString("lastCmd", lastCommandKey);
    if (status == "pending") runCommand(keys[i], command);
  }
}

// The meal limit from the app's Settings, and the schedule (in case a command was missed)
void checkSettings() {
  lastSettingsMs = millis();
  String response;
  if (firebaseRequest("GET", ownerPath("settings/mealLimit"), "", "", response) == 200) {
    int limit = response.toInt();
    if (limit >= 1 && limit <= MAX_FEEDS_PER_DAY) saveLimit(limit);
  }
  if (firebaseRequest("GET", ownerPath("feeder/schedule"), "", "", response) == 200) {
    JsonDocument schedule;
    if (!deserializeJson(schedule, response) && schedule.is<JsonObject>()
        && (schedule["enabled"] | true)) {
      int hour = schedule["hour"] | -1;
      int minute = schedule["minute"] | -1;
      int portion = schedule["portion"] | 2;
      if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && portion >= 1 && portion <= 3) {
        bool changed = !hasSchedule || hour != scheduleHour || minute != scheduleMinute
                       || portion != schedulePortion;
        saveSchedule(hour, minute, portion);
        String planned = schedule["nextFeedingId"] | "";
        if (changed && planned.length() > 0) {
          nextScheduledFeedingId = planned;
          storage.putString("nextFeed", planned);
        }
      }
    }
  }
}

// feeder/status: what the app shows (online, food left, meals today)
void sendStatus() {
  lastStatusMs = millis();
  JsonDocument status;
  status["connected"] = true;
  status["lastSeen"][".sv"] = "timestamp";
  status["feedsToday"] = feedsToday;
  status["mealLimit"] = dailyLimit;
  if (foodLevel >= 0) status["foodPercent"] = foodLevel;
  if (hasSchedule) status["schedule"] = twoDigits(scheduleHour) + ":" + twoDigits(scheduleMinute);
  status["timeFromInternet"] = timeFromInternet;
  String json;
  serializeJson(status, json);
  firebaseWrite("PATCH", ownerPath("feeder/status"), json);
}
#endif

// ---------- Called from feeder_bluetooth.ino ----------

void firebaseSetup() {
#if USE_WIFI
  lastCommandKey = storage.getString("lastCmd", "");
  nextScheduledFeedingId = storage.getString("nextFeed", "");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  configTzTime(FEEDER_TIME_ZONE, "pool.ntp.org", "time.google.com");
  Serial.println("Connecting to Wi-Fi...");
  lastWifiTry = millis();
#endif
}

void firebaseLoop() {
#if USE_WIFI
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastWifiTry >= WIFI_RETRY_MS) {   // try again every 30 s
      lastWifiTry = millis();
      WiFi.disconnect();
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    }
    return;
  }

  // FD-1.7: the time, every 10 s until it works, then every hour
  if ((!timeFromInternet && millis() - lastTimeTry >= TIME_RETRY_MS)
      || (timeFromInternet && millis() - lastTimeSync >= TIME_EVERY_MS)) {
    syncTimeFromInternet();
  }

  if (fbToken.length() == 0 || millis() - lastLoginMs >= LOGIN_EVERY_MS) {
    if (lastLoginTry != 0 && millis() - lastLoginTry < LOGIN_RETRY_MS) return;
    if (!firebaseLogin()) return;
    checkSettings();                                  // right after signing in
  }

  sendWaiting();
  // Commands only once the date is known: without it, old commands could not be told apart
  // from new ones, and a Feed Now from yesterday could be given now.
  if (timeFromInternet && millis() - lastCommandsMs >= COMMANDS_EVERY_MS) checkCommands();
  if (millis() - lastSettingsMs >= SETTINGS_EVERY_MS) checkSettings();
  if (millis() - lastStatusMs >= STATUS_EVERY_MS) sendStatus();
#endif
}

// The scheduled meal was given (or not): save it in feeder/feedings
void reportScheduledMeal(String result) {
#if USE_WIFI
  if (fbUid.length() == 0) return;                  // never signed in: nothing to update
  if (nextScheduledFeedingId.length() > 0) {
    // The planned meal the app made when the schedule was saved
    sendOrKeep("PATCH", ownerPath("feeder/feedings/" + nextScheduledFeedingId), mealResultJson(result));
    nextScheduledFeedingId = "";
    storage.putString("nextFeed", "");
  } else {
    // The next days: a new meal
    JsonDocument meal;
    long long now = epochMsNow();
    if (now > 0) meal["time"] = now;
    else meal["time"][".sv"] = "timestamp";
    meal["portion"] = schedulePortion;
    meal["source"] = "scheduled";
    meal["status"] = result.startsWith("OK") ? "fed" : "failed";
    meal["result"] = result;
    String json;
    serializeJson(meal, json);
    sendOrKeep("POST", ownerPath("feeder/feedings"), json);
  }
  lastStatusMs = 0;
#endif
}
