# Running App

An Android heart-rate coach: a Bluetooth chest strap feeds a live run, the app
coaches the runner through it by voice, and records it. This glossary fixes the
words the code and the issues should use for that.

## Language

**Run**:
One recorded outing, from the moment the runner presses START until it is
stopped or auto-stops. A live thing before it is a saved one: it holds the
clock, the Phase it is in, and everything counted so far.
_Avoid_: session, activity

**Workout**:
The planned shape a Run may follow — its intervals, durations, and targets —
taken from the training plan.
_Avoid_: session, plan (the Plan is the whole schedule of Workouts)

**Phase**:
Which of a Run's three stretches it is in: warm-up, main, or cool-down. Every
Run has all three in that order; only the main one is open-ended.
_Avoid_: stage (a Stage is a block of the training Plan), segment

**Interval**:
One run or walk stretch inside the main Phase, repeated as the Workout
prescribes. Only a Run following a Workout has any.
_Avoid_: rep, segment, split (a Split is a kilometre of distance)

**Split**:
One completed kilometre of a Run, and the pace it was covered at. Measured by
distance, unlike an Interval, which is measured by time.
_Avoid_: lap, mile

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
