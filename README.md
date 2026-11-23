# BrewLab

BrewLab is an Android pour-over coffee brewing learning and measurement app built with Kotlin, Jetpack Compose, Material 3, Room, and Navigation Compose.

## Current Scope (Milestone 1 — Demo Scale Only)

- Works **100% offline** with no accounts, backend services, Firebase, or Gemini API calls.
- Uses **simulated scale data (`模擬數據`)** via `DemoScaleDataSource` (~10 Hz quantised readings with real monotonic timestamps, configurable noise, automatic pour/rest simulation independent of recipes, optional manual pouring-rate control, tare before start, and simulated disconnection/reconnection).
- Real Bluetooth Low Energy (BLE) hardware (`實體藍牙電子秤`) is intentionally stubbed via `BleScaleDataSourceStub` and clearly reports **「尚未整合」**. No physical connection is ever claimed.

## How to Run

1. Open the project in Android Studio (or build via Gradle `gradle :app:assembleDebug`).
2. Run unit and Robolectric tests with `gradle :app:testDebugUnitTest`.
3. Launch the app on an Android device/emulator:
   - Explore the seeded demonstration recipe (**示範配方（可編輯範例，非業界標準）**) and seeded Demo sessions (**模擬範例**).
   - Go to **沖煮**, connect the Demo scale, complete the pre-brew checklist, press **歸零 (Tare)**, press **開始紀錄**, mark manual pour intervals or interference events, test a simulated disconnection if desired, finish, add notes, save, and open the report.

## Architecture

- `com.example.domain.model` — Pure Kotlin domain models (`Recipe`, `RecipeSnapshot`, `ScaleSample`, `BrewEvent`, `BrewSession`, `AlgorithmConfig`).
- `com.example.domain.measurement` — Pure Kotlin measurement & validation logic (`RecipeValidator`, `TargetCurveGenerator`, `RateEstimator`, `SessionAnalyzer`), independent of Android UI classes.
- `com.example.device` — Replaceable `ScaleDataSource` abstraction, `DemoScaleDataSource`, `BleScaleDataSourceStub`, and `ScaleDeviceManager`.
- `com.example.data` — Room database (`BrewLabDatabase`, entities & DAOs separating raw samples and events from session metadata), `BrewRepository`, `SeedData`, `AppPreferences`, and `BrewExporter` (CSV & JSON via `FileProvider` and SAF).
- `com.example.brew` — `BrewRecordingCoordinator` managing the single foreground sample subscription, timer, and lifecycle interruption tracking across navigation.
- `com.example.ui` — Jetpack Compose Material 3 screens (`首頁`, `沖煮`, `配方`, `紀錄`, `裝置設定`, `系統診斷`) and custom Canvas charts.

## Measurement Assumptions

- One scale supports the dripper, coffee grounds, and server together while the kettle is held separately.
- Change in total scale weight approximates externally added water (**累計注水量**, g); least-squares slope over a configurable rolling window (0.5 s, 1 s default, 2 s) using actual sample receive timestamps approximates pouring mass flow (**注水速率**, g/s).
- Does **not** measure coffee dripping out of the dripper separately, and weight data alone cannot determine flavour, extraction yield, water temperature, or pouring trajectory.
- Disconnections, clock resets, foreground interruptions, or interference events (`碰觸器具`, `移動器具`) break the valid analysis interval. Missing data is never zero-filled or interpolated, unavailable values display as `—`, and chart lines break across gaps.

## Where to Add a Real BLE Adapter

Implement the future BLE transport and protocol parser under `com.example.device.ble` implementing `ScaleDataSource` (replacing or alongside `BleScaleDataSourceStub` in `ScaleDeviceManager`). Keep all GATT UUIDs, packet framing, and vendor-specific parsing inside that device layer—never inside UI composables or `com.example.domain.measurement`.
