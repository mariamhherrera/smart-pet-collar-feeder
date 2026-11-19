# Battery tests (COM-5.3)

Tested on Nov 19, 2026 with collar PC-417EF6 (Adafruit Feather ESP32-S2 with a 3.7 V 1000 mAh LiPo) and the app on the Android emulator (Pixel 9 Pro).

| # | Steps | Expected result | Result | Pass? |
|---|---|---|---|---|
| B1 | Plug the LiPo into the Feather's battery port, unplug the USB cable. Open Firebase > collars/(collar ID)/latest | batteryPercent is a real number (not -1), batteryVoltage between 3.0 and 4.2 V | batteryPercent = 65, batteryVoltage = 3.87 V. The collar kept running without USB | Pass |
| B2 | Collar on battery, open Home | Collar tile shows "Online · Battery xx%" | "Online · Battery 67%" | Pass |
| B3 | Open the Collar page right after B2 | Same % as Home | Battery 67%, same as Home | Pass |
| B4 | Plug the Feather's USB-C in (CHG light on), wait 10 minutes | The % goes up | Not run yet: collar not available. Will be run with COL-11.2 (battery life test) | — |
| B5 | Unplug the battery, wait 30 seconds, open Home and the Collar page | Red "Offline since (time)", and the last % still shows | Home: "Offline since 5:06 PM · Battery 66%" in red. Collar page: "Offline since 5:06 PM", Battery 66% | Pass |

B1 and B2 were read a few minutes apart, so the % is slightly different.