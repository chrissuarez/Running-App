# Running App - Heart Rate Monitor & Coach

A robust Android application designed to track heart rate (HR) during runs and provide real-time audio coaching to keep you in your target heart rate zone.

## 🆕 New Features & Fixes (February 25 - March 7, 2026)

- **Run/Walk Workout Progress Visibility (Live During Main Phase)**:
    - Added a dedicated Workout Progress card showing interval number, interval type, countdown, elapsed/planned interval time, and next interval preview.
    - Added live progress percentage and progress bar updates for structured sessions.
    - Added explicit walk-reason and HR-cap trigger visibility while an interval is running.
- **Run/Walk Interval Telemetry + Session Summary Metrics**:
    - Added per-interval telemetry persistence for Run/Walk sessions (trigger events, time-to-trigger, run duration before trigger, walk time during run interval).
    - Added trigger-quality metrics (`avgHrAtTriggerInInterval`, `avgRecoverySecondsAfterTriggerInInterval`) for richer debrief analysis.
    - Added a Run/Walk Interval Summary card in Session Detail with clean-interval rate, average completion, average time-to-trigger, longest clean interval, and graded completion bands.
- **AI Coaching Context & Safety Improvements**:
    - Added per-session AI training opt-out support (`includeInAiTraining`) and skip logic for excluded sessions.
    - Expanded Gemini context with structured Run/Walk adaptation metrics (graded completion rates, clean interval rate, drift slope, completion ratio, trigger/recovery stats).
    - Added a deterministic progression safety clamp: AI-generated next workout load is limited to 110% of the runner's max completed load in the last 30 days.
- **Coaching/Audio Reliability Fixes**:
    - Fixed run/walk recovery cue timing so RUN-interval recovery cues fire at the target zone low threshold.
    - Hardened audio ducking cleanup to always release cue audio focus after spoken coaching cues.

## 🆕 UI Redesign Update (March 8, 2026)

- **Evidence-led Workout-first UI refresh**:
    - Reworked the main/home flow for faster pre-run decisions (sensor readiness, session type, controls).
    - Replaced emoji top actions with explicit Material icon buttons and labels.
    - Added daylight-focused high-contrast Compose theme and shared UI tokens for spacing/tap targets.
- **New in-run workout player hierarchy**:
    - Tier 1: dominant phase + countdown + interval progress.
    - Tier 2: HR + zone status with simple below/in/above signalling.
    - Tier 3: lightweight secondary metrics (elapsed, distance, pace when available).
- **Interval transparency additions**:
    - Added workout timeline strip with current-position marker.
    - Planned transitions and HR-triggered events are visually distinct (not color-only).
    - Added low-noise coach chip with reason tags (`planned_transition`, `hr_too_high`, `hr_recovered`, `sensor_lost`, `unknown`).
- **Secondary screen consistency pass**:
    - Updated history/session detail typography contrast and touch-target sizing to match the new visual baseline.

## 🚀 Features

- **Real-time Monitoring**: Connects to BLE heart rate monitors (using standard HRS GATT services).
- **Foreground Service**: Continuous tracking even when the screen is off or the app is minimized, using a persistent notification.
- **Adaptive Coaching**:
    - **Target Zone Emphasis**: Alerts you when you are above or below your target zone. All five zones (Endurance, Moderate, Tempo, Threshold, Anaerobic) are fixed slices of your Max HR at 50/60/70/80/90%; you pick which one to train in, and Moderate (Zone 2) is the default.
    - **Warm-up Coaching Buffer**: Total audio silence for the first **8 minutes** of a session to allow physiological steady-state. Includes a safety override (Target High + 15 BPM).
    - **Cardiac Drift Detection**: Detects slow physiological HR rise after 20 minutes using a 10-minute baseline. Plays specialized "Steady Effort" cues with a 5-minute anti-nag cooldown.
    - **Run/Walk Coach Mode**: Specialized mode for beginner training with interval transition cues ("Transition to walking", "Start running, interval 2 of 6") and wider recovery hysteresis. A high heart rate is advice — "Ease off slightly" — and never an instruction to walk; the walks are the ones the workout prescribed.
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
    - **Share a run** as a GPX file (track, timestamps, per-point heart rate) from its detail page.
    - **Personal records**: Gold/silver/bronze at seven records — fastest 1 km, mile, 5 km, 10 km and
      half marathon, plus longest run and longest time. The distances are contested as a *best
      effort*: the quickest continuous stretch anywhere inside the run, walk breaks included. A run's
      detail page shows the medals it took, and nothing when it took none. Treadmill runs and runs
      with no GPS track contest the longest time only, since their distance was never measured
      against ground. Every run you have already recorded is scored once in the background at
      launch, so the book starts out complete; deleting a medal-holder promotes the next-best
      efforts behind it.
    - **Full archive**: Settings → Backup writes one ZIP — a GPX per run, an `archive.json` of
      everything GPX can't carry, and a snapshot of the database — to a folder you pick once
      (choose a Drive-synced one for offsite backup). Automatic monthly, keeping the last 3, plus a
      **Back up now** button and the last-backup time.
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
    - Adds interval transition cues (Walk/Jog) at the boundaries the workout sets.
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

## ✅ Recommended Test Workflow (Phone-first)

Use this order for reliable validation:

1. **Real phone + Android Studio deploy (primary)**:
    - Validate permissions, BLE scanning/connection, foreground service, audio cues, and navigation.
2. **Simulation mode (fast loop)**:
    - Validate in-run UI flow (countdown, timeline, coach chip, controls) without a strap.
3. **Real strap pass**:
    - Validate live HR updates, sensor freshness behavior, pause/resume/stop, and post-run summary/history.
4. **Quick outdoor readability check**:
    - Brightness high, bright-light glanceability for countdown/HR/zone.

### 10-minute smoke script

1. Launch app and grant permissions.
2. Turn on simulation and start a session.
3. Verify workout player hierarchy, timeline, and coach chip behavior.
4. Pause/resume, skip/start cooldown, then force stop.
5. Open History and Session Detail, then return.
6. Open Settings, save a small non-critical change, return.
7. Turn simulation off, connect real strap, start session, confirm live HR + sensor freshness.
8. Stop session and confirm latest summary renders correctly in history/detail.
