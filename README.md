# Running App - Heart Rate Monitor & Coach

An Android running app built around a chest strap: it tracks your heart rate and coaches you out
loud during the run, then keeps the run afterwards — your training plan and its stages, an AI coach
that prescribes the next long run, your training load, your records, your routes and your segments.
Everything lives on the phone — your runs and their heart-rate traces in a Room database, your
settings and your plan in DataStore, the Run Journal as a plain text file — with a monthly archive
that carries all three and can be restored from.

## 🆕 What's new (April – August 2026)

The app grew from a heart-rate coach into a training log in this stretch. The big ones, by area —
the dated sections below this one are older and are kept as history.

- **A training plan that moves you through it.** The plan is an ordered list of Stages, and you are
  in exactly one at a time. Every run writes down the Stage it was run under, so one Stage's work
  can never graduate the next. Where a Stage asks for something in numbers — a 5K in a time — the
  app measures it and decides it; where it asks for a judgement, the coach does. Finishing the last
  Stage records a Plan Completion rather than leaving you parked on it.
- **An AI coach that prescribes the next long run.** After a run it writes the intervals and target
  zone for your next Long run, and only that — it prescribes work, it does not configure the app.
  It is told your effort ratings, your notes, the weather, and where you stand against your Goals,
  and it holds off when you are carrying too much fatigue. A prescription stands on the runs it was
  shown, so deleting one of them takes it back.
- **Progress screen: Fitness, Fatigue and Form.** Fitness is your Effort Scores over 42 days,
  Fatigue the same over 7, and Form is the difference — how fresh you are today. Plus a weekly
  volume chart you can flip between distance, time and Effort, a Goals card, and a Max HR card that
  offers to recompute your zones when a run beats the number you are training on.
- **Effort Score on every run that wore a strap.** What a run cost you, as one number: every second
  weighted by the zone it was spent in, never off an average, so a run/walk session scores its
  running as running. A run that recorded no heart rate has no score rather than a zero.
  Your whole history was scored in the background so the numbers start out complete. A week holding
  a run that wore no strap says it is only partly measured, rather than reading as a lighter week.
- **Segments.** Name a stretch of ground — "Cemetery Hill" — cut out of a run you actually ran, and
  every run that crosses it is timed against it, with the quickest marked PR. Creating one measures
  the whole of your history against it, so it arrives with its efforts already on it.
- **Routes.** A library of courses you keep, imported from GPX — from the picker, or by sharing a
  `.gpx` to the app from anywhere else. A file that isn't one is refused with a reason.
- **Your own words on a run.** Rate the effort out of ten and leave a note, on the sheet at the
  finish or on the run's page for ever afterwards. Mark a run a **Walk** and it still builds fitness
  in full but pays only a quarter of the fatigue — and it takes no records and graduates no Stage.
- **Treadmill runs count.** Type in the distance the console showed and it counts like any other:
  your pace, your weekly volume, what the coach sees, and the longest-run record.
- **Personal records and medals.** Gold, silver and bronze at seven records. The five at fixed
  distances — 1 km, mile, 5 km, 10 km, half marathon — are contested as the quickest continuous
  stretch anywhere inside a run; the other two, longest run and longest time, rank whole runs. Your
  existing history was scored once at launch, and deleting a medal-holder promotes what was behind it.
- **Export and backup.** Share a run as **.FIT** — the one Garmin Connect reads without re-deriving
  anything — or as GPX. Separately, a monthly **archive** writes the whole database, plus a GPX for
  each finished run that has a usable track, to a folder you pick once, and can be restored from.
- **A richer run page.** The route drawn on a map, coloured by heart-rate zone, with a fullscreen
  view; drag across the chart and a dot follows you along the route. History rows carry route
  thumbnails and the medals the run took.
- **Voice cues that queue.** Every spoken cue goes through one ordered queue, so nothing is cut off
  mid-sentence and nothing is dropped — including a "Halfway. Turn around." on an out-and-back.
