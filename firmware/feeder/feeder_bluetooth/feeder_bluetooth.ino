/*
  FD-2.2 - Send manual feed command (feeder side)
  Isabel Al-Haddad

  Step 9.4: overfeeding protection.
  - A second FEED within 3 seconds is ignored (double taps).
  - At most 6 feeds per day.
  Messages can come from the phone (Bluetooth) or the Serial Monitor (USB).
*/

#include <BluetoothSerial.h>

BluetoothSerial SerialBT;    
String btMessage = "";       
String usbMessage = "";      

// Overfeeding protection settings
const unsigned long COOLDOWN_MS = 3000;            // 3 seconds between feeds
const int MAX_FEEDS_PER_DAY = 6;
const unsigned long ONE_DAY_MS = 24UL * 60 * 60 * 1000;

unsigned long lastFeedTime = 0;   // when the last feed happened (in ms)
bool hasFedBefore = false;        // false until the first feed
int feedsToday = 0;               // feeds since the start of the current day
unsigned long dayStartTime = 0;   // when the current day started (in ms)

// Sends a reply to both the phone and the Serial Monitor
void reply(String text) {
  Serial.println(text);
  if (SerialBT.hasClient()) {
    SerialBT.println(text);
  }
}

// Gives one portion of food.
// The motor code (FD-1.4) goes inside this function.
void feedOnce() {
  Serial.println("(motor would turn here)");
}

// Starts a new day once 24 hours have passed.
// Until the feeder knows the real time, a "day" starts when it is turned on.
void startNewDayIfNeeded() {
  if (millis() - dayStartTime >= ONE_DAY_MS) {
    dayStartTime = millis();
    feedsToday = 0;
  }
}

// Checks the protection rules, then feeds if it is allowed
void tryToFeed() {
  startNewDayIfNeeded();

  // Rule 1: too soon after the last feed
  if (hasFedBefore && millis() - lastFeedTime < COOLDOWN_MS) {
    reply("ERR COOLDOWN");
    return;
  }

  // Rule 2: already fed the maximum today
  if (feedsToday >= MAX_FEEDS_PER_DAY) {
    reply("ERR DAILY LIMIT");
    return;
  }

  feedOnce();
  lastFeedTime = millis();
  hasFedBefore = true;
  feedsToday++;
  reply("OK FED " + String(feedsToday));
}

// Decides what to do with one full message.
// This part stays the same if we add Firebase later.
void handleCommand(String msg) {
  msg.toUpperCase();              

  if (msg == "FEED") {
    tryToFeed();
  } else {
    reply("ERR UNKNOWN " + msg);
  }
}

// Reads letters from one source (Bluetooth or USB).
// When a full line has arrived, it calls handleCommand().
void checkForMessage(Stream &source, String &message) {
  while (source.available()) {
    char letter = source.read();

    if (letter == '\n') {            
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