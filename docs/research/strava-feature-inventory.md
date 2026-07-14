# Strava Feature Inventory (Runner-Facing)

Research for issue #29. Sources are Strava primary sources only: `strava.com` product pages and
`support.strava.com` Help Center articles (Strava's official documentation, including its
glossary-style Subscription Features article). Researched 2026-07-14.

**Relevance tags** (for a solo, local-first, no-backend runner's app):

- `relevant` — works as-is for a solo runner with no social graph.
- `relevant-with-adaptation` — the core idea works but must be reframed (e.g. community data → personal data).
- `not-applicable` — depends on Strava's social graph, community data pool, or hosted backend in a way that can't be meaningfully reproduced locally.

**Free vs paid**: "Free" = available to all Strava accounts; "Sub" = requires a Strava
subscription, per Strava's [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features) article unless otherwise cited.

---

## 1. Activity recording

### GPS activity recording
- **What**: Record runs (and 30+ other sports) with the phone's GPS; distance, pace, time and route are captured and uploaded on save.
- **Mechanics**: Record screen with start/stop/pause; activities without GPS (treadmill) can still be recorded as duration-only. Moving time is computed server-side on upload if auto-pause is off.
- **Free/paid**: Free.
- **Relevance**: `relevant` — this is the core loop; the app already records via GPS + BLE HR.
- **Source**: [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)

### Auto-pause
- **What**: Automatically pauses the recording clock when you stop moving, resumes when you move again.
- **Mechanics**: For running it is **accelerometer/motion-based** (detects that running motion has stopped); for cycling it is GPS-speed-based. Optional; if off, Strava derives "moving time" after upload instead.
- **Free/paid**: Free.
- **Relevance**: `relevant` — purely on-device signal processing.
- **Sources**: [Auto-Pause](https://support.strava.com/en-us/articles/15402141-auto-pause), [How to Use Auto-Pause on iOS](https://support.strava.com/hc/en-us/articles/216917437-How-to-Use-Auto-Pause-on-iOS)

### Live stats on the record screen
- **What**: Real-time distance, pace/speed, elapsed time, and (with a sensor) heart rate while recording; live location shown on the map.
- **Mechanics**: Strava notes there is no data usage while recording unless viewing maps or using Live Segments/Beacon — i.e. live stats are computed on-device.
- **Free/paid**: Free (the Subscription Features page markets "live performance data" on paired devices as a perk, but in-app record-screen stats are free).
- **Relevance**: `relevant` — already partially present (HR coach); extend with pace/distance/split stats.
- **Source**: [Recording an Activity](https://support.strava.com/hc/en-us/articles/216917397-Recording-an-Activity)

### Audio announcements
- **What**: Spoken updates during a run without looking at the phone.
- **Mechanics**: Announces start/stop/pause events and per-split summaries every half or full mile/km; auto-pause events are announced when split announcements or Live Segment audio are on.
- **Free/paid**: Free.
- **Relevance**: `relevant` — the app already has an AudioCueManager; Strava's split-announcement cadence is the model to match.
- **Source**: [Audio Announcements](https://support.strava.com/en-us/articles/15402180-audio-announcements)

### Beacon (live safety tracking)
- **What**: Shares your real-time location with up to 3 safety contacts during an activity.
- **Mechanics**: Generates a unique URL sent by text; contacts follow along in a browser (no Strava account needed); location updates ~every 15 s over cellular; link dies when recording stops; no automatic "stopped moving" alerts.
- **Free/paid**: Free from the Strava phone app; Beacon **on connected devices** (Apple Watch, Garmin) is subscription — the Subscription Features page lists "Beacon on compatible devices".
- **Relevance**: `relevant-with-adaptation` — the hosted tracking page is a backend service; a local-first equivalent is sharing live location via the OS share sheet / messaging app rather than a self-hosted page.
- **Sources**: [Strava Beacon](https://support.strava.com/hc/en-us/articles/224357527-Strava-Beacon), [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

---

## 2. Activity detail & analysis

### Run activity page (stats + charts)
- **What**: Per-activity page with distance, moving time, pace, elevation gain, calories, HR, plus an activity map and charts.
- **Mechanics**: Elevation profile with mouse-over instantaneous stats; split list, split graph and map are linked (hover/click one highlights the others).
- **Free/paid**: Free (basic page); deeper analysis features below are gated.
- **Relevance**: `relevant` — this is the "rich activity detail" cluster's blueprint.
- **Source**: [Run Activity Pages](https://support.strava.com/hc/en-us/articles/216919567-Run-Activity-Pages)

### Splits (auto per-mile/km)
- **What**: Automatic per-mile or per-km pace breakdown of every run.
- **Mechanics**: Splits are created automatically at each mile/km depending on the athlete's preferred units; shown as a bar graph plus a table.
- **Free/paid**: Free.
- **Relevance**: `relevant`.
- **Source**: [Pace/Speed](https://support.strava.com/hc/en-us/articles/115001136770-Pace-Speed)

### Laps & Workout Analysis
- **What**: Lap-by-lap breakdown for structured workouts (intervals), with a pace-zone-colored graph.
- **Mechanics**: If the recording device pressed lap markers, analysis defaults to lap data (any distance); otherwise auto splits. Mobile Workout Analysis appears automatically on activities with ≥2 laps; bar graph of pace per lap with distance/elapsed time/pace table beneath.
- **Free/paid**: Sub ("Workout Analysis" / "Pace Analysis" are listed subscription features).
- **Relevance**: `relevant` — manual lap button + interval analysis is pure local data.
- **Sources**: [Pace/Speed](https://support.strava.com/hc/en-us/articles/115001136770-Pace-Speed), [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Grade Adjusted Pace (GAP)
- **What**: Estimates the equivalent flat-land pace for hilly running.
- **Mechanics**: Adjusts each moment's pace for the grade being run; shown as a second series in pace analysis.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — a formula over local elevation + pace streams.
- **Sources**: [Subscription Features (glossary)](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features), [Pace/Speed](https://support.strava.com/hc/en-us/articles/115001136770-Pace-Speed)

### Heart rate analysis & custom HR zones
- **What**: Time-in-zone breakdown for any activity with HR data; zones from max HR or fully custom.
- **Mechanics**: Athlete sets max HR (free) or custom zone boundaries (sub); activity page shows time in each zone.
- **Free/paid**: Basic HR display free; custom zones + zone analysis are sub.
- **Relevance**: `relevant` — the app is HR-centric already; zones are a natural extension.
- **Sources**: [Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava), [Heart Rate](https://support.strava.com/en-us/articles/15401762-heart-rate)

### Best Efforts (running)
- **What**: Automatically tracked fastest times at benchmark distances within every run.
- **Mechanics**: Distances from 400 m to 50 k (400m, ½ mi, 1k, 1 mi, 2 mi, 5k, 10k, 15k, 10 mi, 20k, half marathon, 30k, marathon, 50k); detected from GPS data in each activity; "View Analysis" shows top-10 lifetime performances, top per year, and a trend graph.
- **Free/paid**: Sub (listed under subscription "Best Efforts"; achievements on upload are a subscriber perk).
- **Relevance**: `relevant` — purely personal PR detection over local activities.
- **Sources**: [Best Efforts – Overview](https://support.strava.com/en-us/articles/15401646-best-efforts-overview), [Best Efforts – Running](https://support.strava.com/en-us/articles/15401661-best-efforts-running), [Troubleshooting Best Efforts](https://support.strava.com/hc/en-us/articles/216917127-Troubleshooting-Best-Efforts)

### All-Time PRs
- **What**: Manually curated all-time personal records (fastest race times over official distances), distinct from auto-detected Best Efforts.
- **Mechanics**: Athlete-entered; displayed on profile.
- **Free/paid**: Free.
- **Relevance**: `relevant`.
- **Source**: [All-Time PRs](https://support.strava.com/hc/en-us/articles/216918487-All-Time-PRs)

### Achievements / activity top results
- **What**: Medals awarded on upload for outstanding performances (segment PRs, top-3 personal times on a distance, KOM/CR).
- **Mechanics**: PRs are private to the athlete; KOM/QOM/CR and Top-10s depend on public leaderboards.
- **Free/paid**: Achievement awards on upload are a subscriber feature.
- **Relevance**: `relevant-with-adaptation` — personal medals (PR, 2nd/3rd best) work solo; KOM/CR-style community awards do not.
- **Sources**: [What's a Segment?](https://support.strava.com/hc/en-us/articles/216917137-What-s-a-segment), [Your Activity's Top Results](https://support.strava.com/en-us/articles/15402021-your-activity-s-top-results)

### Matched Activities ("matched runs")
- **What**: Auto-groups repeated efforts on the same route and charts the performance trend.
- **Mechanics**: Algorithm matches start point, end point, direction and distance; all matching efforts appear in one trend chart on the activity page. Supported for run, walk, hike, ride and variants.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — personal route benchmarking needs no other users.
- **Source**: [Matched Activities](https://support.strava.com/hc/en-us/articles/216918597-Matched-Activities)

### Gear tracking (shoes)
- **What**: Assign shoes to runs; Strava accumulates mileage per pair.
- **Mechanics**: Create shoes (brand/model), set a default; mileage accrues only from Strava activities (no manual mileage — workaround is a manual activity); wear notification at a configurable threshold (default 250 mi, max 800 mi); shoes can be retired (kept, hidden from pickers) or deleted.
- **Free/paid**: Free.
- **Relevance**: `relevant` — simple local bookkeeping with real injury-prevention value.
- **Sources**: [Adding Gear to Your Activities](https://support.strava.com/hc/en-us/articles/216918727-Adding-Gear-to-Your-Activities-on-Strava), [Managing Shoe Notifications](https://support.strava.com/hc/en-us/articles/216918887-Managing-Shoe-Notifications)

### Perceived Exertion
- **What**: 1–10 subjective effort rating per activity; can substitute for HR in effort math.
- **Mechanics**: Entered on save/edit ("How did that activity feel?"); per-activity toggle to use PE instead of HR as the input to Relative Effort / Fitness.
- **Free/paid**: Entering PE is free; using it in Relative Effort/Fitness is sub.
- **Relevance**: `relevant` — useful fallback when the HR strap is absent.
- **Source**: [Perceived Exertion](https://support.strava.com/hc/en-us/articles/360032535512-Perceived-Exertion)

### Private notes
- **What**: A notes field on an activity visible only to the athlete, separate from the public description.
- **Mechanics**: Added when editing an activity.
- **Free/paid**: Free.
- **Relevance**: `relevant` — trivially local (and in a solo app, all notes are private).
- **Source**: [Run Activity Pages](https://support.strava.com/hc/en-us/articles/216919567-Run-Activity-Pages)

### Weather on activities
- **What**: Shows the weather conditions during the activity on its detail page.
- **Mechanics**: Populated from WeatherKit data for the activity's time and place.
- **Free/paid**: Sub.
- **Relevance**: `relevant-with-adaptation` — needs a third-party weather API call at save time (one outbound request; no Strava backend).
- **Source**: [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Activity split tool / editing
- **What**: Split one recorded activity into two (e.g. forgot to stop the watch).
- **Free/paid**: Free.
- **Relevance**: `relevant` — data-hygiene tool for local recordings.
- **Source**: [Activity Split Tool](https://support.strava.com/hc/en-us/articles/221033867-Activity-Split-Tool)

### Athlete Intelligence (AI summaries)
- **What**: Generative-AI plain-language summary of each activity under the stat box.
- **Mechanics**: Analyzes the activity's data (pace, HR, effort vs history) for runs, trail runs, rides, walks, hikes; opt-in.
- **Free/paid**: Sub.
- **Relevance**: `relevant-with-adaptation` — same idea works with an on-device/API LLM summarizing local data; not required for v1.
- **Source**: [Athlete Intelligence on Strava](https://support.strava.com/hc/en-us/articles/26786795557005-Athlete-Intelligence-on-Strava)

### Flyby
- **What**: Playback of an activity showing other athletes who crossed your path.
- **Free/paid**: Free.
- **Relevance**: `not-applicable` — defined by other users' data. (A solo "replay my run" animation is covered by Flyover below.)
- **Source**: [Flyby Privacy Controls](https://support.strava.com/hc/en-us/articles/360015478252-Flyby-Privacy-Controls)

---

## 3. Maps

### Activity maps (standard & satellite)
- **What**: Every GPS activity renders its polyline on a map; standard and satellite base styles on web.
- **Mechanics**: Satellite imagery from DigitalGlobe/NASA/Mapbox; map linked to charts on the activity page.
- **Free/paid**: Free.
- **Relevance**: `relevant` — core of the maps cluster.
- **Source**: [Map Types](https://support.strava.com/en-us/articles/15401748-map-types)

### Stat maps (custom activity lines) & 3D map types
- **What**: Color the activity polyline by a chosen metric, or render it on a 3D terrain view.
- **Mechanics**: Pace/speed (darker blue = faster), heart rate (darker red = higher), elevation (yellow→black), plus 3D and Winter 3D satellite renderings.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — metric-colored polylines are a strong solo visualization; 3D needs a terrain-capable map SDK.
- **Sources**: [Map Types / Custom Activity Lines](https://support.strava.com/hc/en-us/articles/360049869011-Custom-Activity-Lines-statmaps), [3D Layer on Strava Maps](https://support.strava.com/hc/en-us/articles/4482870430605-3D-Layer-on-Strava-Maps)

### Personal heatmap
- **What**: A map of everywhere the athlete has ever trained, drawn as heat.
- **Mechanics**: Filter by date range or all time; choose heat color; include/exclude commutes, private activities and hidden map portions; toggle activity clusters. Viewable alongside the global heatmap.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — built entirely from the athlete's own local activities; a flagship solo feature.
- **Source**: [Personal Heatmaps](https://support.strava.com/en-us/articles/15402028-personal-heatmaps)

### Weekly heatmap
- **What**: Rolling heat layer of the athlete's (and community's) recent activity for the current week context.
- **Free/paid**: Free (community layer).
- **Relevance**: `relevant-with-adaptation` — a "my recent runs" heat layer is local; community heat is not.
- **Source**: [Weekly Heatmap](https://support.strava.com/en-us/articles/15401630-weekly-heatmap)

### Global heatmap (& Night Heatmap)
- **What**: Aggregated heat from the whole Strava community's last year of activity (updated monthly); Night Heatmap filters to nighttime activity for safety-aware planning.
- **Free/paid**: Viewing at high zoom / in route planning is a subscription capability.
- **Relevance**: `not-applicable` — requires Strava's community data pool. The *personal* analog is the personal heatmap above.
- **Sources**: [The Global Heatmap and Strava Metro](https://support.strava.com/en-us/articles/15401880-the-global-heatmap-and-strava-metro), [Night Heatmap](https://support.strava.com/hc/en-us/articles/31335253810701-Night-Heatmap)

### Flyover (3D replay)
- **What**: Animated 3D fly-through of an activity or route on a dynamic map.
- **Mechanics**: Mobile-only; works on own activities, routes, suggested routes and segments.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — a local render of local data; polish-tier rather than core.
- **Source**: [Flyover](https://support.strava.com/hc/en-us/articles/19900004650125-Flyover)

### Dark mode
- **What**: App-wide dark theme (including maps).
- **Free/paid**: Free.
- **Relevance**: `relevant` — table-stakes UI option.
- **Source**: [Dark Mode on Strava](https://support.strava.com/en-us/articles/15401628-dark-mode-on-strava)

---

## 4. Routes & navigation

### Route builder (web + mobile)
- **What**: Draw a route on a map with smart routing.
- **Mechanics**: Supports 32 sports; preferences for most-popular vs most-direct routing, minimize/maximize elevation, paved vs dirt surface; overlays global heatmap and segments while planning; elevation profile of the drawn route.
- **Free/paid**: Sub (route creation is listed under subscription "Create routes").
- **Relevance**: `relevant-with-adaptation` — drawing + elevation profile is local (needs a routing/elevation service or offline graph); "popularity" routing depends on community heat.
- **Sources**: [Routes on Web](https://support.strava.com/hc/en-us/articles/216918387-Routes-on-Web), [Creating Routes on Mobile](https://support.strava.com/hc/en-us/articles/18001474720397-Creating-Routes-on-Mobile)

### Suggested routes / generated community routes
- **What**: One-tap generated run/ride routes near you at a chosen distance.
- **Mechanics**: Maps tab → Routes; pick sport (running, trail running, walking, …) and approximate distance; Strava generates options optimized per sport from community data.
- **Free/paid**: Sub.
- **Relevance**: `relevant-with-adaptation` — solo version = generate loops from the athlete's own history + open map data instead of community popularity.
- **Sources**: [Generated Community Routes](https://support.strava.com/en-us/articles/15401756-generated-community-routes), [Routes on Mobile](https://support.strava.com/hc/en-us/articles/360039136692-Routes-on-Mobile)

### GPX/TCX route import
- **What**: Upload a GPX file to become a Strava route.
- **Mechanics**: Import via the route builder on web; imported routes behave like drawn ones.
- **Free/paid**: Sub (routes feature).
- **Relevance**: `relevant` — file import is the local-first bread and butter.
- **Source**: [Uploading Route Files](https://support.strava.com/hc/en-us/articles/206811950-Uploading-Route-Files)

### GPX/TCX route export
- **What**: Download any route (or another athlete's public activity) as GPX/TCX for external devices.
- **Mechanics**: GPX includes styled map + text directions when base maps exist; TCX suits deviceless navigation; routes auto-sync to Garmin.
- **Free/paid**: Free to export public routes; own-activity GPX export is free.
- **Relevance**: `relevant` — interop with watches and other apps.
- **Sources**: [Following a Route](https://support.strava.com/hc/en-us/articles/360044071592-Following-a-Route), [Downloading a GPX Route from other Athletes' Activities](https://support.strava.com/en-us/articles/15402129-downloading-a-gpx-route-from-other-athlete-s-activities)

### Following a route while recording (map-based navigation)
- **What**: Load a saved route onto the record screen and follow it live.
- **Mechanics**: Add Route icon on record screen → pick route → Start; the route draws on the live map. Strava's in-app navigation is **map-based, not full turn-by-turn audio** — cue sheets and device export cover turn-level guidance; requires GPS.
- **Free/paid**: Sub (part of routes).
- **Relevance**: `relevant` — on-map route-following with off-route awareness is a top solo feature; exceeding Strava with audio turn cues is an opportunity.
- **Source**: [Following a Route](https://support.strava.com/hc/en-us/articles/360044071592-Following-a-Route)

### Offline route maps
- **What**: Download routes (with maps) for use without connectivity.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — offline-first is literally the app's philosophy.
- **Source**: [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Driving directions to route start
- **What**: Hands off the route start point to the phone's navigation app.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — a trivial OS intent.
- **Source**: [Driving Directions](https://support.strava.com/hc/en-us/articles/7878960835597-Driving-Directions)

---

## 5. Segments

### Segments (concept)
- **What**: Community-defined stretches of road/trail; every activity crossing one gets a timed effort.
- **Mechanics**: Anyone can create a segment from a portion of an activity on the web; efforts are matched by GPS; popular segments ranked by stars/views/proximity; hazardous segments can be flagged; iconic segments get a "verified" badge.
- **Free/paid**: Free to match & see own efforts/PRs; full leaderboards etc. gated (below).
- **Relevance**: `relevant-with-adaptation` — **personal segments**: user defines favorite stretches from their own runs; every run auto-times them and tracks PRs. No community needed for the timing mechanics.
- **Sources**: [What's a Segment?](https://support.strava.com/hc/en-us/articles/216917137-What-s-a-segment), [Segment Updates: Verified Segments, Decluttering and Leaderboard](https://support.strava.com/hc/en-us/articles/31523071638797-Segment-Updates-Verified-Segments-Decluttering-and-Leaderboard)

### Starred segments
- **What**: Bookmark segments to follow; starred segments feed Live Segments and device sync.
- **Free/paid**: Free to star.
- **Relevance**: `relevant-with-adaptation` — "favorite personal segments" list.
- **Source**: [What Are Starred Segments?](https://support.strava.com/hc/en-us/articles/216918377-What-Are-Starred-Segments)

### Segment leaderboards
- **What**: Ranked times per segment; overall plus filters (time period, age, weight, followers, clubs); KOM/QOM/CR for the fastest.
- **Free/paid**: Full/filtered leaderboards are sub.
- **Relevance**: `relevant-with-adaptation` — a **personal leaderboard** (my top-10 efforts on my segment, by year/conditions) preserves the motivation loop; community ranking itself is `not-applicable`.
- **Sources**: [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features), [What's a Segment?](https://support.strava.com/hc/en-us/articles/216917137-What-s-a-segment)

### Live Segments
- **What**: Real-time race against a segment while recording.
- **Mechanics**: Nearest starred or popular segment enters live mode; live comparison vs your PR and the KOM/QOM/CR; audio feedback; works in-app and on compatible devices; subscribers get extra comparison screens.
- **Free/paid**: Base experience free; subscriber extras.
- **Relevance**: `relevant-with-adaptation` — race **your own ghost** (PR) on personal segments in real time; the KOM comparison drops out.
- **Source**: [Live Segments](https://support.strava.com/hc/en-us/articles/207343830-Live-Segments)

### Local Legends
- **What**: Award for the athlete with the most efforts on a segment over a rolling 90 days, regardless of speed.
- **Free/paid**: Free.
- **Relevance**: `not-applicable` as a competition — but the underlying mechanic (effort *count* streaks on a favorite loop) adapts into personal consistency stats.
- **Source**: [Local Legends](https://support.strava.com/en-us/articles/15401751-local-legends)

---

## 6. Training & analytics

### Training log
- **What**: Calendar/log view of all training in one place, visualized by week with key stats.
- **Free/paid**: Sub (web feature).
- **Relevance**: `relevant` — pure personal-data visualization.
- **Source**: [Subscription Features – Training Log](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Relative Effort
- **What**: A single cardiovascular-load score for any activity with HR (or Perceived Exertion).
- **Mechanics**: Personalized to the athlete's HR zones; sport-weighted so efforts compare across sports; weekly view shows the trend plus a target range derived from the athlete's 3-week average to guard against overtraining.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — HR-based scoring is a perfect fit for an HR-first app.
- **Source**: [Relative Effort](https://support.strava.com/hc/en-us/articles/360000197364-Relative-Effort)

### Fitness & Freshness
- **What**: Long-term chart of Fitness (chronic load), Fatigue (acute load) and Form/Freshness.
- **Mechanics**: Daily training quantified by Relative Effort (HR/PE) or power-based Training Load; impulse-response model per Banister (1975), as applied by Coggan.
- **Free/paid**: Sub.
- **Relevance**: `relevant` — a well-documented formula over local effort scores.
- **Sources**: [Fitness & Freshness](https://support.strava.com/en-us/articles/15402032-fitness-freshness), [Fitness](https://support.strava.com/en-us/articles/15401765-fitness)

### Pace zones & training zones
- **What**: Pace zones (min/km or min/mi) and HR zones used to color analysis and measure intensity.
- **Mechanics**: Pace zones are manually configured; HR zones from max-HR defaults or custom boundaries.
- **Free/paid**: Sub for custom zones/pace-zone analysis.
- **Relevance**: `relevant`.
- **Source**: [Training Zones on Strava](https://support.strava.com/hc/en-us/articles/39113532401421-Training-Zones-on-Strava)

### Goals
- **What**: Weekly / monthly / annual goals on distance, time, elevation or activity count; also segment and power goals.
- **Mechanics**: Set per sport; progress tracked automatically; all sport types settable from mobile.
- **Free/paid**: Sub ("Custom Goals").
- **Relevance**: `relevant` — self-set targets need no community.
- **Source**: [Subscription Features – Custom Goals](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Progress summary chart & cumulative stats
- **What**: "You" tab chart of distance/time/elevation/count over selectable ranges; monthly cumulative stats with year-over-year comparison.
- **Free/paid**: Chart free; year-over-year cumulative stats sub.
- **Relevance**: `relevant`.
- **Sources**: [Progress Summary Chart](https://support.strava.com/hc/en-us/articles/28437860016141-Progress-Summary-Chart), [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

### Profile stats grid
- **What**: Profile page with weekly/monthly activity grouping, totals filtered by time/distance/elevation, recent trophies; "Refresh Stats" recompute.
- **Free/paid**: Free.
- **Relevance**: `relevant` (minus follower/KOM tabs).
- **Source**: [Your Strava Profile Page](https://support.strava.com/hc/en-us/articles/216917697-Your-Strava-Profile-Page)

### Trophy case & challenges
- **What**: Badges for completed monthly challenges displayed in a trophy case.
- **Free/paid**: Free (Group Challenges with friends are sub).
- **Relevance**: `relevant-with-adaptation` — community challenges are out, but **self-issued milestones/badges** (first 10k, 100 km month, streaks) reuse the trophy-case mechanic.
- **Source**: [The Strava Trophy Case](https://support.strava.com/hc/en-us/articles/216918557-The-Strava-Trophy-Case)

### Year in Sport
- **What**: Annual personalized recap of the athlete's training year.
- **Free/paid**: Free (richer for subs).
- **Relevance**: `relevant-with-adaptation` — a local "year recap" generated from the athlete's own data (skip social stats).
- **Source**: [Your Year in Sport](https://support.strava.com/hc/en-us/articles/22067973274509-Your-Year-in-Sport)

### Training plans
- **What**: Guided multi-week run/ride training programs.
- **Free/paid**: Sub.
- **Relevance**: `relevant-with-adaptation` — bundled static plans work offline; Strava's coaching content itself isn't reproducible.
- **Source**: [Subscription Features – Training Plans](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)

---

## 7. Data portability

### Activity export (GPX/TCX) & bulk export
- **What**: Export any own activity as GPX (button) or TCX (`/export_tcx` URL suffix; includes HR/cadence); one-click archive of the whole account.
- **Free/paid**: Free.
- **Relevance**: `relevant` — a local-first app should match or beat this (it owns all the files already).
- **Source**: [Exporting your Data and Bulk Export](https://support.strava.com/hc/en-us/articles/216918437-Exporting-your-Data-and-Bulk-Export)

### Activity import / bulk upload
- **What**: Upload GPX/TCX/FIT files, including bulk migration from other platforms.
- **Free/paid**: Free.
- **Relevance**: `relevant` — lets the athlete bring watch/Strava history into the local app.
- **Source**: [Bulk Uploading Activities to Strava](https://support.strava.com/en-us/articles/15402173-bulk-uploading-activities-to-strava)

---

## 8. Social / community features (out of scope)

Recorded for completeness; all `not-applicable` (require Strava's social graph or hosted community):

- Feed, followers, kudos & comments — [Your Strava Profile Page](https://support.strava.com/hc/en-us/articles/216917697-Your-Strava-Profile-Page)
- Clubs — [Clubs on the Mobile App](https://support.strava.com/hc/en-us/articles/221622188-Clubs-on-the-Mobile-App)
- Group Challenges — [Subscription Features](https://support.strava.com/hc/en-us/articles/216917657-Strava-Subscription-Features)
- Group activities detection / activity–event match — [Group Activities](https://support.strava.com/en-us/articles/15401890-group-activities)
- Flyby — [Flyby Privacy Controls](https://support.strava.com/hc/en-us/articles/360015478252-Flyby-Privacy-Controls)

---

## Summary table

| Feature | Area | Free/Sub | Relevance |
|---|---|---|---|
| GPS recording, live stats | Recording | Free | relevant |
| Auto-pause (motion-based for runs) | Recording | Free | relevant |
| Audio announcements (splits/events) | Recording | Free | relevant |
| Beacon live safety tracking | Recording | Free (app) / Sub (devices) | relevant-with-adaptation |
| Activity page: map + linked charts | Activity detail | Free | relevant |
| Auto splits per mi/km | Activity detail | Free | relevant |
| Laps & Workout/Pace Analysis | Activity detail | Sub | relevant |
| Grade Adjusted Pace | Activity detail | Sub | relevant |
| HR analysis + custom zones | Activity detail | Free/Sub | relevant |
| Best Efforts (400m–50k) | Activity detail | Sub | relevant |
| All-Time PRs | Activity detail | Free | relevant |
| Achievements / top results medals | Activity detail | Sub | relevant-with-adaptation |
| Matched Activities | Activity detail | Sub | relevant |
| Gear (shoe) tracking + wear alerts | Activity detail | Free | relevant |
| Perceived Exertion | Activity detail | Free/Sub | relevant |
| Private notes | Activity detail | Free | relevant |
| Weather on activity | Activity detail | Sub | relevant-with-adaptation |
| Activity split tool | Activity detail | Free | relevant |
| Athlete Intelligence (AI summary) | Activity detail | Sub | relevant-with-adaptation |
| Flyby | Activity detail | Free | not-applicable |
| Standard/satellite activity maps | Maps | Free | relevant |
| Stat maps (pace/HR/elev-colored lines), 3D | Maps | Sub | relevant |
| Personal heatmap | Maps | Sub | relevant |
| Weekly heatmap | Maps | Free | relevant-with-adaptation |
| Global / Night heatmap | Maps | Sub | not-applicable |
| Flyover 3D replay | Maps | Sub | relevant |
| Dark mode | Maps/UI | Free | relevant |
| Route builder (surface/elevation prefs) | Routes | Sub | relevant-with-adaptation |
| Suggested routes | Routes | Sub | relevant-with-adaptation |
| GPX/TCX route import | Routes | Sub | relevant |
| GPX/TCX route/activity export | Routes/Data | Free | relevant |
| Follow a route while recording | Routes | Sub | relevant |
| Offline route maps | Routes | Sub | relevant |
| Driving directions to start | Routes | Sub | relevant |
| Segments (timed stretches) | Segments | Free | relevant-with-adaptation |
| Starred segments | Segments | Free | relevant-with-adaptation |
| Leaderboards, KOM/QOM/CR | Segments | Sub | relevant-with-adaptation (personal-only) |
| Live Segments (race your PR) | Segments | Free/Sub | relevant-with-adaptation |
| Local Legends | Segments | Free | not-applicable |
| Training log | Training | Sub | relevant |
| Relative Effort | Training | Sub | relevant |
| Fitness & Freshness | Training | Sub | relevant |
| Pace/HR training zones | Training | Sub | relevant |
| Goals (weekly/monthly/annual) | Training | Sub | relevant |
| Progress chart & cumulative stats | Training | Free/Sub | relevant |
| Profile stats grid | Training | Free | relevant |
| Trophy case / challenges | Training | Free | relevant-with-adaptation |
| Year in Sport | Training | Free | relevant-with-adaptation |
| Training plans | Training | Sub | relevant-with-adaptation |
| Bulk export / import | Data | Free | relevant |

## Gaps versus the current map's four clusters

The map's clusters — (1) maps + route recording, (2) rich activity detail, (3) routes +
navigation, (4) training analytics — do **not** yet cover:

1. **Personal segments** (segment definition, auto effort timing, personal PR leaderboard, live "race your ghost") — the single biggest uncovered Strava pillar.
2. **Best Efforts / PR system** — cross-activity automatic PR detection at benchmark distances with trend views (bigger than one activity's detail page).
3. **Matched activities** — automatic route-repeat grouping and trend charts.
4. **Gear (shoe) mileage tracking** with wear-threshold alerts.
5. **Goals** — weekly/monthly/annual distance/time/elevation/count targets with progress tracking.
6. **Milestones / trophy case** — self-issued badges, streaks, Year-in-Sport-style recaps (Local-Legend-like consistency stats fold in here).
7. **Data portability** — GPX/TCX/FIT export & import, full-archive backup (existential for a local-first app).
8. **Safety: Beacon-style live location sharing** (via OS share/messaging in a no-backend world).
9. **Subjective & context data capture** — perceived exertion, private notes, weather-at-save.
10. **Audio-cue parity+** — Strava-style split/event announcements exist in-app already, but *navigation* audio cues (off-route, turn hints) would exceed Strava's map-only route following.
11. **AI activity summaries** (Athlete Intelligence analog) — optional, adaptation-tier.