- **A Run Journal.** The app writes its own plain-text record of what decided whether a run was
  recording — starts, pauses, the foreground promotion being taken or refused, the strap arriving or
  leaving. Android's log holds about two hours; a run plus the walk home is longer, so the minute
  that would name a cause had been rolling off before anyone could look.

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
- **Training Plan & AI Coach**:
    - **Stages**: The plan is a fixed, ordered list of blocks, and you are in exactly one at a time.
      Every run writes down the Stage it was run under when you press START, so one Stage's work can
      never graduate the next, and a run carrying no Stage answers nothing. Later Stages show a
      padlock; the one you are in never does.
    - **Graduating**: A Stage asks for something before it will let go of you. Where it is written in
      numbers — a 5K in a time — the app measures it and decides it, and the coach is fenced out;
      where it holds a judgement, such as "4 weeks of consistent Zone 2 training", the coach decides.
      It is asked once you can no longer change what the run was, never at STOP, so a run you are
      about to mark a Walk cannot graduate anything. Granted forwards only and never taken back.
    - **Tests**: The one workout that exists to answer a Stage's requirement — a 5K flat out, no
      warm-up or cool-down. The app says one is due three weeks after the last, and holds off while
      your Form is low. It is a prompt, never a gate: it stays pickable either way.
    - **Prescriptions**: After a run the AI coach writes the intervals and target zone for your next
      **Long** run, and only that — it prescribes work, it does not configure the app. It is told
      your effort ratings and notes, the weather, and where you stand against your Goals, and it
      holds off prescribing more when you are carrying too much fatigue. A prescription stands on the
      runs it was shown, so deleting one of them takes it back to the previous one.
    - **Debrief**: The sentences shown after a run. One slot, two writers, and the card says which —
      the coach explains its prescription, and the app writes its own for the things that are not the
      coach's to judge, such as a Stage granted or a Test that missed its bar.
- **Progress & Training Load**:
    - **Fitness, Fatigue and Form**: Fitness is your Effort Scores averaged over 42 days, Fatigue the
      same over 7, and Form is yesterday's Fitness less yesterday's Fatigue — how fresh you are this
      morning, before today's run has cost anything. Above +10 is fresh, below −10 is fatigued.
    - **Effort Score**: What a run cost you, as one number — every second weighted by the zone it was
      spent in, and nothing below Zone 1. Never taken off a run's average heart rate, which is what
      lets a run/walk session score its running as running. Runs finished before the score existed
      were scored afterwards from the beats they kept, so history carries the same number.
    - **Weekly volume**: A bar per week, flipped between distance, time and Effort. A week holding a
      run that wore no strap is marked **partly measured** — its total is a floor, never a ceiling.
    - **Goals**: A standing target you set yourself — distance, time or number of runs, per week,
      month or year. Recurring, with no end date, and measured from your runs on read, so editing one
      re-measures the period you are in. A goal is yours and not the plan's: it graduates no Stage.
    - **Max HR card**: When a run's peak beats the maximum your zones are built on, the card says so
      and offers to recompute them.
- **Session Management**:
    - **Phases**: Supports **Warm-up**, **Main Workout**, and **Cool-down** phases.
    - **Run Modes**: Choose between **Treadmill** (HR only) and **Outdoor** (GPS tracking).
    - **Treadmill distance**: A treadmill run has no GPS, so you tell it the distance — the number
      the console showed, typed into the sheet at the end of the run or onto the run's own page
      later, and correctable there whenever. It counts as a distance like any other: your pace, your
      weekly volume, what the coach sees, and the longest-run record. The five fastest-* records
      still need a GPS track, because those are the quickest stretch found *inside* a run. A run
      nobody stated a distance for shows a dash rather than 0.00 km.
    - **Simulation Mode**: Test coaching logic and UI without a physical heart rate strap using realistic mock data.
    - **GPS Tracking**: Records distance and calculates pace using a 15-second sliding window for stability.
    - **Split Announcements**: Automatic voice alerts for every 1km covered.
    - **One Voice Queue**: Every spoken cue goes through a single ordered queue — navigation, then
      instructions (interval and phase changes, auto-pause), then coaching, then information
      (splits, turnaround). Cues play one at a time, back to back; nothing is cut off mid-sentence
      and nothing is dropped.
    - **Turnaround Cue**: On an outdoor run following a plan, "Halfway. Turn around." once, at half
      the run's total moving time — warm-up to cool-down, so an out-and-back gets you home. It
      takes its turn in the cue queue rather than cutting off an interval cue, moves with a pause
      or a skipped warm-up, and says nothing at all if you skip to the cool-down. Toggle in
      Settings, on by default.
    - Start, Pause, Resume, and Stop controls with immediate UI synchronization.
