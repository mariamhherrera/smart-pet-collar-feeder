
/*
  Smart Pet Collar - Adafruit Feather ESP32-S2 + MPU-6500 motion sensor

  What it does:
    1. Reads the motion sensor 50 times per second (in its own task, so it
       never stops, even while Wi-Fi is slow or down).
    2. Every 4 seconds: counts the steps and decides resting / walking /
       running (COL-2, see activity.h).
    3. Adds those 4 seconds to today's total for that activity (COL-2.3).
       Totals are kept in flash memory, so a restart does not lose them.
    4. Every 5 seconds (when on Wi-Fi): sends the newest reading, the activity
       and today's minutes to Firebase:  collars/<collar ID>/latest
    5. Every minute: sends today's totals to  collarDays/<collar ID>/<date>
       for the History graphs.

  Pairing (COM-4):
    Every collar has its own ID, like PC-3C71F2 (printed on Serial at start).
    The collar only writes under its ID. The app links the ID to the pet,
    so the collar does not need to know who owns it.

  Offline:
    Without Wi-Fi the collar keeps counting. It retries Wi-Fi every 15 s.
    Days that ended while offline are kept (up to 7) and sent when it is back.

  Needs secrets.h in this folder (copy secrets.example.h).
*/

#include <Wire.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <TinyGPSPlus.h>
#include <Preferences.h>
#include <time.h>
#include "secrets.h"
#include "activity.h"

// ---------- Settings ----------

const uint8_t MPU_ADDR = 0x68;
const uint8_t BATTERY_GAUGE_ADDR = 0x36;

// NEO-7M GPS configuration
const int GPS_RX_PIN = 38;
const int GPS_TX_PIN = 39;
const uint32_t GPS_BAUD = 9600;
const uint32_t GPS_FRESH_MS = 5000;

const uint32_t SAMPLE_INTERVAL_MS = 1000 / SAMPLE_RATE_HZ;
const uint32_t LATEST_INTERVAL_MS = 5000;
const uint32_t DAY_INTERVAL_MS = 60000;
const uint32_t WIFI_RETRY_MS = 15000;
const uint32_t LOGIN_RETRY_MS = 30000;
const uint32_t AUTH_INTERVAL_MS = 3000000;

const char *TIME_ZONE = "EST5EDT,M3.2.0,M11.1.0";

const int MAX_PENDING_DAYS = 7;

// ---------- Shared sensor data ----------

struct MotionReading {
  float ax, ay, az;
  float gx, gy, gz;
};

struct DayTotals {
  char date[11];
  uint32_t seconds[3];
};

MotionReading latestMotion;

bool haveReading = false;
bool haveActivity = false;

Activity currentActivity = RESTING;
float currentPace = 0;

uint32_t todaySeconds[3] = {0, 0, 0};

int batteryPercent = -1;
float batteryVoltage = 0;

// ---------- GPS data ----------

TinyGPSPlus gpsParser;
HardwareSerial gpsUart(1);

struct GpsReading {
  bool everFixed = false;
  double latitude = 0;
  double longitude = 0;
  uint32_t lastFixMs = 0;
  int satellites = -1;
  float hdop = -1;
  uint32_t nmeaSentences = 0;
};

GpsReading latestGps;

// ---------- Shared data lock ----------

portMUX_TYPE dataLock = portMUX_INITIALIZER_UNLOCKED;

void lockData() {
  portENTER_CRITICAL(&dataLock);
}

void unlockData() {
  portEXIT_CRITICAL(&dataLock);
}

// ---------- Main variables ----------

String collarId;
String currentDate;

DayTotals pendingDays[MAX_PENDING_DAYS];
int pendingCount = 0;

String firebaseToken;

Preferences memory;
StepCounter steps;

uint32_t lastLatestMs = 0;
uint32_t lastDayMs = 0;
uint32_t lastWifiTryMs = 0;
uint32_t lastLoginTryMs = 0;
uint32_t lastAuthMs = 0;

bool timeStarted = false;
bool daysDue = false;

