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

## Feeder interoperability
The app's `BluetoothSppClient.sendMessage(message)` sends a UTF-8 line plus newline,
so the same SPP transport can be reused by the feeder pair. Before shipping feeder
commands, FD-1.3 and FD-2.2 should agree and document command names, fields, units,
acknowledgements, and error responses. Do not change the collar telemetry record
without coordinating a protocol version with all teams.
