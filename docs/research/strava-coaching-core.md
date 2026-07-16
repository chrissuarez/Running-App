# How do Strava (and Garmin/Coros) shape HR zones, run setup, and audio cues?

Research for issue [#96](https://github.com/chrissuarez/Running-App/issues/96) (part of #95).
Researched 2026-07-16 against **primary sources only** — `support.strava.com` Help Center articles,
Garmin device owner's manuals + Garmin Support Center, and the COROS Help Center. No blogs, forums,
or secondary write-ups: where a fact exists only in secondary sources, it is recorded as a **GAP**
rather than asserted.

Builds on issue [#29](https://github.com/chrissuarez/Running-App/issues/29)'s
`docs/research/strava-feature-inventory.md` (branch `research/strava-feature-inventory`), which
covered Strava at a *feature* level. This document goes deeper on the two things that inventory
never covered — the **settings inventory** and the **record-screen flow** — plus zones and audio,
and the live-cueing gap that Strava does not fill.

**Reference devices**: Garmin claims are cited against the **Forerunner 265** owner's manual (the
alerting topics are byte-identical across current Forerunners, so they are safe to read as
Garmin-wide); Coros against **APEX 4 / PACE 4**. Behaviour is per-model — see each section.

## TL;DR — what the design needs to know

1. **Strava never publishes its default HR zone percentages.** Confirmed absent from every zone
   article. Zones *are* derived from **Max HR** (validating this map's decision), Max HR defaults to
   **220 − age** with a **190 bpm** fallback and a **230 bpm** cap. But any percentage table
   attributed to Strava would be invented. If we need defaults, they must be sourced from a named
   physiological convention and labelled as **our** choice, not Strava's.
2. **History reconciliation has an exact answer, and it's a hybrid**: *"Only the first time you set
   zones, Strava will recalculate historical activities. After that, changes apply only to future
   activities."* One backfill, then freeze forever.
3. **Strava has no target band and no live HR target.** Zones are a post-hoc analysis construct only.
4. **The record screen has effectively zero mandatory decisions.** Sport type is the only pre-run
   choice and it is pre-filled from last time. Title, description, photos and privacy are all
   deferred to the save screen.
5. **Strava's whole record-settings surface is ~8 items, only 3 of which are recording behaviour.**
   The governing pattern: **Strava exposes the on/off, never the threshold.** No auto-pause
   sensitivity, no GPS smoothing, no split-detection tuning.
6. **Auto-pause IS a setting** on both platforms — not a silent default. (Cycling defaults on;
   the *running* default is undocumented — do not assume.)
7. **The brief's claim "Strava has no live coaching cues" is right in spirit but too strong.** No
   physiological/pace coaching cue exists — but Live Segments, Instant Workouts step cues, and
   Off-Route Alerts are all live, event-driven, audible cues. See §8 for the suggested restatement.
8. **For the live-cueing gap, Coros is the better model to copy than Garmin.** Coros documents a
   pre-cue before each step, auto-advance semantics, undo-advance, cross-alert priority, and — the
   single most reusable finding here — a **30/30/60 anti-nag ladder** with a deliberate nag ceiling.
   Garmin's manuals leave range-alert repeat behaviour **entirely undocumented**.
9. **Garmin and Coros disagree on cue coexistence**, and it's a real citable divergence: an
   HR-targeted step **suppresses** the general HR alert on Garmin, but **does not** on Coros.

---

## Zones

### 1. Strava's default HR zone boundaries — the exact percentages, and percentage *of what*?

**GAP — this is the headline finding. Strava does not publish its default HR zone percentages anywhere in the Help Center.**

What Strava *does* state:

- There are **five** heart rate zones, named **Zone 1 – Endurance, Zone 2 – Moderate, Zone 3 – Tempo, Zone 4 – Threshold, Zone 5 – Anaerobic** ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)).
- Zones are derived from **Max HR**, not LTHR and not HRR: "Strava automatically calculates your heart rate zones based on your Max Heart Rate" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)). This confirms the consuming design's decision to derive zones from Max HR matches Strava's basis.
- Max HR itself defaults to **220 minus age**; "If no age is provided, Strava defaults to a Max Heart Rate of 190 bpm" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)).
- Max HR is capped: "The maximum values are: FTP, 500; Max Heart Rate, 230 bpm" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)).
- Zone descriptions are qualitative only — zones "categorize strain levels, ranging from Zone 1 (active recovery) to Zone 5 (near-maximum effort)" ([Heart Rate](https://support.strava.com/en-us/articles/15401762-heart-rate)).

**GAP:** No Strava primary source states the % of Max HR at which any zone boundary sits. Searched and read in full: [Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava) (both the `/hc/en-us/` and `/en-us/articles/15401569-` URL shapes), [Heart Rate Zones](https://support.strava.com/hc/en-us/articles/216917077-Heart-Rate-Zones), [Heart Rate](https://support.strava.com/en-us/articles/15401762-heart-rate), [Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features). None contains a percentage table. Percentages circulating for "Strava's default zones" exist only in secondary write-ups and are **not** primary-sourced. **Do not invent numbers and attribute them to Strava.** If the design needs default percentages, they must be sourced from a named physiological convention and labelled as *our* choice, not Strava's.

### 2. How zones are configured — what the athlete may set, free vs subscription, and what happens to historical activities when zones change

**Configuration:**

- Auto-calculated from Max HR by default; athlete may enter their own Max HR in profile settings under "My Performance" ([Heart Rate Zones](https://support.strava.com/hc/en-us/articles/216917077-Heart-Rate-Zones)).
- Fully custom boundaries via the "Custom Heart Rate Zones" option, "sliding the zone endpoints along the scale" ([Heart Rate Zones](https://support.strava.com/hc/en-us/articles/216917077-Heart-Rate-Zones)).
- "Subscribers have the option to set different heart rate zones for runs/other sports and rides. You can adjust your heart rate zones at any time on the Heart Rate Zones settings page" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)).
- Validation constraints: "zones cannot overlap, and consecutive zones must differ by at least one unit of distance" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)).

**Free vs subscription:** Training Zones are a subscriber feature — "Strava subscribers can use Training Zones to understand the load they're taking on" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)); "Athletes who subscribe to Strava can take advantage of heart rate analysis" ([Heart Rate Zones](https://support.strava.com/hc/en-us/articles/216917077-Heart-Rate-Zones)). The Subscription Features page lists: "Set your max heart rate or custom heart rate zones in your profile to see time spent in each zone during activities" ([Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features)).

**Historical recalculation — this is the load-bearing answer, and Strava is explicit:**

> "Only the first time you set zones, Strava will recalculate historical activities. After that, changes apply only to future activities."
> — [Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)

So Strava's model is a **hybrid**: one retroactive backfill on first configuration, then **freeze at record time** forever after. It is not "always recompute" and not "always freeze".

There is a manual escape hatch: athletes can "contact our support team with the necessary details. At this time, we are only able to recalculate auto-calculated heart rate, race, and power zones" ([Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)). Note the implication — Strava can recalculate **auto-calculated** zones on request but the sentence pointedly omits **custom** zones.

**GAP:** Strava never states *where* the frozen time-in-zone lives (recomputed-and-cached at upload vs stored stream + stored boundaries), nor what happens to historical activities if the athlete changes **Max HR** (as opposed to zone boundaries) after first setup. The sentence above says "set zones", which is ambiguous as to whether a Max HR edit counts. Searched all four zone articles listed in Q1; none disambiguates.

### 3. Does Strava have any notion of a "target band" or single focus zone distinct from the five zones?

**No — with a caveat about where "focus" does appear.**

- No Strava primary source describes a target band, target zone, or single focus zone for heart rate. Zones are presented purely as a five-way post-hoc classification of time — "the time spent in each zone helping you assess the training impact of an activity" ([Heart Rate](https://support.strava.com/en-us/articles/15401762-heart-rate)); Subscription Features frames the payoff as "to see time spent in each zone during activities" ([Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features)). Zones are an **analysis** construct, not a **live target** construct.
- The only place the word "focus" appears in a Strava setting is **Instant Workouts**, where the athlete picks a focus of "Build fitness, Stay active, Train for an event, or Recover" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)). That is a *recommendation-engine* input, not an HR band, and it does not map onto Zones 1–5.

**GAP:** I could not find any Strava article on real-time HR zone alerts or a target band. The absence is consistent across every zone article I read, and Strava's own Community Hub carries this as an open *feature request* rather than a shipped feature — but community-hub threads are secondary, so I am resting this answer on the silence of the primary docs, not on those threads. Treat as "not documented as existing" rather than "documented as not existing".

---

## Settings

### 4. The complete inventory of Strava's recording/audio settings

Everything below is from [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) unless otherwise cited. The record screen's settings live in a bottom sheet: "Expand the menu by sliding it up from the bottom of your screen", with a **Customize** entry at the bottom leading to **Audio Cues** ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)).

**Record-screen settings (bottom sheet):**

| Setting | What it does | iOS vs Android | Free/Sub |
|---|---|---|---|
| Sport type | "Strava will default to recording the activity type you recorded last, but you can change this by tapping the sports icon (for example, the shoe or bike) next to the start button" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | Same | Free |
| Strava Beacon | "will share your real-time location with up to three safety contacts" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | Same | Free on the Strava app; sub on compatible devices ([Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features)) |
| Heart Rate Sensor | Pair a Bluetooth Low Energy (BLE) HR device ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | Same | Free |
| Display Settings | "allows you to choose whether you'd like to prevent the screen from turning off while recording" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | **Android labels this "Screen Display"** ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | Free |
| Audio Cues | "can be enabled for the activity start/stop/pause, running splits, and Live Segments" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | Same entry point; see audio table | Free (Live Segment cues sub) |
| Auto-Pause | "for hands-free pausing, or you can choose to leave auto-pause off" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) | **Android**: "you can set Audio-Pause to off, for runs only, or for rides only" ([Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause)). **iOS**: reached via "select **Auto-Pause** on the next page" ([How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS)) | Free |
| Add Route | "select the routes Add Route icon in the lower right corner of the screen to open and view a list of your saved and created routes. Tap on the route you wish to follow, select Start, and it will load on the record screen for navigation" ([Following a Route](https://support.strava.com/en-us/articles/15401749-following-a-route)) | Same | Free |
| Off-Route Alerts | Toggled from navigation settings on the record screen; "Turning alerts off won't affect your ability to follow a route — you just won't be notified when you deviate" ([Strava Apple Watch App](https://support.strava.com/hc/en-us/articles/115000161184-Strava-Apple-Watch-App)) | Available on "Mobile Record (iOS and Android)" ([Strava Apple Watch App](https://support.strava.com/hc/en-us/articles/115000161184-Strava-Apple-Watch-App)) | Free |

**Audio Cues sub-screen** (Record > Customize > Audio Cues) — see the audio table in Q8.

**Notable iOS-only, outside the record screen:** Live Activities — "go to Settings > Strava > Live Activities, then turn on Allow Live Activities and More Frequent Updates" ([Strava Live Activities on iOS](https://support.strava.com/en-us/articles/15401559-strava-live-activities-on-ios)). This is an OS setting, not a Strava in-app one.

**The headline count: the entire record-screen settings surface is roughly eight items**, and only three of them (Audio Cues, Auto-Pause, Display Settings) are recording *behaviour*. The rest are sensors, routes, or sharing.

**GAP:** Strava's Help Center documents the record screen *by prose walkthrough*, not by an exhaustive settings enumeration, so I cannot guarantee this is literally every row in the current app build. There is no primary "settings reference" article. Item labels and platform differences beyond the two explicitly named above (Screen Display; the Android three-way auto-pause selector) are undocumented. Confidence is high on presence, lower on completeness.

### 5. Which recording behaviours ship as sensible defaults with no user setting at all?

**Auto-pause is NOT one of them — it is a setting on both platforms.** This is the direct answer to the "check auto-pause first" instruction.

- It is a setting on **Android**: "Tap on **'Settings.'** From here, you can set Audio-Pause to off, for runs only, or for rides only" ([Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause)).
- It is a setting on **iOS**: "Select **Record** from the bottom navigation menu. Expand the menu by sliding it up from the bottom of your screen, and select **Auto-Pause** on the next page" ([How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS)).

**Defaults:** Cycling auto-pause is **on by default** on both platforms — "Cycling Auto-Pause will be on by default, but can be turned off on your settings page" ([Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause)); "Cycling auto-pause will be on by default but can be turned off on your settings page" ([How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS)).

**GAP: the running auto-pause default is not stated on either platform.** Both auto-pause articles specify the cycling default and are silent on running. Searched both the Android ([Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause)) and iOS ([How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS)) articles plus [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity). **Do not assume running auto-pause defaults on.** The pointed specificity of "*Cycling* Auto-Pause will be on by default" invites the inference that running is not — but that is an inference, not a documented fact.

**Behaviours that genuinely have no user setting (sensible defaults):**

| Behaviour | Evidence | Source |
|---|---|---|
| Moving time derivation when auto-pause is off | Strava derives moving time server-side after upload rather than exposing a setting | [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) |
| Live elevation source | "On iOS devices, live elevation is derived from your device's motion sensor, while on Android devices, live elevation is derived from your GPS location" — platform-determined, not user-chosen | [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) |
| Auto-pause detection *method* | Running is "motion-based (accelerometer)"; cycling is GPS-speed based. The athlete toggles auto-pause on/off but cannot choose the method or thresholds | [Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause), [How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS) |
| Splits cadence in analysis | Splits are created automatically per mile/km from the athlete's unit preference — no separate setting | [Pace/Speed](https://support.strava.com/en-us/articles/15401806-pace-speed) |
| GPS strength indication | A halo around the location dot shows signal accuracy; not configurable | [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) |
| Sport type memory | "Strava will default to recording the activity type you recorded last" — inferred, not a setting | [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) |

The pattern worth carrying into the design: **Strava exposes the on/off, never the threshold.** No auto-pause sensitivity, no GPS smoothing setting, no split-detection tuning.

---

## Record screen / starting a run

### 6. What does the record screen make you decide before you start, and what does it defer or infer?

**Must be decided before start — exactly one thing, and even that is pre-filled:**

| Decision | Before or after? | Detail |
|---|---|---|
| Sport type | **Before** — but inferred by default | "Strava will default to recording the activity type you recorded last, but you can change this by tapping the sports icon (for example, the shoe or bike) next to the start button" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) |
| Route | **Optional, before or during** | Loaded via the Add Route icon; also reachable mid-run — "this option can be found by dragging the bottom tray up" ([Following a Route](https://support.strava.com/en-us/articles/15401749-following-a-route)) |
| Workout (Instant Workouts) | **Optional, before** | Pick a recommended workout, then "tap **Record**, and follow the workout" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)) |
| Sensors / Beacon / audio / auto-pause | **Before, but sticky** | Bottom-sheet settings that persist between runs; not per-run decisions ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)) |

**Deferred to the save screen (after the run):** title, photos, description, and privacy — "Title your activity, add photos, write a description, change your activity privacy controls" ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)).

**The design takeaway:** Strava's record screen is effectively **one-tap**. The only mandatory pre-run decision is sport type, and it is pre-filled from last time — so the true mandatory decision count is **zero**. Title, privacy and gear are all post-run.

**GAP:** Gear (shoe) selection is the one item I could not place cleanly. [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity) does not name gear on the save screen, and [Adding Gear to Your Activities](https://support.strava.com/hc/en-us/articles/216918727-Adding-Gear-to-Your-Activities-on-Strava) describes assigning gear and setting a **default** pair without stating whether the picker appears on the mobile save screen or only in later editing. Prior art (issue #29) established the default-gear mechanic; the *placement in the record flow* remains unconfirmed. Given a default pair exists, gear is best read as **inferred, not decided** — but the save-screen picker is unverified.

### 7. Does Strava have anything resembling session types / modes?

**Essentially no — with one qualified exception.**

There is **no** notion of a run "mode" (easy / intervals / tempo / long) that changes recording behaviour. The record screen has sport type and nothing else mode-like; the settings inventory in Q4 contains no mode selector ([Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)).

The closest thing is **Instant Workouts**, and it is worth being precise about what it is and is not:

- "Instant Workouts offers Strava subscribers a set of personalized activities to help them stay active throughout the week" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).
- Recommendations key off a chosen **focus** — "Build fitness, Stay active, Train for an event, or Recover" — plus sports preferences and activity history ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).
- Twenty new recommendations arrive each Monday, filterable by difficulty: **Easier, Steady, Harder, Variety** ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).
- During recording it surfaces steps: "Your current step will appear on the preview card — tap to expand it, then swipe right to view the full set of instructions" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).
- "Audio Cues are available for Run only" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).
- "Instant Workouts is available only to subscribers who are 18 or older" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)).