// ---------- Collar ID ----------

String makeCollarId() {
  uint64_t mac = ESP.getEfuseMac();

  char id[12];

  snprintf(
    id,
    sizeof(id),
    "PC-%02X%02X%02X",
    (unsigned)((mac >> 24) & 0xFF),
    (unsigned)((mac >> 32) & 0xFF),
    (unsigned)((mac >> 40) & 0xFF)
  );

  return String(id);
}

// ---------- Motion sensor ----------

bool writeRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);

  return Wire.endTransmission() == 0;
}

int readSensorID() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x75);

  if (Wire.endTransmission(false) != 0) {
    return -1;
  }

  if (Wire.requestFrom(MPU_ADDR, (uint8_t)1) != 1) {
    return -1;
  }

  return Wire.read();
}

bool readWord(uint8_t address, uint8_t reg, uint16_t &value) {
  Wire.beginTransmission(address);
  Wire.write(reg);

  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  if (Wire.requestFrom(address, (uint8_t)2) != 2) {
    return false;
  }

  value = (Wire.read() << 8) | Wire.read();

  return true;
}

// ---------- Battery ----------

void readBattery(int &percent, float &voltage) {
  uint16_t rawVoltage;
  uint16_t rawPercent;

  if (!readWord(BATTERY_GAUGE_ADDR, 0x02, rawVoltage) ||
      !readWord(BATTERY_GAUGE_ADDR, 0x04, rawPercent)) {

    percent = -1;
    return;
  }

  voltage = rawVoltage * 78.125e-6f;
  percent = constrain(rawPercent >> 8, 0, 100);
}

// ---------- Initialize motion sensor ----------

bool initializeSensor() {
  pinMode(PIN_I2C_POWER, OUTPUT);
  digitalWrite(PIN_I2C_POWER, HIGH);

  delay(50);

  Wire.begin(SDA, SCL);
  Wire.setClock(100000);

  int sensorID = readSensorID();

  Serial.print("Sensor WHO_AM_I: 0x");
  Serial.println(sensorID, HEX);

  if (sensorID != 0x70) {
    Serial.println("Motion sensor not recognized!");
    return false;
  }

  if (!writeRegister(0x6B, 0x00)) {
    return false;
  }

  delay(100);

  if (!writeRegister(0x1C, 0x00)) {
    return false;
  }

  if (!writeRegister(0x1B, 0x00)) {
    return false;
  }

  Serial.println("Motion sensor ready!");

  return true;
}

// ---------- Read motion ----------

bool readMotion(MotionReading &r) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);

  if (Wire.endTransmission(false) != 0) {
    return false;
  }

  if (Wire.requestFrom(MPU_ADDR, (uint8_t)14) != 14) {
    return false;
  }

  int16_t rawAX = (Wire.read() << 8) | Wire.read();
  int16_t rawAY = (Wire.read() << 8) | Wire.read();
  int16_t rawAZ = (Wire.read() << 8) | Wire.read();

  Wire.read();
  Wire.read();

  int16_t rawGX = (Wire.read() << 8) | Wire.read();
  int16_t rawGY = (Wire.read() << 8) | Wire.read();
  int16_t rawGZ = (Wire.read() << 8) | Wire.read();

  r.ax = rawAX / 16384.0f;
  r.ay = rawAY / 16384.0f;
  r.az = rawAZ / 16384.0f;

  r.gx = rawGX / 131.0f;
  r.gy = rawGY / 131.0f;
  r.gz = rawGZ / 131.0f;

  return true;
}

// ---------- Motion sensor task ----------

