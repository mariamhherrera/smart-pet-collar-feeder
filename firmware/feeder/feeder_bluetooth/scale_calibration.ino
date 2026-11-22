/*
  Bowl scale calibration (run once, before adding the scale to the feeder code)

  Library: install "HX711" by Bogdan Necula (Sketch > Include Library > Manage Libraries)

  Wiring:
    Load cell red   -> HX711 E+      HX711 VCC -> ESP32 3V3
    Load cell black -> HX711 E-      HX711 GND -> ESP32 GND (common ground)
    Load cell white -> HX711 A-      HX711 DT  -> ESP32 GPIO 16
    Load cell green -> HX711 A+      HX711 SCK -> ESP32 GPIO 4

  Steps (Serial Monitor at 115200, "Newline"):
    1. Start with the EMPTY bowl on the scale. The sketch zeroes it ("tare").
    2. Put something you know the weight of in the bowl (weigh it on a kitchen scale first,
       e.g. 200 g of kibble or a full can).
    3. Type that weight in grams and press Enter, for example: 200
    4. The sketch prints your CALIBRATION_FACTOR. Copy that number into the feeder code.
    5. After that it shows the weight every half second so you can check it.
*/

#include "HX711.h"

const int SCALE_DT = 16;
const int SCALE_SCK = 4;

HX711 scale;
float factor = 1.0;
bool calibrated = false;
String typed = "";

void setup() {
  Serial.begin(115200);
  delay(500);
  scale.begin(SCALE_DT, SCALE_SCK);

  if (!scale.wait_ready_timeout(2000)) {
    Serial.println("ERROR: HX711 not found. Check DT/SCK wires and power.");
    return;
  }

  Serial.println("Empty bowl on the scale... zeroing in 3 s");
  delay(3000);
  scale.set_scale(1.0);
  scale.tare(20);                       // average of 20 readings = zero
  Serial.println("Zeroed. Put a known weight in the bowl, then type its grams and press Enter.");
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      typed.trim();
      if (typed.length() > 0) {
        float grams = typed.toFloat();
        if (grams > 0) {
          float raw = scale.get_value(20);     // reading without any factor
          factor = raw / grams;
          scale.set_scale(factor);
          calibrated = true;
          Serial.println("CALIBRATION_FACTOR = " + String(factor, 2));
          Serial.println("Copy this number into the feeder code.");
        }
      }
      typed = "";
    } else {
      typed += c;
    }
  }

  if (calibrated) {
    Serial.println("Weight: " + String(scale.get_units(5), 1) + " g");
    delay(500);
  }
}