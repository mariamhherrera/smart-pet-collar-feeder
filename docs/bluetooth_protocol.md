# Shared Bluetooth message format

## Transport
- Bluetooth Classic SPP (RFCOMM), UUID `00001101-0000-1000-8000-00805F9B34FB`
- Collar device name: `PetCollar_ESP32`
- UTF-8 text, one message per line, terminated by `\\n`

## Collar telemetry (current firmware contract)
The collar currently sends seven comma-separated numeric fields:

`time_s,x_g,y_g,z_g,gx_deg_s,gy_deg_s,gz_deg_s`

Example:

`12.345,0.0123,0.0456,0.9987,1.2000,-0.3000,0.1000`

Units: seconds, acceleration in g, angular velocity in degrees/second.
The CSV header is printed to the USB serial console only; Bluetooth sends data records.
The Android app ignores header/status lines and parses seven-value records.

## Feeder commands (FD-2.2)
- Feeder device name: `PetFeeder_ESP32`
- Same transport as the collar: Bluetooth Classic SPP, one UTF-8 message per line, ending in `\n`
  (a `\r` before it is ignored)
- Commands are not case sensitive (`feed` works like `FEED`)
- The feeder replies with one line to every command
- Firmware: `firmware/feeder/feeder_bluetooth/feeder_bluetooth.ino`

| App sends | Feeder replies | Meaning |
|---|---|---|
| `FEED` | `OK FED <n>` | One portion given. `<n>` is the number of feeds today (1 to 6) |
| `FEED` | `ERR COOLDOWN` | Ignored: less than 3 seconds since the last feed (double tap) |
| `FEED` | `ERR DAILY LIMIT` | Ignored: already 6 feeds today |
| anything else | `ERR UNKNOWN <message>` | Command not recognized |

Notes:
- The cooldown (3 s) and daily limit (6) are set at the top of the firmware
  (`COOLDOWN_MS`, `MAX_FEEDS_PER_DAY`).
- Until the feeder knows the real time, a "day" starts when it is turned on.
- Planned for FD-1.3 (not implemented yet): `SCHEDULE,<HH:MM>,<portion>`, for example
  `SCHEDULE,07:30,2`.

Do not change the collar telemetry record without coordinating a protocol version
with all teams.