void sensorTask(void *unused) {
  TickType_t nextWake = xTaskGetTickCount();

  int readingsSinceBattery = 10000;

  while (true) {
    vTaskDelayUntil(
      &nextWake,
      pdMS_TO_TICKS(SAMPLE_INTERVAL_MS)
    );

    if (++readingsSinceBattery >= 10 * SAMPLE_RATE_HZ) {
      readingsSinceBattery = 0;

      int percent;
      float voltage;

      readBattery(percent, voltage);

      lockData();

      batteryPercent = percent;
      batteryVoltage = voltage;

      unlockData();
    }

    MotionReading r;

    if (!readMotion(r)) {
      continue;
    }

    float totalG = sqrtf(
      r.ax * r.ax +
      r.ay * r.ay +
      r.az * r.az
    );

    bool windowDone = steps.add(totalG);

    lockData();

    latestMotion = r;
    haveReading = true;

    if (windowDone) {
      currentActivity = steps.activity();
      currentPace = steps.pace();

      todaySeconds[currentActivity] += WINDOW_SECONDS;

      haveActivity = true;
    }

    unlockData();
  }
}

// ---------- GPS receiver task ----------

void gpsTask(void *unused) {
  uint32_t lastInfoMs = 0;

  for (;;) {
    while (gpsUart.available()) {
      gpsParser.encode(gpsUart.read());
    }

    if (gpsParser.location.isUpdated() &&
        gpsParser.location.isValid()) {

      double lat = gpsParser.location.lat();
      double lng = gpsParser.location.lng();

      lockData();

      latestGps.latitude = lat;
      latestGps.longitude = lng;
      latestGps.lastFixMs = millis();
      latestGps.everFixed = true;

      unlockData();
    }

    lockData();

    latestGps.satellites =
      gpsParser.satellites.isValid()
      ? (int)gpsParser.satellites.value()
      : -1;

    latestGps.hdop =
      gpsParser.hdop.isValid()
      ? gpsParser.hdop.hdop()
      : -1;

    latestGps.nmeaSentences = gpsParser.passedChecksum();

    unlockData();

    if (millis() - lastInfoMs >= 10000) {
      lastInfoMs = millis();

      GpsReading info;

      lockData();
      info = latestGps;
      unlockData();

      bool fresh =
        info.everFixed &&
        (uint32_t)(millis() - info.lastFixMs) < GPS_FRESH_MS;

      Serial.printf(
        "GPS: %s, satellites %d, NMEA %lu\n",
        fresh ? "VALID" : "NO FIX",
        info.satellites,
        (unsigned long)info.nmeaSentences
      );

      if (fresh) {
        Serial.printf(
          "GPS coordinates: %.6f, %.6f\n",
          info.latitude,
          info.longitude
        );
      }
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

// ---------- Clock and days ----------

String todayDate() {
  time_t now = time(nullptr);

  if (now < 1700000000) {
    return "";
  }

  struct tm local;

  localtime_r(&now, &local);

  char text[11];

  strftime(text, sizeof(text), "%Y-%m-%d", &local);

  return String(text);
}

// ---------- Save activity totals ----------

void saveToMemory() {
  uint32_t copy[3];

  lockData();
  memcpy(copy, todaySeconds, sizeof(copy));
  unlockData();

  memory.putString("date", currentDate);
  memory.putBytes("today", copy, sizeof(copy));
  memory.putInt("pendingCount", pendingCount);
  memory.putBytes("pending", pendingDays, sizeof(pendingDays));
}

void loadFromMemory() {
  currentDate = memory.getString("date", "");

  uint32_t copy[3] = {0, 0, 0};

  memory.getBytes("today", copy, sizeof(copy));

  memcpy(todaySeconds, copy, sizeof(copy));

  pendingCount = memory.getInt("pendingCount", 0);

  if (pendingCount < 0 || pendingCount > MAX_PENDING_DAYS) {
    pendingCount = 0;
  }

  memory.getBytes("pending", pendingDays, sizeof(pendingDays));

  Serial.printf(
    "Restored %s: resting %lu s, walking %lu s, running %lu s, %d unsent days\n",
    currentDate.isEmpty() ? "(no date)" : currentDate.c_str(),
    (unsigned long)copy[0],
    (unsigned long)copy[1],
    (unsigned long)copy[2],
    pendingCount
  );
}

// ---------- Pending days ----------

void addPendingDay(const DayTotals &day) {
  if (pendingCount == MAX_PENDING_DAYS) {
    for (int i = 1; i < MAX_PENDING_DAYS; i++) {
      pendingDays[i - 1] = pendingDays[i];
    }

    pendingCount--;
  }

  pendingDays[pendingCount++] = day;
}

void removeOldestPendingDay() {
  for (int i = 1; i < pendingCount; i++) {
    pendingDays[i - 1] = pendingDays[i];
  }

  pendingCount--;
}

// ---------- New day detection ----------

void checkNewDay() {
  String today = todayDate();

  if (today.isEmpty()) {
    return;
  }

  if (currentDate.isEmpty()) {
    currentDate = today;
    return;
  }

  if (today == currentDate) {
    return;
  }

  if (today < currentDate) {
    currentDate = today;
    saveToMemory();
    return;
  }

  DayTotals finished;

  strncpy(
    finished.date,
    currentDate.c_str(),
    sizeof(finished.date)
  );

  finished.date[10] = '\0';

  lockData();

  memcpy(
    finished.seconds,
    todaySeconds,
    sizeof(finished.seconds)
  );

  todaySeconds[0] = 0;
  todaySeconds[1] = 0;
  todaySeconds[2] = 0;

  unlockData();

  addPendingDay(finished);

  Serial.printf(
    "New day %s. %s will be sent to Firebase.\n",
    today.c_str(),
    finished.date
  );

  currentDate = today;

  saveToMemory();
}

// ---------- Wi-Fi ----------

void keepWiFiConnected() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }

  if (millis() - lastWifiTryMs < WIFI_RETRY_MS) {
    return;
  }

  lastWifiTryMs = millis();

  Serial.println("No Wi-Fi. Trying to connect...");

  WiFi.disconnect();
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

// ---------- Firebase HTTPS ----------

void secureClient(WiFiClientSecure &client) {
#if ESP_ARDUINO_VERSION_MAJOR >= 4
  client.useBuiltinCACertBundle();
#else
  client.setInsecure();
#endif
}

// ---------- Firebase database URL ----------

String databaseUrl(const String &path) {
  String url = String(FIREBASE_DATABASE_URL);

  if (url.endsWith("/")) {
    url.remove(url.length() - 1);
  }

  return url + "/" + path + ".json?auth=" + firebaseToken;
}

// ---------- Firebase authentication ----------

bool firebaseLogin() {
  Serial.println("Signing in to Firebase...");

  WiFiClientSecure client;
  secureClient(client);

  HTTPClient http;

  http.setReuse(false);
  http.setTimeout(15000);

  String url =
    "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=";

  url += FIREBASE_API_KEY;

  if (!http.begin(client, url)) {
    Serial.println("Firebase sign-in setup failed!");
    return false;
  }

  http.addHeader("Content-Type", "application/json");

  JsonDocument login;

  login["email"] = FIREBASE_TEST_EMAIL;
  login["password"] = FIREBASE_TEST_PASSWORD;
  login["returnSecureToken"] = true;

  String body;

  serializeJson(login, body);

  int code = http.POST(body);

  String response = http.getString();

  http.end();
  client.stop();

  if (code == 200) {
    JsonDocument result;

    if (!deserializeJson(result, response)) {
      firebaseToken = result["idToken"].as<String>();
    }
  }

  if (code != 200 || firebaseToken.isEmpty()) {
    Serial.printf(
      "Firebase sign-in failed. HTTP code: %d\n",
      code
    );

    firebaseToken = "";

    return false;
  }

  lastAuthMs = millis();

  Serial.println("Signed in to Firebase!");

  return true;
}

// ---------- Firebase request ----------

int sendToFirebase(
  const char *method,
  const String &path,
  const String &json
) {
  WiFiClientSecure client;
  secureClient(client);

  HTTPClient http;

  http.setReuse(false);
  http.setTimeout(15000);

  if (!http.begin(client, databaseUrl(path))) {
    return -1;
  }

  http.addHeader("Content-Type", "application/json");

  int code = http.sendRequest(method, json);

  if (code != 200) {
    Serial.printf(
      "Firebase %s %s failed. HTTP code: %d\n",
      method,
      path.c_str(),
      code
    );

    if (code > 0) {
      Serial.println(http.getString());
    }
  }

  http.end();
  client.stop();

  if (code == 401) {
    firebaseToken = "";
  }

  return code;
}

// ---------- Upload latest collar data ----------

void uploadLatest() {
  MotionReading r;

  bool reading;
  bool activityKnown;

  Activity activity;

  float pace;
  float voltage;

  int percent;

  uint32_t seconds[3];

  GpsReading gpsReading;

  lockData();

  r = latestMotion;
  reading = haveReading;
  activityKnown = haveActivity;
  activity = currentActivity;
  pace = currentPace;
  percent = batteryPercent;
  voltage = batteryVoltage;

  memcpy(seconds, todaySeconds, sizeof(seconds));

  gpsReading = latestGps;

  unlockData();

  if (!reading) {
    return;
  }

  JsonDocument data;

  JsonObject latest = data["latest"].to<JsonObject>();

  latest["collarId"] = collarId;
  latest["deviceTimeSeconds"] = millis() / 1000.0f;

  JsonObject accel =
    latest["accelerationG"].to<JsonObject>();

  accel["x"] = r.ax;
  accel["y"] = r.ay;
  accel["z"] = r.az;

  JsonObject gyro =
    latest["gyroscopeDegreesPerSecond"].to<JsonObject>();

  gyro["x"] = r.gx;
  gyro["y"] = r.gy;
  gyro["z"] = r.gz;

  if (percent >= 0) {
    latest["batteryVoltage"] = voltage;
    latest["batteryPercent"] = percent;
  }

  latest["wifiSignal"] = WiFi.RSSI();

  if (activityKnown) {
    latest["activity"] = activityName(activity);
    latest["stepsPerSecond"] = pace;
  }

  JsonObject today =
    latest["today"].to<JsonObject>();

  today["date"] = currentDate;
  today["restingSeconds"] = seconds[RESTING];
  today["walkingSeconds"] = seconds[WALKING];
  today["runningSeconds"] = seconds[RUNNING];

  // ---------- GPS Firebase fields ----------

  JsonObject gpsJson =
    latest["gps"].to<JsonObject>();

  uint32_t fixAgeMs =
    gpsReading.everFixed
    ? (uint32_t)(millis() - gpsReading.lastFixMs)
    : UINT32_MAX;

  bool gpsFixFresh =
    gpsReading.everFixed &&
    fixAgeMs < GPS_FRESH_MS;

  gpsJson["fixValid"] = gpsFixFresh;

  if (gpsReading.satellites >= 0) {
    gpsJson["satellites"] = gpsReading.satellites;
  }

  if (gpsReading.hdop >= 0) {
    gpsJson["hdop"] = gpsReading.hdop;
  }

  if (gpsReading.everFixed) {
    gpsJson["lastKnownLatitude"] = gpsReading.latitude;
    gpsJson["lastKnownLongitude"] = gpsReading.longitude;
    gpsJson["lastKnownAgeMs"] = fixAgeMs;

    if (gpsFixFresh) {
      gpsJson["latitude"] = gpsReading.latitude;
      gpsJson["longitude"] = gpsReading.longitude;
    }
  }

  latest["receivedAt"][".sv"] = "timestamp";
  data["seenAt"][".sv"] = "timestamp";

  String json;

  serializeJson(data, json);

  if (
    sendToFirebase(
      "PATCH",
      "collars/" + collarId,
      json
    ) == 200
  ) {
    Serial.printf(
      "[%s] sent: %s, %.2f steps/s, today R %lu / W %lu / Run %lu min\n",
      collarId.c_str(),
      activityKnown ? activityName(activity) : "(first 4 s)",
      pace,
      (unsigned long)(seconds[RESTING] / 60),
      (unsigned long)(seconds[WALKING] / 60),
      (unsigned long)(seconds[RUNNING] / 60)
    );
  }
}

// ---------- Daily activity JSON ----------

String dayJson(const uint32_t seconds[3]) {
  JsonDocument row;

  row["restingSeconds"] = seconds[RESTING];
  row["walkingSeconds"] = seconds[WALKING];
  row["runningSeconds"] = seconds[RUNNING];

  row["updatedAt"][".sv"] = "timestamp";

  String json;

  serializeJson(row, json);

  return json;
}

// ---------- Upload daily totals ----------

void uploadDays() {
  while (pendingCount > 0 && !firebaseToken.isEmpty()) {
    DayTotals &day = pendingDays[0];

    String path =
      "collarDays/" + collarId + "/" + String(day.date);

    if (
      sendToFirebase(
        "PUT",
        path,
        dayJson(day.seconds)
      ) != 200
    ) {
      return;
    }

    Serial.printf(
      "Sent the totals of %s\n",
      day.date
    );

    removeOldestPendingDay();
  }

  if (currentDate.isEmpty() || firebaseToken.isEmpty()) {
    return;
  }

  uint32_t seconds[3];

  lockData();
  memcpy(seconds, todaySeconds, sizeof(seconds));
  unlockData();

  sendToFirebase(
    "PUT",
    "collarDays/" + collarId + "/" + currentDate,
    dayJson(seconds)
  );
}

// ---------- Setup ----------

void setup() {
  Serial.begin(115200);
  delay(1500);

  setenv("TZ", TIME_ZONE, 1);
  tzset();

  collarId = makeCollarId();

  Serial.println();
  Serial.println("==============================");

  Serial.print("  Smart collar ID: ");
  Serial.println(collarId);

  Serial.println("  (write it on the sticker)");
  Serial.println("==============================");

  memory.begin("collar", false);

  loadFromMemory();

  while (!initializeSensor()) {
    Serial.println(
      "Check the sensor wiring. Trying again in 2 s..."
    );

    delay(2000);
  }

  // ---------- Start GPS ----------

  gpsUart.setRxBufferSize(2048);

  gpsUart.begin(
    GPS_BAUD,
    SERIAL_8N1,
    GPS_RX_PIN,
    GPS_TX_PIN
  );

  xTaskCreate(
    gpsTask,
    "gps",
    4096,
    nullptr,
    1,
    nullptr
  );

  Serial.println(
    "GPS UART initialized at 9600 baud (RX 38, TX 39)"
  );

  // ---------- Start motion sensor ----------

  xTaskCreate(
    sensorTask,
    "sensor",
    4096,
    nullptr,
    2,
    nullptr
  );

  // ---------- Start Wi-Fi ----------

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  lastWifiTryMs = millis();

  Serial.println(
    "Connecting to Wi-Fi (the collar keeps counting meanwhile)..."
  );
}

// ---------- Main loop ----------

void loop() {
  uint32_t now = millis();

  checkNewDay();

  if (now - lastDayMs >= DAY_INTERVAL_MS) {
    lastDayMs = now;

    saveToMemory();

    daysDue = true;
  }

  keepWiFiConnected();

  if (WiFi.status() != WL_CONNECTED) {
    delay(10);
    return;
  }

  if (!timeStarted) {
    configTzTime(
      TIME_ZONE,
      "pool.ntp.org",
      "time.google.com"
    );

    timeStarted = true;
  }

  bool tokenOld =
    !firebaseToken.isEmpty() &&
    now - lastAuthMs >= AUTH_INTERVAL_MS;

  if (
    (firebaseToken.isEmpty() || tokenOld) &&
    now - lastLoginTryMs >= LOGIN_RETRY_MS
  ) {
    lastLoginTryMs = now;

    firebaseLogin();
  }

  if (firebaseToken.isEmpty()) {
    delay(10);
    return;
  }

  if (now - lastLatestMs >= LATEST_INTERVAL_MS) {
    lastLatestMs = now;

    uploadLatest();
  }

  if (daysDue) {
    daysDue = false;

    uploadDays();
  }

  delay(10);
}

