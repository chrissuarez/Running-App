# Running App

An Android heart-rate coach: a Bluetooth chest strap feeds a live run, the app
coaches the runner through it by voice, and records it. This glossary fixes the
words the code and the issues should use for that.

## Language

**Run**:
One recorded outing, from the moment the runner presses START until it is
stopped or auto-stops.
_Avoid_: session, activity

**Workout**:
The planned shape a Run may follow — its intervals, durations, and targets —
taken from the training plan.
_Avoid_: session, plan (the Plan is the whole schedule of Workouts)

**Prescription**:
What the AI coach writes after a Run: the intervals and target zone for the next
Workout, and nothing else. Dated, replaceable, and never a setting — the coach
prescribes work, it does not configure the app.
_Avoid_: AI adjustments, AI intervals

**Strap**:
The Bluetooth heart-rate sensor worn on the chest. A sensor the Run reads, not a
gate the Run waits on.
_Avoid_: device, monitor, HRM

**Acquisition**:
The attempt to get a Strap connected: scanning, connecting, or retrying. In
flight until the Strap is connected, given up on, or blocked.
_Avoid_: pairing, connecting (as a noun), chasing

**Promotion**:
The state of running in Android's foreground — persistent notification and wake
lock together. Earned by a live Run or an in-flight Acquisition.
_Avoid_: foreground state (Promotion is the whole of it, not the Android flag)

**Simulation**:
A developer mode that feeds fake heart rate in place of a Strap, so the app can
be exercised without hardware.
_Avoid_: demo mode, test mode, fake mode