So Instant Workouts is a **recommendation + step-display layer**, not a recording mode: it does not change what is recorded, what is computed, or how the run is analysed. The difficulty labels are filters on a recommendation list, not session types. **This partially undercuts a flat "Strava has no session types" claim** — a subscriber running an Instant Workout does get on-screen step instructions and (on runs) audio, which is functionally mode-adjacent.

**GAP:** The article does not state what the Instant Workouts audio cues actually *say*, or whether the steps are time-based, distance-based, or effort-based. This is the single biggest hole in my Strava picture and it sits exactly where a "session types" design decision would want evidence. Searched [Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts) and [Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements); neither elaborates. Note also that Instant Workouts is **not listed** on [Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features) despite the Instant Workouts article calling it subscriber-only — the two primary sources are inconsistent.

---

## Audio

### 8. The complete set of Strava audio announcements, triggers, and cadence options — and does Strava have live coaching cues?

**Entry point:** Record > expand the bottom menu > "Customize" > "Audio Cues" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)).

| Announcement | Trigger | Cadence / options | Applies to | Free/Sub |
|---|---|---|---|---|
| Start / stop / pause | Activity state changes | On/off | "runs, rides, and walks" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)) | Free |
| Split-time updates | Distance milestone | "every half mile or kilometer or every full mile or kilometer (depending on the units of measure set in your preferences)" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)) | "only available for Run and Trail Run activities" ([Audio Announcements](https://support.strava.com/hc/en-us/articles/216917237-Audio-Announcements)) | Free |
| Live Segment Performance | Approaching / running / finishing a starred or popular segment | Three options: "off, voice, or chimes" ([Live Segments](https://support.strava.com/hc/en-us/articles/207343830-Live-Segments)) | "runs and rides" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)) | **Sub** ([Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features)) |
| Instant Workouts cues | Workout steps during a recorded workout | Undocumented | "Run only" ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)) | Sub ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)) |

