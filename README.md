# BrewLab

BrewLab is an Android pour-over coffee brewing learning and measurement app built with Kotlin, Jetpack Compose, Material 3, Room, and Navigation Compose.

## Current Scope

- Works **100% offline** with no accounts, backend services, Firebase, or Gemini API calls.
- Uses **simulated scale data (`模擬數據`)** via `DemoScaleDataSource` (~10 Hz quantised readings with real monotonic timestamps, configurable noise, automatic pour/rest simulation independent of recipes, optional manual pouring-rate control, tare before start, and simulated disconnection/reconnection).
- **實體藍牙電子秤** receives live weights from the BrewLab ESP32 scale. Each notification contains one UTF-8 numeric value in grams. BrewLab records the phone's monotonic receive time and preserves the reported value, including negative readings. Compatible firmware supports remote Tare before recording.

## How to Run

Install Android SDK Platform 36.1 and Build-Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME` for command-line builds. The wrapper uses Gradle 9.3.1 with Android Gradle Plugin 9.1.1. Use JDK 21 for the full test suite because the Robolectric tests run Android API 36. JDK 17 is sufficient to assemble the APK.

1. Build and run the unit and Robolectric tests with `./gradlew :app:testDebugUnitTest :app:assembleDebug` on macOS/Linux or `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` in Windows PowerShell.
2. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A repository-root `debug.keystore`, when present, is used for AI Studio debug builds. Otherwise Android Gradle Plugin uses or creates the standard local `~/.android/debug.keystore`. Generate the optional repository-root debug key locally with `keytool`.

   ```sh
   keytool -genkeypair -keystore debug.keystore -storetype JKS -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

3. Launch the app on an Android device or emulator.
   - Explore the seeded demonstration recipe (**示範配方（可編輯範例，非業界標準）**) and seeded Demo sessions (**模擬範例**).
   - Go to **沖煮**, connect the Demo scale, complete the pre-brew checklist, press **歸零 (Tare)**, press **開始紀錄**, mark manual pour intervals or interference events, test a simulated disconnection if desired, finish, add notes, save, and open the report.

## ESP32 BLE Protocol

The ESP32 advertises as `BrewLab-Scale` with service UUID `b1e00001-8a20-4c8f-a45e-6f12e3d45c90`.

| Characteristic | UUID | Properties | UTF-8 payload |
| --- | --- | --- | --- |
| Weight | `b1e00002-8a20-4c8f-a45e-6f12e3d45c90` | READ, NOTIFY, CCCD | A decimal weight in grams, such as `-0.1` |
| Command | `b1e00003-8a20-4c8f-a45e-6f12e3d45c90` | WRITE with response, NOTIFY, CCCD | Write `TARE:123` and receive `OK:123` or `ERR:123:TIMEOUT` |

Request IDs range from 1 to 65535. The app enables weight notifications first and enables Tare only after the command CCCD write is acknowledged. A successful GATT write confirms delivery. The app marks Tare successful only after a matching `OK` on that connection. It waits up to 8 seconds for completion, separately from the 5-second weight watchdog. Older firmware with only the Weight characteristic still streams weights.

The firmware uses HX711 DT on GPIO 19, SCK on GPIO 18, and a calibration factor of 405.7 counts per gram. It tares on startup with the scale empty. A remote Tare averages 20 fresh raw samples in the main loop and sends `OK` after setting the offset. On a 4-second sampling timeout it sends `ERR:<id>:TIMEOUT` and keeps the previous offset. A second queued request during a Tare receives `ERR:<id>:BUSY`. The app can also display `NOT_READY` if a firmware version sends it.

## Test the ESP32 Scale on a Pixel 4

1. Flash `firmware/BrewLabScaleBLE.ino` using the ESP32 Arduino core and the HX711 Arduino Library. If opening the sketch in Arduino IDE, put it in a folder named `BrewLabScaleBLE`. Leave the scale empty during boot. Enable USB debugging and Bluetooth on the Pixel 4, then install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Disconnect nRF Connect from the ESP32 before using BrewLab.
2. Open **裝置設定**, choose **實體藍牙電子秤**, and tap **掃描電子秤**. Grant Nearby devices on Android 12+ or precise location on Android 11 and earlier. Enable the phone's location services on Android 11 and earlier.
3. Select **BrewLab-Scale** when it appears. Its advertisement must include service UUID `b1e00001-8a20-4c8f-a45e-6f12e3d45c90`. Wait for **已連線** and **持續接收重量通知**. Add and remove weight and compare the readout with the firmware values.
4. Place a container on the scale, open **沖煮**, and tap **歸零 (Tare)**. Confirm **歸零中** blocks a second tap and starting a recording. Wait for **已歸零** and confirm the live readout approaches zero through weight notifications. Remove the container and check for a negative reading.
5. Disconnect during a Tare by turning off the ESP32. Confirm the pending Tare ends without showing **已歸零** and the raw readout clears. Power the ESP32 on, reconnect, and retry. After a successful Tare, reconnect again and confirm the old success label is cleared.
6. Start a recording and confirm Tare is disabled. Repeat with the previous, weight-only firmware to check that weight still streams and Tare is unavailable. Switch to **模擬電子秤（模擬數據）** and confirm Demo still connects and tares.

## Architecture

- `com.example.domain.model` — Pure Kotlin domain models (`Recipe`, `RecipeSnapshot`, `ScaleSample`, `BrewEvent`, `BrewSession`, `AlgorithmConfig`).
- `com.example.domain.measurement` — Pure Kotlin measurement & validation logic (`RecipeValidator`, `TargetCurveGenerator`, `RateEstimator`, `SessionAnalyzer`), independent of Android UI classes.
- `com.example.device` — `ScaleDataSource`, `DemoScaleDataSource`, and `ScaleDeviceManager`. The ESP32 GATT transport, weight parser, and Tare command session live in `com.example.device.ble`.
- `com.example.data` — Room database (`BrewLabDatabase`, entities & DAOs separating raw samples and events from session metadata), `BrewRepository`, `SeedData`, `AppPreferences`, and `BrewExporter` (CSV & JSON via `FileProvider` and SAF).
- `com.example.brew` — `BrewRecordingCoordinator` managing the single foreground sample subscription, timer, and lifecycle interruption tracking across navigation.
- `com.example.ui` — Jetpack Compose Material 3 screens (`首頁`, `沖煮`, `配方`, `紀錄`, `裝置設定`, `系統診斷`) and custom Canvas charts.

## Measurement Assumptions

- One scale supports the dripper, coffee grounds, and server together while the kettle is held separately.
- Change in total scale weight approximates externally added water (**累計注水量**, g); least-squares slope over a configurable rolling window (0.5 s, 1 s default, 2 s) using actual sample receive timestamps approximates pouring mass flow (**注水速率**, g/s).
- Does **not** measure coffee dripping out of the dripper separately, and weight data alone cannot determine flavour, extraction yield, water temperature, or pouring trajectory.
- Disconnections, clock resets, foreground interruptions, or interference events (`碰觸器具`, `移動器具`) break the valid analysis interval. Missing data is never zero-filled or interpolated, unavailable values display as `—`, and chart lines break across gaps.