- **History & Data**:
    - All sessions and high-resolution HR samples are saved locally to a Room database.
    - View past workout summaries including Avg BPM, Max BPM, and **Time in Zones breakdown**.
    - **The run's own page**: its route drawn on a map and coloured by heart-rate zone, with a
      fullscreen view; drag across the pace/HR/elevation chart and a dot follows you along the route.
      History rows carry a route thumbnail and the medals the run took.
    - **How it felt**: rate the effort out of ten and leave a note — on the sheet at the finish, or on
      the run's own page for ever afterwards. Your words, kept alongside the run; they never change
      what your heart rate says about it, and the coach is told them.
    - **Mark a run a Walk**: one mark on a whole run, never inferred. A walk builds Fitness in full
      but pays only a quarter of its score into Fatigue, because the fatigue that degrades form is
      largely mechanical. It counts towards Goals and fills the weekly bars, but takes no record of
      any kind, completes no prescribed workout and graduates no Stage. Marking one from three weeks
      ago moves every Fitness, Fatigue and Form number from that day forward — they are a live read
      of the truth, and the alternative is freezing numbers we know to be wrong.
    - **Share a run** from its detail page, as either file:
        - **Garmin (.fit)** — the run's own summary (distance, duration, moving time, heart rates,
          climb), its own kilometre splits as laps, and a moment for every second that recorded a GPS
          fix or a heart rate — so a second that recorded neither has none. A run with no GPS at all
          — a treadmill run, or one that lost the sky — is still a whole file, with its heart-rate
          trace intact. This is the one Garmin Connect reads without re-deriving anything.
        - **GPX** — track, timestamps and per-point heart rate. The portable option, for everything
          that isn't Garmin. A run with no GPS track can't be one, so it isn't offered.
    - **Personal records**: Gold/silver/bronze at seven records — fastest 1 km, mile, 5 km, 10 km and
      half marathon, plus longest run and longest time. The distances are contested as a *best
      effort*: the quickest continuous stretch anywhere inside the run, walk breaks included. A run's
      detail page shows the medals it took, and nothing when it took none. A treadmill run
      contests the longest run and the longest time — a stated distance is a real distance — but
      never the five fastest, which need a track to find a stretch inside. A run with no GPS track
      and no stated distance contests the longest time only. Every run you have already recorded is scored once in the background at
      launch, so the book starts out complete; deleting a medal-holder promotes the next-best
      efforts behind it.
    - **Full archive**: Settings → Backup writes one ZIP — a GPX for each finished run that has a
      usable track, an `archive.json` of everything GPX can't carry, and a snapshot of the database —
      to a folder you pick once (choose a Drive-synced one for offsite backup). Automatic monthly,
      keeping the last 3, plus a **Back up now** button and the last-backup time. **Every run is in
      the archive** either way: a treadmill run, one still being recorded, or one whose fixes were
      all too vague to trust is carried by the database snapshot and `archive.json` rather than by a
      GPX, because a GPX of a run that went nowhere would be an empty file with a name on it.
    - **Restore**: pick an archive and the app opens the database from it before swapping it in, so a
      file that cannot be read is refused rather than leaving you with nothing. History restored from
      an older file is re-banded on the Max HR it was actually recorded under, not today's.
    - **Run Journal**: the app's own plain-text record of what decided whether a run was recording —
      the run starting, pausing and stopping, the foreground promotion being taken, refused or handed
      back, the service coming and going, the strap arriving or being given up on. It exists because
      Android's log buffer holds about two hours and a run plus the walk home is longer, so the
      minute that would name a cause had rolled off before anyone looked. Bounded, rolled, and
      carried in the archive; nothing in the app reads it back to make a decision.
- **Routes**:
    - **Route library**: Home → Open Routes lists every course you keep, with its distance and its
      elevation gain. A file that carried no heights says so rather than showing 0 m.
    - **Import GPX**: from the picker on the Routes screen, or by choosing this app from another
      app's share/open sheet on a `.gpx` — a download, an email attachment, a Strava or Komoot
      export. Both land in the same place. A route takes its name from the GPX, or from the
      filename if the file doesn't name itself.
    - Rename and delete. Deleting a route never touches a run: routes and history are unconnected.
    - A file that isn't a GPX, is damaged, or holds no route is refused with a message saying why,
      and nothing is saved.
- **Segments**:
    - **Cut one from a run you ran**: Name a stretch of ground — "Cemetery Hill" — marked out on a
      past run's own track. Never drawn freehand, and never taken across a pause or lost signal,
      because the straight line over one is ground nothing witnessed. The geometry is copied onto the
      segment, so deleting the run it came from keeps the place.
    - **Every crossing timed**: A run holds one effort for each time it went over the stretch. It
      counts as a crossing if you pass within about thirty metres of the start, stay on the line — a
      GPS blip flung sideways for a second is forgiven, a shortcut is not — and come out within about
      thirty metres of the end. The time is the wall clock between the two gate crossings, worked out
      between the fixes either side of each gate rather than at the nearest one.
    - **Arrives with its history on it**: Creating a segment measures your whole history against it,
      so its efforts and its PR are there the moment you save it.
    - Marking a run a Walk takes its efforts off every segment; unmarking measures them again. A
      treadmill run holds none, because there is no track to put to the ground.
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
- **Maps**: Mapbox (route preview, fullscreen, segment cutting).
- **Charts**: Vico (Compose charting).
- **AI**: Google Gemini, for the coach's prescriptions and debriefs. Sharing is **on by default**
  and is turned off in Settings. Each run records whether it was on at the moment you pressed
  START, and that stored answer — not today's setting — is what decides whether that run can ever
  be sent. There is no separate per-run switch.
- **Background Work**: Android Foreground Service, plus WorkManager for the monthly archive.
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