**Platform notes:** iOS — media volume is controlled via Control Center. Android — "Three or four volume settings exist; Text-to-Speech language engine should match system language preferences" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)). Android's dependence on the system TTS engine is a real difference: Strava does not ship its own voice.

#### Verdict on the claim: "Strava has NO live coaching cues — its audio is only split summaries plus start/stop/pause events."

**PARTIALLY CORRECT — needs correcting on two counts.**

Correct in spirit: Strava has **no HR-based, pace-based, or effort-based coaching cue**. Nothing tells you to speed up, slow down, or hold a zone. The Q3 finding reinforces this — zones are post-hoc analysis with no live target. For an athlete on a free account with no route and no workout loaded, the claim is exactly true.

But the claim is **too strong as written**, because of:

1. **Live Segments** — genuinely real-time, genuinely performance-relative, and genuinely audible. "Live Segments is a subscription feature that gives you real-time updates on starred and popular segments while recording" ([Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)). On approach: "you will be transitioned from your recording screen to a Live Segment notification. You will hear an audible cue (if enabled) and see the segment name and your position relative to that segment" ([Live Segments](https://support.strava.com/hc/en-us/articles/207343830-Live-Segments)). During the effort, subscribers get a bird's-eye view with avatars tracking PR and KOM/QOM/CR progress, colour-coded — "red = ahead, blue = behind, gray = offscreen" — plus a **halfway mark notification** and final results at the end ([Live Segments](https://support.strava.com/hc/en-us/articles/207343830-Live-Segments)). Subscription Features describes it as "Your segment performance in real-time, plus comparisons to your PR and the current KOM/QOM/CR" ([Strava Subscription Features](https://support.strava.com/en-us/articles/15402044-strava-subscription-features)). A live "you are 4 seconds ahead of your PR" is a competitive cue, even if it is not a coaching cue.
2. **Instant Workouts audio** — "Audio Cues are available for Run only", enabling "a hands-free experience" while following a workout's steps ([Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts)). Step prompts during a structured workout are cue-like by any reasonable reading.
3. **Off-Route Alerts** — notification on route deviation, on "Mobile Record (iOS and Android)" ([Strava Apple Watch App](https://support.strava.com/hc/en-us/articles/115000161184-Strava-Apple-Watch-App)). A navigational cue.

**Suggested restatement:** *Strava has no physiological or pace coaching cues — nothing prompts effort changes. Its audio is split summaries plus start/stop/pause, extended by three event-driven cues: Live Segments (sub), Instant Workouts steps (sub, runs only), and Off-Route Alerts.*

**GAP — exact spoken text is undocumented across the board.** No Strava article states what a split announcement actually says or which metrics it includes (distance? split pace? average pace? elapsed time? HR?). The [Audio Announcements](https://support.strava.com/hc/en-us/articles/216917237-Audio-Announcements) article confirms the feature exists and where to toggle it, but "does not provide the exact verbatim text of what is spoken during each announcement, specific metrics announced at splits". Same for Live Segments voice and Instant Workouts cues. Searched both URL shapes of Audio Announcements plus [Live Segments](https://support.strava.com/hc/en-us/articles/207343830-Live-Segments) and [Instant Workouts](https://support.strava.com/en-us/articles/15401583-instant-workouts). If the design needs to match Strava's split phrasing, primary sources cannot supply it — it would need capture from the live app.

**GAP / CORRECTION TO PRIOR ART.** The issue-#29 inventory states "auto-pause events are announced when split announcements or Live Segment audio are on", sourced to [Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements). **I could not confirm this from that article or any other.** Re-read both URL shapes with a targeted query for auto-pause announcement behaviour; found no such statement. The article says only "you enable start/stop/pause announcements for runs, rides, and walks" — which is about *manual* start/stop/pause, and says nothing about auto-pause triggering an announcement or about a dependency on split/Live Segment audio being enabled. Treat that prior-art claim as unverified; it may have come from an older revision of the article.

---

## The gap — live cueing Strava lacks (Garmin & Coros)

**Reference devices and manuals.** Garmin behaviour is per-model, so everything below is cited against the **Forerunner 265 Series Watch Owner's Manual (EN-US)** unless stated ([webhelp index](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-54A017B7-95D1-4C96-A39F-AEA91B7ACE29.html); [full PDF](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/Forerunner_265_OM_EN-US.pdf)). Garmin publishes these topics under shared GUIDs across models — the *Setting an Alert* topic is byte-identical in the [Forerunner 965](https://www8.garmin.com/manuals/webhelp/GUID-0221611A-992D-495E-8DED-1DD448F7A066/EN-US/GUID-D68697C7-D321-4B42-8A52-5C9D257B58CE.html) and [Forerunner 165](https://www8.garmin.com/manuals/webhelp/GUID-607F08F6-33FC-40BF-9727-84E54043D82D/EN-US/GUID-D68697C7-D321-4B42-8A52-5C9D257B58CE.html) manuals, so the alerting model below is safe to treat as Garmin-wide for current Forerunners. The authoring side is cited against the platform-level [Creating a Custom Workout in Garmin Connect](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7) FAQ, which itself warns: *"Not all features and workout options are available on every device."*

Coros is cited against the **COROS Help Center**, using **APEX 4 / PACE 4** as the current reference where a page is device-scoped.

**Subscription gating.** Neither vendor gates the thing this app cares about. Garmin's Connect+ launch release states *"All existing features and data in Garmin Connect will remain free"* at $6.99/month or $69.99/year — the workout builder and structured-workout cueing predate Connect+ and are not listed among its premium features ([Garmin newsroom](https://www.garmin.com/en-US/newsroom/press-release/wearables-health/elevate-your-health-and-fitness-goals-with-garmin-connect/)). Coros describes its Workout Library as *"free official workouts"* with no subscription mentioned anywhere in the workout docs ([COROS](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)).

---

### 9. How do Garmin and Coros prescribe and cue a structured/interval session live — what does the watch say, when, and what does the athlete configure vs what's derived from the plan?

#### Garmin

**Authoring.** Workouts are built in Garmin Connect (app or web) and pushed to the watch; the watch is a player, not an authoring surface (except for the on-watch Intervals feature, below). Garmin's model is explicit: *"Every workout is made of Steps, and each step has a Duration and an optional Intensity Target."* Max **50 steps** per workout ([Creating a Custom Workout in Garmin Connect](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)).

Step **intensity/type** is a separate axis from duration and target — `Warm Up`, `Run / Bike / Swim / etc.`, `Recover`, `Rest`, `Cool Down`, `Other`. Garmin defines Recover as *"A low-intensity step between more strenuous steps"* and Rest as *"A step with no activity"* — they are distinct, and the distinction is load-bearing: warm-up and recovery steps *"have a lower impact"* on the post-run Workout Execution Score and *"The cool down step does not impact your workout execution score at all"* ([FR265 manual, p. 48](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/Forerunner_265_OM_EN-US.pdf)).

**The step model (duration x target) — all option names verbatim from [the Garmin Connect FAQ](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7):**

| Duration type (what ends the step) | Garmin's definition | Target type (goal within the step) | Garmin's definition |
|---|---|---|---|
| **Time** | *"The step lasts for a specific duration (e.g., 10 minutes)."* | **Pace** | *"Target a specific pace range (e.g., 8:00-8:30 min/mile)."* |
| **Distance** | *"The step lasts for a specific distance (e.g., 1 mile)."* | **Cadence** | *"Target a specific steps-per-minute or revolutions-per-minute range."* |
| **Lap Button Press** | *"The step ends only when you manually press the lap button on your watch."* | **Heart Rate Zone** | *"Target a specific heart rate zone (e.g., Zone 4)."* |
| **Calories** | *"The step lasts until you burn a specific number of calories."* | **Power Zone** | *"Target a specific power zone."* Runners only see this *"if you have a device that natively supports Running Power."* |
| **Heart Rate** | *"The step ends once your heart rate goes above or drops below a specified BPM."* | *(none)* | Target is explicitly *"optional"* — omitting it gives an open step. |
| **Power** | *"(Cycling/Running) The step ends once your power output goes above or drops below a specified wattage."* | | |

The two axes are genuinely independent: heart rate appears as *both* a duration type (bpm threshold ends the step) and a target type (zone to hold during it). Note the asymmetry worth stealing — **duration by HR is an absolute BPM**, but **target by HR is a zone number**, not bounds.

**Repeats.** *"you can group a series of steps and set them to repeat a certain number of times (up to 99)"*, and *"Some devices will offer the option to select Skip Last Recover after adding a repeat, allowing you to skip the last recover in a repeat when performing the workout"* ([FAQ](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)). That last one is a nice detail — it solves the "don't make me jog a recovery I'm never going to do" problem declaratively rather than making the athlete skip it live.

**What's derived vs configured.** Derived from the plan: step order, durations, targets, repeat expansion, and the estimated total — *"For workouts with steps based on time or distance, Garmin Connect may provide an estimated total time or distance… based on your historical pace for that activity type"*, and notably *"Workouts containing steps with other duration types (e.g., Lap Button Press, Heart Rate) will not show a total estimate"* — i.e. Garmin refuses to fake a duration it can't compute. HR-zone targets are derived from the athlete's zone table rather than restated per workout, so a zone-4 step means different bpm for different athletes ([FAQ](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)). Configured live by the athlete: essentially only *whether to advance a lap-press step* and the audio-prompt settings.

**What the watch does live.** The manual is thin here: *"Your watch can guide you through multiple steps in a workout"* and *"After you begin a workout, the watch displays each step of the workout, optional step notes, and the current workout data"* ([Starting a Workout](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-71017882-35BA-457C-9F94-E4BE2C882E48.html)).

**Modality.** Cues are visual + tone/vibration by default, with voice as an opt-in overlay. The audio layer is configured per-activity under Audio Prompts, and the relevant switches are *"To hear workout alerts play as an audio prompt, select **Workout Alerts**"*, *"To hear activity alerts play as an audio prompt, select **Activity Alerts**"*, and — the detail worth copying — *"To hear a sound play right before an audio alert or prompt, select **Audio Tones**"*, i.e. an earcon that precedes speech so the athlete's attention is captured before the words start. Voice is configurable by **Dialect** and **Voice** (*"to male or female"*). Audio prompts *"play on your connected Bluetooth headphones, if available. Otherwise, audio prompts play on your phone paired through the Garmin Connect app"*, and *"During an audio prompt, the watch or phone lowers the volume of the primary audio to play the announcement"* — ducking, not pausing ([Playing Audio Prompts During an Activity](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-D7092CFC-0284-4DEE-A89E-8D932F0F0ED3.html)). Tones vs vibration globally live under *"**Sound and Vibe**: Sets the watch sounds, such as button tones, alerts, and vibrations"* ([FR265 manual, p. 100](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/Forerunner_265_OM_EN-US.pdf)).

**Authored audio at the transition.** Garmin lets the *coach* supply the words rather than relying on TTS: *"On music-capable devices paired with Bluetooth headphones, you can add custom audio notes to your workout steps that will play as you transition between them"*, each *"up to 15 seconds long"* ([FAQ](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)). This is the only Garmin primary source that pins a cue explicitly to the **transition moment**.

**> GAP:** The Garmin manuals never state **what the watch actually announces** at a step transition — no example utterance, no template, no field list. "Workout Alerts" is named as a toggle and never specified. Any claim about Garmin's exact step-transition wording would have to come from a secondary source.

**> GAP:** Garmin's manuals are **silent on a countdown or pre-cue before a step ends**. The FR265 manual uses "countdown" only for the standalone countdown timer, recovery HR, and race-event glances — never for workout steps. There is no documented n-seconds-remaining warning. Contrast Coros, which documents one explicitly.

**> GAP:** The manual never states **which modality fires for a step transition specifically** (tone? vibration? both?). *Sound and Vibe* is a global, generically-worded setting; there is no per-cue modality matrix.

**On-watch intervals (no phone needed).** Garmin also ships a watch-local interval builder, a much smaller model than Connect's: *"Interval workouts can be open or structured. Structured repeats can be based on distance or time."* The athlete configures only four things — *"To set the interval duration and type, select **Interval**"*, *"To set the rest duration and type, select **Rest**"*, *"To set the number of repetitions, select **Repeat**"*, *"To add an open-ended warm up to your workout, select **Warm Up** > On"*. Two behaviours worth noting: *"TIP: All interval workouts include an open-ended cool down step"* (the cool-down is implicit, not authored), and completion is signalled — *"After you complete all of the intervals, a message appears."* Manual advance is always available: *"At any time, press BACK to stop the current interval or rest period and transition to the next interval or rest period"* ([Interval Workouts](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-6EC17A6A-ECF6-4620-AE7D-9BCD0114ED1C.html)).

**Capacity limits worth knowing.** *"Most devices can store a maximum of 25 custom workouts"* and *"Most devices only display the first 15 characters of a workout's name"* — Garmin explicitly warns that workouts sharing the first 15 characters may collide on-device ([FAQ](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)).

#### Coros

**Coros's docs are, unexpectedly, *better* than Garmin's on live cueing** — they specify the pre-cue, the advance semantics, and the anti-nag ladder that Garmin leaves unstated. They are thinner than Garmin on the authoring model (no step cap documented, no per-step "intensity/type" axis).

**Authoring.** *"From the Profile page, select Workout Library, then tap Create in the top right corner… Tap Add Exercise to start building your workout"* ([Create Custom Workouts in Your COROS App](https://support.coros.com/hc/en-us/articles/47285577958932-Create-Custom-Workouts-in-Your-COROS-App)). Workouts can also be synced in: *"Connect TrainingPeaks or another supported platform to COROS, and your planned workouts will automatically appear on your training calendar"* ([following workouts](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)).

**Watch out for the vocabulary inversion.** Coros calls the *duration* axis **"Target types"** and the *goal* axis **"Intensity types"** — the exact opposite of the intuitive reading and the reverse of Garmin's naming. Verbatim from [Create Custom Workouts](https://support.coros.com/hc/en-us/articles/47285577958932-Create-Custom-Workouts-in-Your-COROS-App), for Run and Bike:

| Coros "Target type" (= what ends the step) | Coros "Intensity type" (= the goal to hold) |
|---|---|
| **Distance** | **% Max Heart Rate** |
| **Time** | **% Heart Rate Reserve** |
| **Training Load** | **% Threshold Heart Rate** |
| **Open** | **Heart Rate** (absolute) |
| | **% FTP** (biking) |
| | **% Threshold Pace** (running) |
| | **Pace** (running) / **Speed** (biking) |
| | **Cadence** |
| | **Power** |
| | **Not set** |

Two structural differences from Garmin worth flagging for the design. First, Coros has **no lap-press duration type** for run/bike steps — an `Open` step plus a manual Back/Lap press is how you get the same effect. Second, Coros offers **Training Load** as a duration type, which Garmin has no equivalent of; the step ends when a modelled load figure is hit.

**Step categories.** *"For all workout types, you can choose from the following exercise categories: **Warm Up**, **Training**, **Rest**, and **Cool Down**. Running and Biking also support an **Interval** option, which is helpful for creating a series of alternating activity/rest intervals."* Note Coros has **Rest but no separate Recover** — a distinction Garmin makes and Coros collapses ([Create Custom Workouts](https://support.coros.com/hc/en-us/articles/47285577958932-Create-Custom-Workouts-in-Your-COROS-App)).

**Repeats.** *"Group into a set: Drag and drop one exercise on top of another, then use the + button to set the number of repetitions."* **> GAP:** no documented maximum repetition count (Garmin states 99).

**Derived vs configured.** Coros derives target bounds from the athlete's zones automatically: *"If you select an option that includes % pace or % heart rate, your COROS app will automatically calculate the pace/heart rate targets for you based on your pace/heart rate zones (found in the Profile page)."* The athlete configures, per-workout, on the watch **before** pressing Start ([following workouts](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)):

- **View** — *"to preview each stage of the workout"*
- **Auto Advance** — *"whether the watch automatically advances to the next stage of the workout when you complete a stage, or whether you have to manually press the Back/Lap button to advance between stages"*
- **Finish Pause** — *"whether the workout should automatically pause when you complete its final stage, or if the activity should continue tracking after completing the final stage until you pause and finish it yourself"*

**The pre-cue — documented, unlike Garmin.** *"Your watch will guide you through each stage of the workout, **with alerts before each stage to show you the upcoming targets**."* And, decisively: *"Your watch will alert you when the current stage is about to end and the workout is about to progress to the next one."* The countdown is independent of auto-advance: *"**Note:** If Auto Advance is turned off, your watch will still count down to the next stage, but it won't automatically advance until you press the Back/Lap button"* ([following workouts](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)). So the model is: *count down always; advance conditionally.*

**> GAP:** Coros documents *that* a pre-cue fires and *that* it shows upcoming targets, but **never states how many seconds before the step ends** it fires. Garmin doesn't document a pre-cue at all; Coros documents one without a number.

**Undo — a genuinely good idea.** *"Undo an advance: If you accidentally manually advance to the next stage of your workout and need to go back, you have a few seconds to press the Back/Lap button to cancel the transition and return to the previous stage."* Garmin has no documented equivalent. (Coros says *"a few seconds"* — no exact figure. **> GAP**.)

**Skipping.** *"If you need to skip the current stage of your workout or go back to a previous one, press the dial to pause the activity, and scroll down to 'View Workout' and select a previous or future stage"*, with a Pause Options shortcut so this doesn't require actually pausing ([following workouts](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)).

**Display takeover.** *"When following a structured workout on your watch, a new primary data page is added by default, corresponding with the specific stage of the session. To view the data pages you customized for this activity type, simply scroll the dial"* — the workout owns the primary screen and demotes the athlete's normal layout ([following workouts](https://support.coros.com/hc/en-us/articles/360044426251-How-to-Follow-Structured-Workouts-on-COROS-Watches)).

**Modality.** Screen + tone/vibration by default; voice is opt-in per-activity. *"The voice alert feature will broadcast **activity alerts, laps, workout details, turn alerts**, and more."* Audio output is a three-way choice — **Headphones Only**, **Always On** (*"Voice alerts will use headphones if they are detected. If no headphones are detected, they will be played from your phone's speaker"*), and **Watch Speaker** (APEX 4 only). Voice is available in *"English, Chinese, French, and Japanese"* only ([Receive Voice Alerts During Activities](https://support.coros.com/hc/en-us/articles/38299686686484-Receive-Voice-Alerts-During-Activities)). Music handling is device-dependent and documented: on APEX/VERTIX/NOMAD *"Any music currently playing will be paused for the duration of the Voice Alert, and will resume after the alert ends"*, whereas *"On PACE 3 and PACE Pro, the volume of music will be lowered but not paused completely"* — pause vs duck, split by model.

Tones and vibration are separable, per-category: *"**Tones**: Choose whether to enable Key & Dial, Message and Call alerts, Alarms, **Activity Alerts**, and General. **Vibrations**: Choose whether to enable Key Vibrations and **Alert Vibrations**"* ([Sounds and Tones Settings on APEX 4](https://support.coros.com/hc/en-us/articles/42042384839956-Sounds-and-Tones-Settings-on-APEX-4)). Coros exposes a finer-grained tone taxonomy than Garmin's single *Sound and Vibe* blob.

**> GAP:** Like Garmin, Coros **never states the exact words spoken** at a step transition. It says voice alerts *"broadcast… workout details"* and that the pre-cue *"show[s] you the upcoming targets"*, but no example utterance or template exists in the docs.

---

### 10. Do either offer live HR-zone alerts? What's configurable, what's the anti-nag behaviour, and how do zone alerts coexist with structured-workout cues?

**Both do.** And on this question the two vendors' docs are mirror images: **Garmin documents the coexistence rule precisely and the anti-nag behaviour not at all; Coros documents the anti-nag ladder precisely and to the second.**

#### Garmin

**Yes.** Heart Rate is one of Garmin's three alert classes, specifically a **range** alert. Garmin's taxonomy, verbatim ([Activity Alerts](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-E9B4413D-28EB-4054-8622-5CCD3F4571D2.html)):

- **Event alert:** *"An event alert notifies you one time. The event is a specific value."*
- **Range alert:** *"A range alert notifies you each time the watch is above or below a specified range of values. For example, you can set the watch to alert you when your heart rate is below 60 beats per minute (bpm) and over 210 bpm."*
- **Recurring alert:** *"A recurring alert notifies you each time the watch records a specified value or interval. For example, you can set the watch to alert you every 30 minutes."*

**The alert configuration surface** — the running-relevant rows from Garmin's own table ([Activity Alerts](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-E9B4413D-28EB-4054-8622-5CCD3F4571D2.html)):

| Alert Name | Alert Type | Description (verbatim) |
|---|---|---|
| **Heart Rate** | Range | *"You can set minimum and maximum heart rate values **or select zone changes**."* |
| Pace | Range | *"You can set minimum and maximum pace values."* |
| Cadence | Range | *"You can set minimum and maximum cadence values."* |
| Running Power | Event, range | *"You can set minimum and maximum power zone values."* |
| Distance | Event, recurring | *"You can set a distance interval."* |
| Time | Event, recurring | *"You can set a time interval."* |
| Run/Walk | Recurring | *"You can set timed walking breaks at regular intervals."* |
| Custom | Event, recurring | *"You can select an existing message or create a custom message and select an alert type."* |

**Zone number *or* bpm — both.** The Heart Rate row answers the question directly: *"minimum and maximum heart rate values **or** select zone changes"*, and the setup flow confirms the either/or — *"Select a zone, enter the minimum and maximum values, or enter a custom value for the alert"* ([Setting an Alert](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-D68697C7-D321-4B42-8A52-5C9D257B58CE.html)).

**Per-activity-profile: yes, unambiguously.** The path is *Hold UP > **Activities & Apps** > select an activity > the activity settings > **Alerts** > **Add New***, with the caveat *"NOTE: This feature is not available for all activities"* ([Setting an Alert](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-D68697C7-D321-4B42-8A52-5C9D257B58CE.html)). Alerts are scoped to the profile, and multiple alerts can coexist on one profile (*Add New* vs *"Select the alert name to edit an existing alert"*).

**The zones the alert references are themselves deeply configurable** — and, importantly, **per-sport**. Zones can be expressed four ways: *"Select **BPM**… Select **%Max. HR**… Select **%HRR** to view and edit the zones as a percentage of your heart rate reserve (maximum heart rate minus resting heart rate)… Select **%LTHR** to view and edit the zones as a percentage of your lactate threshold heart rate."* And *"Select **Sport Heart Rate**, and select a sport profile to add separate heart rate zones"* ([FR265 manual, p. 74](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/Forerunner_265_OM_EN-US.pdf)). Default fallback is stated: *"The default maximum heart rate is 220 minus your age"* ([FR265 manual, p. 73](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/Forerunner_265_OM_EN-US.pdf)).

**Coexistence with workout cues — Garmin suppresses the zone alert.** This is the single most useful sentence in the Garmin corpus for this design, from the Intensity Targets section: *"Your device will alert you if you are outside of your target zone, **ignoring other standard alerts (like general heart rate alerts) for that step**"* ([Creating a Custom Workout in Garmin Connect](https://support.garmin.com/en-US/?faq=wZ52AaLbLG2GC1Lxu2l4k7)). So the rule is: **a step's intensity target wins, and the profile-level HR alert is suppressed for the duration of that step** — scoped per-step, not per-workout. A step with no target presumably lets the general alert through, which is the sane reading, though the FAQ doesn't spell that case out.

**Anti-nag: this is the big hole.** The entire documented behaviour is one sentence: *"For event and recurring alerts, a message appears each time you reach the alert value. For range alerts, **a message appears each time you exceed or drop below the specified range** (minimum and maximum values)"* ([Setting an Alert](https://www8.garmin.com/manuals/webhelp/GUID-F41EAFB3-6CC9-42DE-9C6C-9E358DBB0671/EN-US/GUID-D68697C7-D321-4B42-8A52-5C9D257B58CE.html)).

**> GAP (the important one):** *"each time you exceed or drop below"* is **ambiguous between edge-triggered and level-triggered**, and Garmin never disambiguates. Read as an edge, it fires once per crossing; read as a level, it repeats while you're out of range. The manual states **no repeat cadence, no re-arm rule, no hysteresis/deadband, no debounce, and no minimum-duration-out-of-range threshold** for range alerts. This is exactly where the task brief expected Garmin's manuals to state a repeat cadence — they do not. Contrast this with Garmin's *recurring* alerts, where a cadence *is* specified (*"alert you every 30 minutes"*); the omission for range alerts is conspicuous rather than accidental. **Anything specific about Garmin's HR-alert repeat behaviour is unavailable from primary sources.** Coros is the only one of the two that can be copied here.

**> GAP:** Garmin does not document whether the range alert **re-fires on every excursion** or is throttled/suppressed after the first, nor whether re-entering the range resets anything.

#### Coros

**Yes**, and per activity mode. *"To set an activity alert, first open your desired activity mode on the watch. Before pressing Start, scroll down to Activity Alert and select from **Cadence, Distance, Heart Rate, Nutrition, Pace, Power, and Speed**. Activity Alerts are custom to individual activity modes, and not all options may be available. During the activity, your watch will alert you if you reach the preset distance/elevation gain, or if you are outside of the preset range"* ([Activity Tracking and Activity Settings — APEX 4](https://support.coros.com/hc/en-us/articles/41966392291220-Activity-Tracking-and-Activity-Settings)). The per-mode reference pages restate it identically — *"**HR Alert** Enable to receive alerts when the HR is outside of the preset zone"* ([Run](https://support.coros.com/hc/en-us/articles/360040257251-Run), [Trail Run](https://support.coros.com/hc/en-us/articles/360039841772-Trail-Run), [Track Run](https://support.coros.com/hc/en-us/articles/360039841832-Track-Run)) — each mode also exposing *"Restore Defaults"*.

**Configuration surface, Garmin vs Coros:**

| | Garmin (FR265) | Coros (APEX 4 / PACE 4) |
|---|---|---|
| HR alert exists | Yes, as a **range** alert | Yes, as **HR Alert** |
| High/low bounds | Yes — *"minimum and maximum heart rate values"* | Implied by *"outside of the preset zone"*; **not documented** |
| Zone number vs bpm | **Both** — *"or select zone changes"* | **> GAP — never stated** |
| Per-activity-profile | Yes — *Activities & Apps > activity > Alerts* | Yes — *"custom to individual activity modes"* |
| Multiple alerts per profile | Yes — *Add New* | Yes — Cadence/Distance/HR/Nutrition/Pace/Power/Speed |
| Zone basis configurable | Yes — **BPM / %Max. HR / %HRR / %LTHR**, plus per-sport zones | **> GAP — not documented for alerts** |
| Where configured | On watch, under activity settings | On watch, **before pressing Start** |
| Anti-nag cadence | **> GAP — undocumented** | **Documented to the second (below)** |
| Workout-cue coexistence | Target **suppresses** general HR alert for that step | Alerts **coexist**; only Pace is suppressed |

**> GAP:** Coros's docs say *"outside of the preset zone"* across every activity page but **never show the configuration screen or state whether the athlete enters bpm bounds or picks a zone number**. Coros's word "zone" here may mean "range" colloquially rather than "numbered training zone" — the docs do not resolve it. Garmin is explicit where Coros is not.

**Anti-nag — Coros documents a precise escalation-then-give-up ladder.** This is the most directly reusable finding in this whole section, and it comes from an FAQ answer rather than a spec page ([Receive Voice Alerts During Activities](https://support.coros.com/hc/en-us/articles/38299686686484-Receive-Voice-Alerts-During-Activities)), verbatim:

> *"Why didn't I hear a Voice Alert even though I was running outside of my target zone during a workout?*
> *If you are outside the target range for a short duration, you'll see a prompt on the watch screen. If you are outside this range for a longer time (at least 30 seconds), you'll receive an alert via the watch screen and voice alerts. If you remain outside the range for an additional 30 seconds longer, you'll receive another alert via the watch screen and voice alerts. If you remain outside the range for over 1 minute, the voice alert will not be repeated, and the alert will show on the watch screen only."*

Decoded, that is a four-stage ladder with a deliberate nag ceiling:

| Time outside target range | Screen | Voice |
|---|---|---|
| Brief excursion | Prompt | — (silent) |
| ≥ 30 s | Alert | **Alert** |
| ≥ 60 s (30 s more) | Alert | **Alert (2nd)** |
| > 60 s | Alert (persists) | **Never again** — voice gives up |

Three design principles fall out of this, all documented rather than inferred: **(1)** short excursions never speak — the 30-second floor is a debounce that stops a momentary spike from talking at you; **(2)** voice escalates at most **twice**; **(3)** past a minute the watch concludes you have heard it and *permanently downgrades to screen-only* for that excursion. Coros has explicitly decided that a nag which hasn't worked in 60 seconds is not going to work.

**Cross-alert arbitration.** Coros also documents priority between competing cues: *"Voice alerts may interrupt each other. More important reminders will interrupt less important ones; **this is intentional**"* ([Receive Voice Alerts During Activities](https://support.coros.com/hc/en-us/articles/38299686686484-Receive-Voice-Alerts-During-Activities)). Garmin documents no arbitration model at all.

**> GAP:** Coros documents this ladder only in the **Voice Alerts** FAQ, framed as *"outside of my target zone during a workout"*. It is **not stated whether the same 30/30/60 timing governs a plain HR Activity Alert outside a structured workout**, nor whether it applies to non-HR alerts (pace, cadence, power). The screen-side repeat cadence is also unstated — "the alert will show on the watch screen only" doesn't say whether it persists, re-fires, or latches.

**> GAP:** Coros does not document hysteresis/deadband on the range boundary (i.e. how far back inside the range you must return before the excursion is considered over and the ladder re-arms).

**Coexistence with structured-workout cues — Coros keeps both, and suppresses only the specific conflict.** Coros's rule is the opposite of Garmin's blanket suppression ([Activity alerts in structured workouts](https://support.coros.com/hc/en-us/articles/11219392861076-Activity-alerts-in-structured-workouts)), verbatim:

> *"**Running:** Regular activity alerts for distance, pace, cadence, heart rate, nutrition, and power are available during **all sessions, including warm up, training, rest and cool down**."*
> *"When completing structured workouts and training plans, the Activity Alert will **share the same settings** with your regular Run or Bike mode. Go to Run mode > Settings > Activity Alerts to add or change your alerts for structured workouts."*
> *"\*When the intensity goal of a structured workout is **Pace or Effort Pace**, then the regular Pace Alert will not affect the activity."*

So: activity alerts stay live through every step including rest and cool-down; there is **one settings store**, not a separate workout-mode config; and suppression is **surgical** — only the Pace alert is muted, and only when the step's own goal is pace-based. Notably, **an HR-targeted step does *not* suppress the general HR alert on Coros**, which is precisely what Garmin *does* suppress. That is a real, citable divergence in philosophy between the two vendors:

| | Garmin | Coros |
|---|---|---|
| Step has an HR-zone target | General HR alert **suppressed** for that step | General HR alert **still fires** |
| Step has a pace target | General pace alert **suppressed** for that step | Pace alert **suppressed** ✓ (agree) |
| Alert settings during workout | Profile-level alerts, overridden per-step | **Shared** with regular Run/Bike mode |
| Alerts during rest/cool-down steps | Not documented (**> GAP**) | Explicitly **on** — *"all sessions, including warm up, training, rest and cool down"* |

**> GAP:** Coros gives no rationale and no equivalent statement for cadence or power targets — only Pace/Effort Pace is called out as suppressed. Whether a cadence-targeted step suppresses the cadence Activity Alert is undocumented.

**> GAP:** Garmin never documents whether activity alerts are suppressed during **Rest or Recover steps** specifically (e.g. does a low-HR alert fire while you're deliberately jogging easy during a recovery?). Coros answers this explicitly; Garmin does not.

---

### Summary of gaps

**Garmin**
1. Exact words announced at a step transition — **not documented anywhere**.
2. Countdown / pre-cue before a step ends — **no such feature documented**; the word "countdown" is never used for workout steps.
3. **Range-alert repeat cadence / anti-nag — entirely undocumented.** *"each time you exceed or drop below"* is ambiguous between edge- and level-triggered. No hysteresis, debounce, re-arm rule, or minimum-excursion duration. This was the brief's key ask and Garmin's manuals do not answer it.
4. Per-cue modality (tone vs vibration vs both) for step transitions — not specified; only a global *Sound and Vibe* toggle.
5. Whether activity alerts are suppressed during Rest/Recover steps — unstated.
6. Whether an untargeted step lets the general HR alert through — implied but not stated.

**Coros**
7. Whether the HR Alert takes **bpm bounds or a zone number** — *"outside of the preset zone"* is never resolved; no config screen documented.
8. Zone basis (%Max/%HRR/%LTHR/bpm) for **alerts** — not documented (it *is* documented for workout *intensity types*).
9. **How many seconds before a step ends the pre-cue fires** — the pre-cue is confirmed to exist but never timed.
10. Exact spoken wording of voice alerts — not documented.
11. Whether the 30/30/60 ladder applies **outside** structured workouts, or to non-HR alerts — the ladder is scoped only to *"target zone during a workout"* in its source FAQ.
12. Screen-side repeat cadence after voice gives up at 60 s — persists? re-fires? latches? Unstated.
13. Hysteresis / re-arm behaviour on the range boundary — unstated.
14. Undo-advance window (*"a few seconds"*) and max repetitions per set — no numbers given (Garmin states 99 repeats).
15. No documented step cap (Garmin states 50).

**Where Coros beats Garmin on documentation:** the pre-cue's existence, auto-advance semantics, undo-advance, the anti-nag ladder, cross-alert priority arbitration, and alert behaviour during rest/cool-down steps. **Where Garmin beats Coros:** the step/target model naming and enumeration, zone-basis configurability, zone-vs-bpm alert config, and the per-step suppression rule.
