# GOODWILL M80 Machine Doctor — Android Native v2

Fresh Android project built from scratch for direct, READ-ONLY Mitsubishi M80 monitoring.

## Default machine connection
- Wi-Fi SSID: `GOODWILL`
- M80 IP: `192.168.250.1`
- SLMP TCP: `30000`

## Read-only safety
The Android code implements only SLMP command `0x0401` (device read).
There is no PLC force, output write, alarm reset, Cycle Start, servo command, or CNC parameter write.

## Native screens
- Live Panel
- Job & Live
- Alarms
- Lubrication
- Cycle & Runtime
- PLC Live Read
- Diagnostics
- Manual & Help
- Settings (password `0209`)

True X/Y/Z engineering coordinates and actual live O-program/N-block are intentionally not faked. They will be enabled after exact M80 mapping/scaling is verified on the machine.
