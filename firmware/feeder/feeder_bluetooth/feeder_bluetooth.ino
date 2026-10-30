/*
  FD-2.2 - Send manual feed command (feeder side)
  Isabel Al-Haddad

receive messages from the phone over Bluetooth
*/

#include <BluetoothSerial.h>

BluetoothSerial SerialBT;    
String message = "";         // collects letters until a full line arrives

// Decides what to do with one full message
// (stays the same if Firebase is added later)
void handleCommand(String msg) {
  Serial.println("Got: " + msg);
  SerialBT.println("You said: " + msg);
}

void setup() {
  Serial.begin(115200);

  if (SerialBT.begin("PetFeeder_ESP32")) {
    Serial.println("Bluetooth started! Waiting for messages...");
  } else {
    Serial.println("Bluetooth failed to start!");
  }
}

void loop() {
  // How the message arrives, by Bluetooth, one letter at a time
  while (SerialBT.available()) {
    char letter = SerialBT.read();

    if (letter == '\n') {            // end of the line = full message
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