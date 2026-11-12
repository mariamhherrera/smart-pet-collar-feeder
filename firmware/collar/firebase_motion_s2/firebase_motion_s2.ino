
/*
  Adafruit Feather ESP32-S2
  MPU-6500 compatible motion sensor

  Reads motion data approximately every 20 ms.
  Sends the latest reading to Firebase every 5 seconds.
*/

#include <Wire.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>
#include "secrets.h"

const uint8_t MPU_ADDR = 0x68;

const uint32_t SAMPLE_INTERVAL_MS = 20;
const uint32_t UPLOAD_INTERVAL_MS = 5000;
const uint32_t AUTH_INTERVAL_MS = 3000000; // 50 minutes

struct MotionReading {
  float ax, ay, az;
  float gx, gy, gz;
  float deviceTimeSeconds;
};

MotionReading latestMotion;

String firebaseToken;
String ownerUid;

uint32_t lastSampleMs = 0;
uint32_t lastUploadMs = 0;
uint32_t lastAuthMs = 0;

bool sensorReady = false;
bool haveReading = false;

// ---------- MOTION SENSOR ----------

bool writeRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

int readSensorID() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x75);

  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)1) != 1)
    return -1;

  return Wire.read();
}

bool initializeSensor() {
  // Enable the Feather's I2C power
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

  if (!writeRegister(0x6B, 0x00)) return false;

  delay(100);

  if (!writeRegister(0x1C, 0x00) ||
      !writeRegister(0x1B, 0x00)) {
    return false;
  }

  Serial.println("Motion sensor initialized!");
  return true;
}

bool readMotion(MotionReading &r) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);

  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)14) != 14)
    return false;

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

  r.deviceTimeSeconds = millis() / 1000.0f;

  return true;
}

// ---------- WIFI ----------

bool connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("Connecting to WiFi");

  int attempts = 0;

  while (WiFi.status() != WL_CONNECTED &&
         attempts < 40) {
    Serial.print(".");
    delay(500);
    attempts++;
  }

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\nWiFi connection failed!");
    return false;
  }

  Serial.println("\nWiFi connected successfully!");
  return true;
}

bool synchronizeTime() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");

  int attempts = 0;

  while (time(nullptr) < 1700000000 &&
         attempts < 30) {
    delay(500);
    attempts++;
  }

  if (time(nullptr) < 1700000000) {
    Serial.println("Time synchronization failed!");
    return false;
  }

  Serial.println("Time synchronized successfully!");
  return true;
}

// ---------- FIREBASE AUTHENTICATION ----------

bool firebaseLogin() {
  Serial.println("Authenticating with Firebase...");

  // Use a separate scope to free HTTPS memory
  WiFiClientSecure client;
  client.useBuiltinCACertBundle();

  HTTPClient http;
  http.setReuse(false);
  http.setTimeout(15000);

  String url =
    "https://identitytoolkit.googleapis.com/v1/"
    "accounts:signInWithPassword?key=";

  url += FIREBASE_API_KEY;

  if (!http.begin(client, url)) {
    Serial.println("Firebase authentication setup failed!");
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

  bool success = false;

  if (code == 200) {
    JsonDocument result;

    if (!deserializeJson(result, response)) {
      firebaseToken = result["idToken"].as<String>();
      ownerUid = result["localId"].as<String>();

      success =
        !firebaseToken.isEmpty() && !ownerUid.isEmpty();
    }
  }

  if (success) {
    lastAuthMs = millis();
    Serial.println("Firebase authentication successful!");
  } else {
    Serial.print("Firebase login failed. HTTP code: ");
    Serial.println(code);

    if (code < 0) {
      Serial.println(http.errorToString(code));
    }

    firebaseToken = "";
  }

  http.end();
  client.stop();

  return success;
}

// ---------- FIREBASE DATABASE ----------

int uploadMotion(const MotionReading &r) {
  WiFiClientSecure client;
  client.useBuiltinCACertBundle();

  HTTPClient http;
  http.setReuse(false);
  http.setTimeout(20000);

  String url = String(FIREBASE_DATABASE_URL);

  if (url.endsWith("/")) {
    url.remove(url.length() - 1);
  }

  url += "/owners/";
  url += ownerUid;
  url += "/collar/latest.json?auth=";
  url += firebaseToken;

  // Do not print this URL: it contains the token.

  if (!http.begin(client, url)) {
    Serial.println("Database request setup failed!");
    return -1;
  }

  http.addHeader("Content-Type", "application/json");

  JsonDocument data;

  data["deviceTimeSeconds"] = r.deviceTimeSeconds;

  JsonObject accel =
    data["accelerationG"].to<JsonObject>();

  accel["x"] = r.ax;
  accel["y"] = r.ay;
  accel["z"] = r.az;

  JsonObject gyro =
    data["gyroscopeDegreesPerSecond"].to<JsonObject>();

  gyro["x"] = r.gx;
  gyro["y"] = r.gy;
  gyro["z"] = r.gz;

  JsonObject timestamp =
    data["receivedAt"].to<JsonObject>();

  timestamp[".sv"] = "timestamp";

  String jsonBody;
  serializeJson(data, jsonBody);

  int code = http.PUT(jsonBody);

  if (code == 200) {
    Serial.println("Motion data uploaded successfully!");
  } else {
    Serial.print("Firebase upload failed. HTTP code: ");
    Serial.println(code);

    if (code < 0) {
      Serial.println(http.errorToString(code));
    } else {
      Serial.println(http.getString());
    }
  }

  http.end();
  client.stop();

  return code;
}

// ---------- SETUP ----------

void setup() {
  Serial.begin(115200);
  delay(1500);

  Serial.println("ELEC 390 - Continuous Motion + Firebase");

  sensorReady = initializeSensor();

  if (!sensorReady) return;
  if (!connectWiFi()) return;
  if (!synchronizeTime()) return;
  if (!firebaseLogin()) return;

  Serial.println("Continuous monitoring started!");

  lastSampleMs = millis();
  lastUploadMs = millis();
}

// ---------- MAIN LOOP ----------

void loop() {
  if (!sensorReady) {
    delay(1000);
    return;
  }

  uint32_t now = millis();

  // Read MPU approximately 50 times per second
  if (now - lastSampleMs >= SAMPLE_INTERVAL_MS) {
    lastSampleMs = now;

    if (readMotion(latestMotion)) {
      haveReading = true;
    }
  }

  // Upload only once every 5 seconds
  if (now - lastUploadMs < UPLOAD_INTERVAL_MS) {
    delay(1);
    return;
  }

  lastUploadMs = now;

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi disconnected. Reconnecting...");
    WiFi.reconnect();
    return;
  }

  // Firebase tokens expire, so sign in again
  // before reaching the normal expiration time.
  if (firebaseToken.isEmpty() ||
      now - lastAuthMs >= AUTH_INTERVAL_MS) {

    if (!firebaseLogin()) return;
  }

  if (!haveReading) {
    Serial.println("No motion reading available!");
    return;
  }

  Serial.printf(
    "Acceleration: %.3f, %.3f, %.3f g\n",
    latestMotion.ax,
    latestMotion.ay,
    latestMotion.az
  );

  Serial.printf(
    "Gyroscope: %.3f, %.3f, %.3f deg/s\n",
    latestMotion.gx,
    latestMotion.gy,
    latestMotion.gz
  );

  Serial.println("Uploading to Firebase...");

  int result = uploadMotion(latestMotion);

  // If token is rejected, get a new one
  // on the next upload attempt.
  if (result == 401) {
    firebaseToken = "";
  }
}

