# Running App - Heart Rate Monitor & Coach

A robust Android application designed to track heart rate (HR) during runs and provide real-time audio coaching to keep you in your target heart rate zones (specifically optimized for Zone 2 training).

## 🚀 Features

- **Real-time Monitoring**: Connects to BLE heart rate monitors (using standard HRS GATT services).
- **Foreground Service**: Continuous tracking even when the screen is off or the app is minimized, using a persistent notification.
- **Adaptive Coaching**:
    - **Zone 2 Emphasis**: Alerts you when you are above or below your target heart rate.
    - **Smart Persistence**: Avoids "jittery" alerts by requiring the heart rate to stay outside a zone for a configurable duration (Persistence) before triggering a cue.
    - **Hysteresis & Cooldown**: Prevents back-to-back voice cues with a customizable cooldown period.
    - **Voice Cues**: Text-to-Speech (TTS) alerts with "Short" or "Detailed" styles.
- **Session Management**:
    - Start, Pause, Resume, and Stop controls.
    - Tracks active vs. paused time.
    - Automatic reconnection logic with exponential backoff if a device disconnects.
- **History & Data**:
    - All sessions are saved locally to a Room database.
    - View past workout summaries including Avg BPM, Max BPM, and Time in Target Zone.
- **Customizable Settings**:
    - Define Max HR and target Zone 2 ranges.
    - Fine-tune coaching behavior (cooldowns, persistence, voice style).

## 🛠️ How it Works

### 1. Connecting
Upon starting the app, Grant the necessary Bluetooth and Notification permissions. Tap **Scan / Start** to find your BLE heart rate strap. Select your device to establish a GATT connection.

### 2. Monitoring
Once connected, the app enters the **Monitoring** state.
- **Heart Rate Calculation**: The app processes standard BLE HR measurement packets (supporting both 8-bit and 16-bit values).
- **Smoothing**: A 5-second moving average is calculated to provide stable coaching decisions.

### 3. Coaching Logic
The coaching engine monitors your **Smoothed BPM**:
- **High Persistence**: If your HR exceeds the `Zone 2 High` threshold for `X` seconds, the app plays an "Ease off" cue.
- **Low Persistence**: If your HR drops below the `Zone 2 Low` threshold for `Y` seconds, the app plays a "Faster" cue.
- **Cooldown**: After a cue is played, the coach remains silent for the `Cooldown` duration to allow your body to react.

### 4. Data Storage
Every second of your session is recorded as an `HrSample` in the local database. When a session is finalized (Stopped), a summary is generated and saved as a `RunnerSession`.

## 💻 Technical Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Concurrency**: Kotlin Coroutines & Flow
- **Persistence**: 
    - **Room**: Structured workout data (Sessions & Samples).
    - **DataStore**: User preferences and training settings.
- **Background Work**: Android Foreground Service.
- **Protocol**: Bluetooth Low Energy (Standard Heart Rate Profile).
- **Audio**: Android Text-to-Speech (TTS) with Audio Focus management.

## 📋 Requirements

- Android device with Bluetooth LE support.
- Compatible BLE Heart Rate Strap (Polar, Garmin, Wahoo, etc.).
- Location permissions (required for BLE scanning on older Android versions) and Near Device permissions (Android 12+).
