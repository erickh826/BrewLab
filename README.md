# BrewLab

BrewLab is an Android pour-over coffee brewing learning and measurement app built with Kotlin, Jetpack Compose, Material 3, Room, and Navigation Compose.

## Current Scope

- Works **100% offline** with no accounts, backend services, Firebase, or Gemini API calls.
- Uses **simulated scale data (`模擬數據`)** via `DemoScaleDataSource` (~10 Hz quantised readings with real monotonic timestamps, configurable noise, automatic pour/rest simulation independent of recipes, optional manual pouring-rate control, tare before start, and simulated disconnection/reconnection).
- **實體藍牙電子秤** receives live weights from the BrewLab ESP32 scale. Each notification contains one UTF-8 numeric value in grams. BrewLab records the phone's monotonic receive time and preserves the reported value, including negative readings. The firmware has no remote Tare command.

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

## Test the ESP32 Scale on a Pixel 4

1. Enable USB debugging and Bluetooth. Install the debug build with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Disconnect nRF Connect from the ESP32 so BrewLab can connect.
2. Open **裝置設定**, choose **實體藍牙電子秤**, and tap **掃描電子秤**. Grant Nearby devices on Android 12+ or precise location on Android 11 and earlier. Enable the phone's location services on Android 11 and earlier.
3. Select **BrewLab-Scale** when it appears. Its advertisement must include service UUID `b1e00001-8a20-4c8f-a45e-6f12e3d45c90`. Wait for **已連線** and **持續接收重量通知**. Add and remove weight and compare the readout with the firmware values.
4. Turn off the ESP32 or pause its notifications. Confirm that the app shows a disconnected or interrupted state and clears the live weight. Five seconds without a valid reading ends the stream so you can scan and reconnect.
5. In **沖煮**, confirm that **歸零 (Tare)** is disabled for hardware. Return to **裝置設定** and switch to **模擬電子秤（模擬數據）** to verify Demo still connects and tares.

## Architecture

- `com.example.domain.model` — Pure Kotlin domain models (`Recipe`, `RecipeSnapshot`, `ScaleSample`, `BrewEvent`, `BrewSession`, `AlgorithmConfig`).
- `com.example.domain.measurement` — Pure Kotlin measurement & validation logic (`RecipeValidator`, `TargetCurveGenerator`, `RateEstimator`, `SessionAnalyzer`), independent of Android UI classes.
- `com.example.device` — `ScaleDataSource`, `DemoScaleDataSource`, and `ScaleDeviceManager`. The ESP32 GATT transport and weight parser live in `com.example.device.ble`.
- `com.example.data` — Room database (`BrewLabDatabase`, entities & DAOs separating raw samples and events from session metadata), `BrewRepository`, `SeedData`, `AppPreferences`, and `BrewExporter` (CSV & JSON via `FileProvider` and SAF).
- `com.example.brew` — `BrewRecordingCoordinator` managing the single foreground sample subscription, timer, and lifecycle interruption tracking across navigation.
- `com.example.ui` — Jetpack Compose Material 3 screens (`首頁`, `沖煮`, `配方`, `紀錄`, `裝置設定`, `系統診斷`) and custom Canvas charts.

## Measurement Assumptions

- One scale supports the dripper, coffee grounds, and server together while the kettle is held separately.
- Change in total scale weight approximates externally added water (**累計注水量**, g); least-squares slope over a configurable rolling window (0.5 s, 1 s default, 2 s) using actual sample receive timestamps approximates pouring mass flow (**注水速率**, g/s).
- Does **not** measure coffee dripping out of the dripper separately, and weight data alone cannot determine flavour, extraction yield, water temperature, or pouring trajectory.
- Disconnections, clock resets, foreground interruptions, or interference events (`碰觸器具`, `移動器具`) break the valid analysis interval. Missing data is never zero-filled or interpolated, unavailable values display as `—`, and chart lines break across gaps.
