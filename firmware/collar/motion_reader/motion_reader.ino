
/*
  HW-1.2 - Read Motion Values
  Johan Quezada 
  Smart Pet Collar and Feeder

  Reads acceleration and rotation from the MPU-6050
  using an ESP32 at approximately 50 samples/second.
*/

#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <BluetoothSerial.h>


Adafruit_MPU6050 mpu;
BluetoothSerial SerialBT;

const int SDA_PIN = 21;
const int SCL_PIN = 22;

const unsigned long SAMPLE_INTERVAL = 20000; // 20 ms = 50 Hz
unsigned long lastSample = 0;

const float GRAVITY = 9.80665;
const float RAD_TO_DEGREES = 57.29578;

void setup() {
  Serial.begin(115200);

  // Start Bluetooth communication
if (SerialBT.begin("PetCollar_ESP32")) {
    Serial.println("Bluetooth started successfully!");
} else {
    Serial.println("Bluetooth initialization failed!");
}

  // Start I2C communication
  Wire.begin(SDA_PIN, SCL_PIN);

  // Check if the motion sensor is connected
  if (!mpu.begin()) {
    Serial.println("MPU-6050 not detected!");
    while (true) {
      delay(1000);
    }
  }

  // Configure sensor settings
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);

  Serial.println("MPU-6050 initialized successfully!");

  // CSV column headers
  Serial.println("time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s");

  lastSample = micros();
}

void loop() {
  unsigned long currentTime = micros();

  // Collect data approximately every 20 milliseconds
  if (currentTime - lastSample >= SAMPLE_INTERVAL) {
    lastSample = currentTime;

    sensors_event_t accel, gyro, temp;

    // Read sensor measurements
    if (!mpu.getEvent(&accel, &gyro, &temp)) {
      Serial.println("Sensor reading failed!");
      return;
    }

    // Convert acceleration from m/s^2 to g
    float ax = accel.acceleration.x / GRAVITY;
    float ay = accel.acceleration.y / GRAVITY;
    float az = accel.acceleration.z / GRAVITY;

    // Convert rotation from rad/s to degrees/s
    float gx = gyro.gyro.x * RAD_TO_DEGREES;
    float gy = gyro.gyro.y * RAD_TO_DEGREES;
    float gz = gyro.gyro.z * RAD_TO_DEGREES;

    // Print measurements as CSV
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

    // Send motion data over Bluetooth
    if (SerialBT.hasClient()) {
      char btData[120];

      snprintf(btData, sizeof(btData),
               "%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
               millis() / 1000.0,
               ax, ay, az,
               gx, gy, gz);

      SerialBT.println(btData);
    }

  }
}
