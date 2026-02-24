# Running App - Heart Rate Monitor & Coach

A robust Android application designed to track heart rate (HR) during runs and provide real-time audio coaching to keep you in your target heart rate zones (specifically optimized for Zone 2 training).

## 🆕 New Features & Fixes (February 24, 2026)

- **Session Type Routing Hardening**:
    - Fixed a path where sessions launched from Manage Devices could lose the selected mode.
    - Service now resolves session type with safer fallback order and explicit source logging.
- **Zone 2 Isolation Improvements**:
    - Zone 2 sessions now stay in Zone 2 behavior (zone-focused cues only).
    - Prevented Run/Walk interval-style behavior from leaking into Zone 2 flows.
- **AI Evaluation Safety Gate**:
    - Added a repository-level guard so AI plan adjustments only run for finalized **Run/Walk** sessions.
    - Non-Run/Walk sessions now skip Gemini evaluation with explicit logs.
- **Simulation Stability Fixes**:
    - Reworked simulation toggling to avoid duplicate DB session creation.
    - Toggling simulation during an active run now preserves session continuity (session id, phase, and elapsed time).
    - Added explicit simulation action handling for bound/unbound service paths.

## 🚀 Features

- **Real-time Monitoring**: Connects to BLE heart rate monitors (using standard HRS GATT services).
- **Foreground Service**: Continuous tracking even when the screen is off or the app is minimized, using a persistent notification.
- **Adaptive Coaching**:
    - **Zone 2 Emphasis**: Alerts you when you are above or below your target heart rate.
    - **Warm-up Coaching Buffer**: Total audio silence for the first **8 minutes** of a session to allow physiological steady-state. Includes a safety override (Target High + 15 BPM).
    - **Cardiac Drift Detection**: Detects slow physiological HR rise after 20 minutes using a 10-minute baseline. Plays specialized "Steady Effort" cues with a 5-minute anti-nag cooldown.
    - **Run/Walk Coach Mode**: Specialized mode for beginner training with tailored interval cues ("Walk until breathing settles", "Transition to a light jog") and wider recovery hysteresis.
    - **Smart Persistence**: Avoids "jittery" alerts by requiring the heart rate to stay outside a zone for a configurable duration before triggering a cue.
    - **Hysteresis & Cooldown**: Prevents back-to-back voice cues with customizable cooldown periods.
- **Session Management**:
    - **Phases**: Supports **Warm-up**, **Main Workout**, and **Cool-down** phases.
    - **Run Modes**: Choose between **Treadmill** (HR only) and **Outdoor** (GPS tracking).
    - **Simulation Mode**: Test coaching logic and UI without a physical heart rate strap using realistic mock data.
    - **GPS Tracking**: Records distance and calculates pace using a 15-second sliding window for stability.
    - **Split Announcements**: Automatic voice alerts for every 1km covered.
    - Start, Pause, Resume, and Stop controls with immediate UI synchronization.
- **History & Data**:
    - All sessions and high-resolution HR samples are saved locally to a Room database.
    - View past workout summaries including Avg BPM, Max BPM, and **Time in Zones breakdown**.
- **Device Management**:
    - Prioritizes manually selected BLE devices.
    - Robust background reconnection logic.
    - Manage and rename saved devices.

## 🛠️ How it Works

### 1. Connecting
Upon starting the app, Grant permissions for Bluetooth, Location, and Notifications. Tap **Scan** to find your BLE heart rate strap. The app prioritizes explicitly selected devices and persists them for future sessions.

### 2. Monitoring
Once connected, the app enters the tracking state.
- **Heart Rate Calculation**: Processes standard BLE HR measurement packets (8-bit and 16-bit).
- **Smoothing**: A 5-second moving average is used for all coaching decisions.

### 3. Coaching Logic (The Rules Engine)
The coaching engine employs several sophisticated filters:
- **Warm-up Buffer**: The first 8 minutes of every run are silent to let your heart rate stabilize, preventing annoying cues during your natural ramp-up.
- **Safety Override**: If your HR exceeds your target by 15+ BPM, the silence is broken immediately to warn of over-exertion.
- **Cardiac Drift Detection**: 
    - At the 10-minute mark, the app captures your steady-state **Baseline HR**.
    - After 20 minutes, if your HR rises slightly above your target but is within 12 BPM of your baseline, the app recognizes this as physiological drift.
    - Instead of "Ease off", it plays a helpful drift cue: *"Heart rate drifting up. Keep effort steady, or take a short walk break."*
- **Run/Walk Coach Mode**: 
    - Changes cues to interval-based instructions (Walk/Jog).
    - Employs **Wider Hysteresis**: In this mode, the app tells you to start jogging again as soon as your HR drops to the *midpoint* of your target zone, preventing your HR from dropping too low during walk intervals.

### 4. Session Phases
A session is divided into three distinct phases:
- **Warm-up**: Silent tracking with visual zone feedback.
- **Main Phase**: The core workout where coaching cues (Buffer/Drift/Run-Walk) are active.
- **Cool-down**: A period of low-intensity recording with silent coaching.

### 5. Data Storage & Analytics
Every second of your session is recorded as an `HrSample`.
- **Zone Breakdown**: The app tracks time spent in all 5 HR zones.
- **Persistence**: Detailed summaries are stored in a Room database for historical review.

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
