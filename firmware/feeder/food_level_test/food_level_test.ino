// FD-4: food level with the GP2Y0E03 IR sensor (I2C)
// Wiring: pins 1, 4, 5 -> 3V3 | pin 3 -> GND | pin 7 (SDA) -> GPIO 21 | pin 6 (SCL) -> GPIO 22
#include <Wire.h>

const uint8_t SENSOR_ADDRESS = 0x40;   // the sensor's I2C address (0x80 in the datasheet = 0x40 in Arduino)

// Calibration (measured in our feeder)
const float EMPTY_CM = 9.6;            // distance to the bottom when the container is empty
const float FULL_CM = 3.7;             // distance to the food at the max fill line

// Reads one byte from a register of the sensor. Returns -1 if the sensor does not answer.
int readRegister(uint8_t reg) {
  Wire.beginTransmission(SENSOR_ADDRESS);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  Wire.requestFrom(SENSOR_ADDRESS, (uint8_t)1);
  return Wire.available() ? Wire.read() : -1;
}

// One distance reading in cm, or -1 if the sensor does not answer.
float readDistanceCm() {
  int shift = readRegister(0x35);       // the sensor's range setting
  int high = readRegister(0x5E);        // distance, top 8 bits
  int low = readRegister(0x5F);         // distance, bottom 4 bits
  if (shift < 0 || high < 0 || low < 0) return -1;
  int raw = (high << 4) | (low & 0x0F); // 12-bit value
  return raw / 16.0 / (1 << shift);     // formula from the datasheet
}

// Takes 7 readings, ignores "no reading" (60 cm or more), returns the middle one.
// Returns -1 if there were no good readings at all.
float readDistanceStable() {
  float good[7];
  int count = 0;
  for (int i = 0; i < 7; i++) {
    float cm = readDistanceCm();
    if (cm > 0 && cm < 60) good[count++] = cm;   // 64 cm means "nothing seen"
    delay(20);
  }
  if (count == 0) return -1;
  // Sort the good readings and pick the middle one (the median)
  for (int i = 0; i < count; i++)
    for (int j = i + 1; j < count; j++)
      if (good[j] < good[i]) { float t = good[i]; good[i] = good[j]; good[j] = t; }
  return good[count / 2];
}

// Food left in %, from 0 (empty) to 100 (full). Returns -1 if there was no reading.
int foodLevelPercent(float cm) {
  if (cm < 0) return -1;
  float percent = (EMPTY_CM - cm) / (EMPTY_CM - FULL_CM) * 100.0;
  if (percent < 0) percent = 0;         // a bit below empty: still 0%
  if (percent > 100) percent = 100;     // a bit above the fill line: still 100%
  return (int)(percent + 0.5);          // round to the nearest whole %
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);                   // SDA, SCL
  delay(100);
  Serial.println("Food level test");
}

void loop() {
  float cm = readDistanceStable();
  int percent = foodLevelPercent(cm);
  if (percent < 0) {
    Serial.println("No reading (food too close to the sensor, or nothing in range)");
  } else {
    Serial.print("Distance: ");
    Serial.print(cm, 1);
    Serial.print(" cm   Food left: ");
    Serial.print(percent);
    Serial.println(" %");
  }
  delay(500);
}