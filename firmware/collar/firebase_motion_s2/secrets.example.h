// Copy this file to secrets.h (same folder) and fill it in.
// secrets.h is in .gitignore, so your passwords never go to GitHub.

#pragma once

#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"

// Firebase console > Project settings > General > Web API key
#define FIREBASE_API_KEY "your-web-api-key"

// Firebase console > Realtime Database (the URL at the top)
#define FIREBASE_DATABASE_URL "https://your-project-default-rtdb.firebaseio.com"

// Any account that can sign in to the app (email/password).
// The collar only writes under collars/<its ID>, so it does not have to be
// the pet owner's account. The pet owner links the collar in the app.
#define FIREBASE_TEST_EMAIL "collar@example.com"
#define FIREBASE_TEST_PASSWORD "collar-password"
