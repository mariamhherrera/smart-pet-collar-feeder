/*
  FD-2.2 - Send manual feed command (feeder side)
  Isabel Al-Haddad

  Step 9.2: understand the FEED command and reply.
  Messages can come from the phone (Bluetooth) or the Serial Monitor (USB).
*/

#include <BluetoothSerial.h>

BluetoothSerial SerialBT;    
String btMessage = "";       // letters received by Bluetooth so far
String usbMessage = "";      // letters received from the Serial Monitor so far

// Sends a reply to both the phone and the Serial Monitor
void reply(String text) {
  Serial.println(text);
  if (SerialBT.hasClient()) {
    SerialBT.println(text);
  }
}

// Decides what to do with one full message.
// stays the same if the team adds Firebase later.
void handleCommand(String msg) {
  msg.toUpperCase();              // "feed" and "FEED" both work

  if (msg == "FEED") {
    Serial.println("Feeding! (the motor is added in step 9.3)");
    reply("OK FED");
  } else {
    reply("ERR UNKNOWN " + msg);
  }
}

// Reads letters from one source (Bluetooth or USB).
// When a full line has arrived, it calls handleCommand().
void checkForMessage(Stream &source, String &message) {
  while (source.available()) {
    char letter = source.read();

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

void setup() {
  Serial.begin(115200);

  if (SerialBT.begin("PetFeeder_ESP32")) {
    Serial.println("Bluetooth started! Waiting for messages...");
  } else {
    Serial.println("Bluetooth failed to start!");
  }
}

void loop() {
  checkForMessage(SerialBT, btMessage);   // messages from the phone
  checkForMessage(Serial, usbMessage);    // messages typed in the Serial Monitor
}