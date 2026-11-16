// Copy this file to secrets.h (same folder) and fill it in.
// secrets.h is in .gitignore, so your passwords never go to GitHub.
// Without secrets.h, the feeder works by Bluetooth only.

#pragma once

// Your home Wi-Fi (2.4 GHz: the ESP32 cannot use 5 GHz networks)
#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"

// Firebase console > Project settings > General > Web API key
#define FIREBASE_API_KEY "your-web-api-key"

// Firebase console > Realtime Database (the URL at the top)
#define FIREBASE_DATABASE_URL "https://your-project-default-rtdb.firebaseio.com"

// The SAME email and password you use to log in to the PetTracker app.
// The feeder reads and writes your owners/<you>/feeder data, like the app.
#define FIREBASE_EMAIL "you@example.com"
#define FIREBASE_PASSWORD "your-app-password"
