# T240 BLE Connect Test

Minimal Android app whose only job is to prove a custom app can talk to the
Shengmai T240 "Record Card" over BLE:

1. **Scan & Connect** — finds a device whose name contains `T240`, connects.
2. **Handshake/bind** — enables notifications on `1911` (this opens the data
   channel and starts the device's 5-second handshake window), receives the
   device UUID, replies with a persisted app-generated UUID + timestamp, and
   waits for the bind result (`0x00` = **Bound ✓**).
   On success it immediately sends the time-sync command (`0x04`).
3. **Read Battery** — `01 09 00`, shows the returned percentage.
4. **Enable USB Disk** — see below.
5. **Send raw** — type any frame in hex (e.g. `01 02 00` for SN) to poke other
   protocol commands while debugging. Every TX/RX frame is logged in hex.

## "Enable USB Disk"

The protocol doc has **no dedicated "open USB disk" opcode**. USB-disk (U盘)
mode is the device's *default* state and is blocked by exactly two things:

- **WiFi open** (`0x0A`): "打开WIFI后，SD卡控制权移交到WIFI芯片，此时U盘功能将被屏蔽"
  — opening WiFi hands the SD card to the WiFi chip and *masks the USB-disk
  function*. Close WiFi with `01 0B 00`.
- **Storage disabled** (`0x70` param `0x01`): "禁用存储指不可当成U盘接在电脑使用"
  — disabled storage means the device *cannot be used as a USB drive on a
  computer*. Re-enable with `01 70 00 00`; query state with `01 71 00`
  (reply `0x00` = storage allowed).

So the button runs: `01 0B 00` (close WiFi) → `01 70 00 00` (enable storage)
→ `01 71 00` (verify). When the final reply is `0x00`, plug the T240 into a
computer over USB and it should mount as a browsable drive. Note the device
must not be recording (`0x70` is rejected mid-recording) and per the doc it
must be plugged into the computer to mount — BLE can stay connected.

## Protocol summary implemented

Frame: `0x01 <cmdL> <cmdH> [data...]` on GATT service `1910`
(write-without-response `1912`, notify `1911`). MTU 247 is requested on
connect because handshake JSON payloads exceed 20 bytes; notification
reassembly handles JSON split across notifications (brace-balanced).

| Command | Frame | Reply |
|---|---|---|
| Handshake hello | dev→app `01 01 00 00` + JSON `{uuid}` | — |
| Bind | app→dev `01 01 00 01` + JSON `{time,uuid}` | `01 01 00 02 <code>` (+ info JSON if `00`) |
| Time sync | `01 04 00` + `YYYYMMDDhhmmss` | echo |
| Battery | `01 09 00` | `01 09 00 <0..0x64>` |
| Close WiFi | `01 0B 00` | `01 0B 00` |
| Storage enable/disable | `01 70 00 <00/01>` | `01 70 00 <01 ok/00 fail>` |
| Storage state | `01 71 00` | `01 71 00 <00 allowed/01 disabled>` |

Bind failure codes: `01` app-UUID check failed (device is bound to another
app — unbind via raw `01 05 00`), `02` bad length, `03` command before
handshake, `04` handshake timeout (>5 s).

## Build

```
cd t240-ble-test
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (compileSdk 34). minSdk 26, targetSdk 34.
Runtime permissions: `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on Android 12+,
`ACCESS_FINE_LOCATION` (plus location services ON) on Android 11 and below.

Everything lives in one file:
`app/src/main/java/com/dsr/t240ble/MainActivity.kt`.
