
/*
  ELEC 390 - Smart Pet Collar
  Sprint 2 - Motion Sensor Test
  Reads acceleration and gyroscope data at 50 Hz.
*/

#include <Wire.h>

const uint8_t MPU_ADDR = 0x68;

const uint32_t SAMPLE_INTERVAL = 20000; // 50 Hz
uint32_t lastSample = 0;
unsigned int sampleCount = 0;

// Write to a sensor register
bool writeRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

// Read sensor identification
int readSensorID() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x75);

  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)1) != 1) return -1;

  return Wire.read();
}

// Read acceleration and gyroscope data
bool readMotion(float &ax, float &ay, float &az,
                float &gx, float &gy, float &gz) {

  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);

  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(MPU_ADDR, (uint8_t)14) != 14) return false;

  int16_t rawAX = (Wire.read() << 8) | Wire.read();
  int16_t rawAY = (Wire.read() << 8) | Wire.read();
  int16_t rawAZ = (Wire.read() << 8) | Wire.read();

  Wire.read(); // Skip temperature
  Wire.read();

  int16_t rawGX = (Wire.read() << 8) | Wire.read();
  int16_t rawGY = (Wire.read() << 8) | Wire.read();
  int16_t rawGZ = (Wire.read() << 8) | Wire.read();

  ax = rawAX / 16384.0f;
  ay = rawAY / 16384.0f;
  az = rawAZ / 16384.0f;

  gx = rawGX / 131.0f;
  gy = rawGY / 131.0f;
  gz = rawGZ / 131.0f;

  return true;
}

void setup() {
  Serial.begin(115200);
  delay(1500);

  // Enable Feather I2C power
  pinMode(PIN_I2C_POWER, INPUT);
  delay(1);
  bool polarity = digitalRead(PIN_I2C_POWER);
  pinMode(PIN_I2C_POWER, OUTPUT);
  digitalWrite(PIN_I2C_POWER, !polarity);

  // Start I2C using Feather pins
  Wire.begin(SDA, SCL);
  Wire.setClock(100000);

  Serial.println("Sprint 2 - Motion Sensor");

  int sensorID = readSensorID();

  Serial.print("Sensor WHO_AM_I = 0x");
  Serial.println(sensorID, HEX);

  if (sensorID != 0x70) {
    Serial.println("Sensor ID not recognized!");
    while (true) delay(1000);
  }

  if (!writeRegister(0x6B, 0x00)) {
    Serial.println("Sensor wake-up failed!");
    while (true) delay(1000);
  }

  delay(100);

  if (!writeRegister(0x1C, 0x00) ||
      !writeRegister(0x1B, 0x00)) {
    Serial.println("Sensor setup failed!");
    while (true) delay(1000);
  }

  Serial.println("Motion sensor initialized!");
  Serial.println("time_s,ax,ay,az,gx,gy,gz");

  lastSample = micros();
}

void loop() {
  uint32_t now = micros();

  if ((uint32_t)(now - lastSample) < SAMPLE_INTERVAL) {
    delay(1);
    return;
  }

  lastSample += SAMPLE_INTERVAL;

  float ax, ay, az, gx, gy, gz;

  if (!readMotion(ax, ay, az, gx, gy, gz)) {
    Serial.println("Sensor reading failed!");
    return;
  }

  sampleCount++;

  // Display every 10th sample to avoid Serial Monitor lag
  if (sampleCount >= 10) {
    Serial.print(millis() / 1000.0, 3);
    Serial.print(",");
    Serial.print(ax, 4);
    Serial.print(",");
    Serial.print(ay, 4);
    Serial.print(",");
    Serial.print(az, 4);
    Serial.print(",");
    Serial.print(gx, 4);
    Serial.print(",");
    Serial.print(gy, 4);
    Serial.print(",");
    Serial.println(gz, 4);

    sampleCount = 0;
  }
}
