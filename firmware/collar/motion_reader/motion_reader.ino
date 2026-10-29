
/*
  HW-1.2 and COM-1.1
  Smart Pet Collar - Motion Reader
  ESP32 with MPU-6500-compatible sensor

  Reads motion data at 50 Hz.
  Sends CSV measurements through Bluetooth.
*/

#include <Wire.h>
#include <BluetoothSerial.h>

BluetoothSerial SerialBT;

const uint8_t MPU_ADDR = 0x68;
const int SDA_PIN = 21;
const int SCL_PIN = 22;

const uint32_t SAMPLE_INTERVAL = 20000; // 50 Hz
uint32_t lastSample = 0;
unsigned int sampleCount = 0;

// Write a value to a sensor register
bool writeRegister(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

// Read the sensor identification
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

// Read acceleration and gyroscope data
bool readMotion(float &ax, float &ay, float &az,
                float &gx, float &gy, float &gz) {

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

  // Skip temperature bytes
  Wire.read();
  Wire.read();

  int16_t rawGX = (Wire.read() << 8) | Wire.read();
  int16_t rawGY = (Wire.read() << 8) | Wire.read();
  int16_t rawGZ = (Wire.read() << 8) | Wire.read();

  // Convert acceleration to g
  ax = rawAX / 16384.0f;
  ay = rawAY / 16384.0f;
  az = rawAZ / 16384.0f;

  // Convert rotation to degrees/second
  gx = rawGX / 131.0f;
  gy = rawGY / 131.0f;
  gz = rawGZ / 131.0f;

  return true;
}

void setup() {
  Serial.begin(115200);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(100000);

  delay(1000);

  // Start Bluetooth
  if (SerialBT.begin("PetCollar_ESP32")) {
    Serial.println("Bluetooth started successfully!");
  } else {
    Serial.println("Bluetooth initialization failed!");
  }

  // Identify sensor
  int sensorID = readSensorID();

  Serial.print("Sensor WHO_AM_I = 0x");
  Serial.println(sensorID, HEX);

  if (sensorID != 0x70) {
    Serial.println("Unexpected sensor ID!");
    while (true) {
      delay(1000);
    }
  }

  // Wake sensor
  if (!writeRegister(0x6B, 0x00)) {
    Serial.println("Sensor wake-up failed!");
    while (true) {
      delay(1000);
    }
  }

  delay(100);

  // Accelerometer: +/- 2g
  // Gyroscope: +/- 250 degrees/s
  if (!writeRegister(0x1C, 0x00) ||
      !writeRegister(0x1B, 0x00)) {
    Serial.println("Sensor configuration failed!");
    while (true) {
      delay(1000);
    }
  }

  Serial.println("Motion sensor initialized successfully!");
  Serial.println("time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s");

  lastSample = micros();
}

void loop() {
  uint32_t currentTime = micros();

  if ((uint32_t)(currentTime - lastSample) < SAMPLE_INTERVAL) {
    delay(1);
    return;
  }

  lastSample += SAMPLE_INTERVAL;

  float ax, ay, az;
  float gx, gy, gz;

  if (!readMotion(ax, ay, az, gx, gy, gz)) {
    Serial.println("Sensor reading failed!");
    return;
  }

  // Format data as CSV
  char data[140];

  snprintf(data, sizeof(data),
           "%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
           millis() / 1000.0,
           ax, ay, az,
           gx, gy, gz);

  // Send all samples over Bluetooth
  if (SerialBT.hasClient()) {
    SerialBT.println(data);
  }

  // Print every 10th sample to avoid IDE lag
  sampleCount++;

  if (sampleCount >= 10) {
    Serial.println(data);
    sampleCount = 0;
  }
}
