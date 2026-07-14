# How should elevation gain be computed credibly?

Research for issue [#20](https://github.com/chrissuarez/Running-App/issues/20) (part of #18).
Researched 2026-07-14 against primary sources (Android/AOSP docs, Android CDD, Bosch datasheets,
Strava/Garmin official support pages, NASA/USGS/Copernicus DEM specs, provider API docs).

## TL;DR — Recommendation

**Tiered, matching what Garmin and Strava both do: use the phone's barometer when present
(GPS-anchored), fall back to smoothed GPS altitude when not — and in both cases accumulate gain
with hysteresis (~2 m baro / ~10 m GPS-only). Skip DEM/web APIs for v1; add on-device Copernicus
GLO-30 tiles later only if GPS-only accuracy proves unacceptable.**

Rationale in one paragraph: both market leaders converge on the same architecture — barometric
altimeter data is trusted as-recorded, non-barometric devices get their GPS altitude *discarded*
and replaced by a DEM/basemap lookup, and gain is only committed after a climb exceeds a noise
threshold ([Strava FAQ][strava-faq], [Garmin elevation-corrections FAQ][garmin-corr]). A phone
barometer gives **sub-meter relative precision** ([Bosch BMP390: ±0.03 hPa ≈ ±25 cm][bmp390]) at
negligible power (single-digit µA), while smartphone GPS vertical error is roughly 8–15 m per fix
(vertical ≈ 1.6–2x horizontal error, [2020 GPS SPS Performance Standard][sps]). For a local-first
personal app, a web API breaks the offline constraint and offline DEM adds real complexity for
accuracy that is *worse* than a barometer (GLO-30 relative vertical error <2–4 m, and it's a
surface model — wrong under tree canopy and on bridges).

---

## Why raw GPS altitude can't be summed

- The official GPS SPS spec commits to ≤8 m horizontal / ≤13 m vertical error (95% global
  average) — vertical is ~1.6x horizontal *by spec*; smartphones under open sky are "typically
  accurate to within a 4.9 m radius" **horizontally**, worse near buildings/trees
  ([gps.gov accuracy][gps-acc], [2020 SPS PS][sps]). Practical vertical uncertainty: 8–15 m per fix.
- `Location.getVerticalAccuracyMeters()` (API 26+, matches our minSdk) is a **68th-percentile
  (1-sigma)** bound — ~1 in 3 fixes is worse than the reported number
  ([Location docs][loc-vacc]).
- `Location.getAltitude()` is height above the **WGS84 ellipsoid**, not sea level; the geoid
  offset (±~100 m worldwide) is locally constant, so it cancels for *gain* but matters for
  absolute display or DEM calibration ([Location docs][loc-alt]). API 34+ adds
  `getMslAltitudeMeters()` / `AltitudeConverter` for on-device geoid conversion
  ([AltitudeConverter][alt-conv]).
- **Naive summation inflates massively.** Gain is a sum of positive deltas, so every meter-scale
  vertical wiggle adds; TopoFusion: "elevation gain is a sum, [so] it is highly sensitive to the
  number of points in a track" ([TopoFusion climb analysis][topofusion]). With σ ≈ 5 m independent
  noise per fix, expected spurious positive delta is ~0.56σ ≈ 3 m *per sample pair* — thousands of
  fictitious meters per hour on a flat route at our 2–5 s cadence. Empirically, raw GPS tracks
  show ±10–20 m swings and 20–40% gain inflation even on genuinely hilly routes
  ([HikingManual comparison][hikingmanual]); Runalyze states the unsmoothed sum "produces
  inflated values" ([Runalyze elevation help][runalyze]).

## How the incumbents handle it (official documentation)

| | Barometric device | Non-barometric device (phones) |
|---|---|---|
| **Strava** | Trusts data as-recorded; less smoothing | Discards GPS altitude, substitutes "corrected elevation" from their barometric-crowdsourced basemap ([FAQ][strava-faq]) |
| **Strava gain threshold** | Climb must persist **>2 m** before counting | Climb must persist **>10 m** before counting ([FAQ][strava-faq]) |
| **Garmin** | Uses device-recorded data; elevation correction *off* by default; watch auto-calibrates baro from GPS at start + "continuous calibration" (DEM+GPS offsets weather drift) ([corrections FAQ][garmin-corr], [fēnix 7 manual][fenix-cal]) | Elevation correction *on* by default: Garmin-hosted DEM replaces GPS altitude ([corrections FAQ][garmin-corr]) |

Neither uses raw GPS altitude for gain. That's the strongest available signal for our design.

---

## Option comparison

| Approach | Accuracy | Offline | Battery | Complexity | Verdict |
|---|---|---|---|---|---|
| **Barometer (GPS-anchored)** | Relative precision ±0.25–0.5 m ([BMP390][bmp390], [BMP581][bmp581]); CDD-required temperature compensation; drift only from weather fronts/wind gusts | Fully offline | Negligible: 1.3–3.2 µA @ 1 Hz sensor draw; SoC already awake for location updates | Low-medium: sensor listener + low-pass + slow GPS offset correction | **Primary, when hardware present** |
| **GPS smoothing + hysteresis** | 8–15 m per-fix vertical error; usable *gain* after 5-sample smoothing + 10 m hysteresis (Strava's own non-baro treatment) | Fully offline | Zero marginal (reuses existing fixes) | Low: ~50 lines in the existing pipeline | **Fallback, always implemented** |
| **Offline DEM (Copernicus GLO-30 / SRTM 1″)** | GLO-30: <4 m absolute, <2–4 m relative LE90 ([Copernicus][cop-dem]); SRTM: ≤16 m abs / ≤10 m rel LE90 ([SRTM guide][srtm-guide]). But: **surface model** (treetops, buildings), wrong on bridges, GPS horizontal error slides you across 30 m cells near slopes | Yes, after tile download (~26 MB/1° raw HGT; convert to compressed GeoTIFF for ~10x faster random reads [per Open Topo Data][otd-srtm]) | Zero marginal | Medium-high: tile fetch/storage, HGT/GeoTIFF reader ([tiff-java][tiff-java] or hand-rolled), bilinear interpolation — and still needs the same smoothing/hysteresis | Possible later upgrade for the GPS-only tier; not v1 |
| **Web elevation API** | Same DEM accuracy ceilings as above; Google interpolates from 4 nearest cells, `resolution` field reports source spacing ([Elevation API][gmaps-elev]) | **No** — fails the offline constraint (post-run correction only) | Network cost only | Low (Google: 512 pts/request, 5,000 free calls/month then $5/1k [Pro SKU pricing][gmaps-price]; Open Topo Data public: 1,000 calls/day, 100 pts/call, "for testing" [docs][otd]; Open-Elevation: 250 m default dataset, donation-funded — weakest [docs][oe]) | Optional post-run "correct elevation" feature, not the live pipeline |

## Recommended design

### Tier 1 — Barometer present (most modern mid/high-tier phones)

**Detection** (both levels):

```kotlin
val hasBaro = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER)
val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) // null => absent
```

`getDefaultSensor` returning null means the device lacks the sensor ([sensors overview][sens-ov]);
the CDD only *strongly recommends* a barometer, so runtime checking is mandatory
([CDD 7.3.5][cdd]).

**Pipeline:**

1. Register at `SENSOR_DELAY_NORMAL` (5 Hz — the CDD minimum delivery rate for barometers, so
   universally supported; [CDD 7.3.5][cdd]). Event value is pressure in hPa
   (`event.values[0]`, [SensorEvent docs][sensorevent]).
2. Convert to relative altitude with
   `SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p)` — the javadoc
   explicitly endorses standard-atmosphere reference for altitude *differences*
   ([SensorManager docs][sm-getalt]). Median/low-pass over ~1–3 s to kill wind-gust spikes
   (rapid non-altitude pressure transients are documented in [Sabatini & Genovese 2014][sensors-paper]).
3. **Fuse with GPS (complementary filter):** baro provides short-term deltas; GPS altitude —
   heavily low-passed and weighted by `getVerticalAccuracyMeters()` — slowly corrects the absolute
   offset (equivalently, the effective sea-level pressure `p0`). This is the canonical
   baro-as-one-band/other-sensor-as-other-band structure ([Sabatini & Genovese][sensors-paper])
   and exactly what Garmin's "auto calibration at your GPS starting point" + continuous
   calibration does ([Forerunner 965 manual][frn965]). A time constant of several minutes is
   enough — its only job is countering weather drift over the run
   (drift mechanism documented by [Strava][strava-elev]).
4. Accumulate gain with **~2 m hysteresis** (Strava's own baro threshold, [FAQ][strava-faq]).

**Battery:** sensor draw is single-digit µA ([BMP581 1.3 µA @ 1 Hz][bmp581], [BMP390 3.2 µA][bmp390]);
the wakeful-SoC cost is already paid by the 2–5 s location updates. Unregister the listener with
the session (leaked listeners keep draining, [sensors overview][sens-ov]).

### Tier 2 — No barometer (GPS-only)

1. Gate on `location.hasVerticalAccuracy()` and reject fixes with
   `getVerticalAccuracyMeters()` above a cutoff (mirroring the existing horizontal-accuracy gate
   in `LocationTracker.handleNewLocation`).
2. Smooth altitude with a **~5-sample moving average** (the width TopoFusion found consistent
   with Strava/Garmin Connect behavior, [TopoFusion][topofusion]) or a 1-D Kalman filter using
   the reported 1-sigma vertical accuracy as measurement variance.
3. Accumulate gain with **~10 m hysteresis** (Strava's non-baro threshold, [FAQ][strava-faq]).
   Comparable documented thresholds: GoldenCheetah default 3 m (good data)
   ([GC preferences][gc-prefs]), Runalyze "good results with a threshold of 3 meters" plus a
   Douglas-Peucker alternative for long shallow grades ([Runalyze][runalyze]),
   Maptech 10 m minimum continuous gain ([TopoFusion][topofusion]).

### The hysteresis accumulator (shared by both tiers)

Only commit climb once cumulative rise since the last committed trough exceeds the threshold:

```
onSmoothedAltitude(a):
    if a < troughAlt: troughAlt = a            # track running minimum
    if a - troughAlt >= THRESHOLD:             # 2 m (baro) / 10 m (GPS-only)
        gain += a - troughAlt
        troughAlt = a                          # re-arm from the new high
```

This is the industry-default structure (Strava's "climbing must occur consistently for more than
N meters before it is added to the total" [FAQ][strava-faq]; same 2 m-class hysteresis appears in
Fitbit's altimeter patent [US 8,386,008][fitbit-patent]). It suppresses flat-route oscillation
(e.g. 98↔102 m jitter being counted as repeated 4 m climbs, the case TwoNav documents
suppressing [TwoNav][twonav]) while still counting real sustained climbs.

### Later options (not v1)

- **On-device DEM assist for Tier 2:** download the 1–4 Copernicus GLO-30 / SRTM 1″ tiles around
  the user's area (~26 MB/tile raw, [SRTM user guide][srtm-guide]; store as compressed GeoTIFF for
  fast random reads [Open Topo Data][otd-srtm]), bilinear-interpolate the 4 surrounding cells
  (the interpolation both Google and Open Topo Data default to, [Google][gmaps-elev-ov],
  [OTD API][otd-api]). Caveats: DSM-vs-ground error under canopy/on bridges
  ([Copernicus][cop-dem], [SRTM guide][srtm-guide]) and GPS horizontal scatter across cells —
  smoothing + hysteresis still required.
- **Post-run "correct elevation" via Google Elevation API** (512 points/request, 5,000 free
  calls/month then $5/1k, [pricing][gmaps-price]) — an online nicety, never the live path.

### Fit with the current codebase

`LocationTracker` (`app/src/main/java/com/example/runningapp/LocationTracker.kt`) already gates
fixes on horizontal accuracy and accumulates distance in `handleNewLocation`. Elevation slots in
as a sibling: an `ElevationTracker` owning the pressure listener + fusion state (Tier 1) or the
altitude smoother (Tier 2), fed from the same session lifecycle (`start`/`stop`/
`resetSessionState`), reporting `gainMeters` through `onMetricsUpdated`.

---

## Sources

[strava-faq]: https://support.strava.com/hc/en-us/articles/115001294564-Elevation-on-Strava-FAQs
[strava-elev]: https://support.strava.com/hc/en-us/articles/216919447-Elevation
[garmin-corr]: https://support.garmin.com/en-US/?faq=R4I5hFFcUk8gJPC4zi0Xv6
[fenix-cal]: https://www8.garmin.com/manuals/webhelp/GUID-C001C335-A8EC-4A41-AB0E-BAC434259F92/EN-US/GUID-BC734846-01A7-4F33-86D4-DFBDBC06CDB4.html
[frn965]: https://www8.garmin.com/manuals/webhelp/GUID-0221611A-992D-495E-8DED-1DD448F7A066/EN-US/GUID-BC734846-01A7-4F33-86D4-DFBDBC06CDB4.html
[sps]: https://www.gps.gov/sites/default/files/2025-07/2020-SPS-performance-standard.pdf
[gps-acc]: https://www.gps.gov/gps-accuracy
[loc-vacc]: https://developer.android.com/reference/android/location/Location#getVerticalAccuracyMeters()
[loc-alt]: https://developer.android.com/reference/android/location/Location#getAltitude()
[alt-conv]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/location/java/android/location/altitude/AltitudeConverter.java
[cdd]: https://source.android.com/docs/compatibility/15/android-15-cdd
[sens-ov]: https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
[sensorevent]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorEvent.java
[sm-getalt]: https://developer.android.com/reference/android/hardware/SensorManager#getAltitude(float,%20float)
[bmp390]: https://www.bosch-sensortec.com/en/products/environmental-sensors/pressure-sensors/bmp390
[bmp581]: https://www.bosch-sensortec.com/en/products/environmental-sensors/pressure-sensors/bmp581
[sensors-paper]: https://pmc.ncbi.nlm.nih.gov/articles/PMC4179067/
[topofusion]: https://topofusion.com/climb.php
[runalyze]: https://runalyze.com/help/article/elevation?_locale=en
[gc-prefs]: https://github.com/GoldenCheetah/GoldenCheetah/wiki/UG_Preferences_General
[hikingmanual]: https://www.hikingmanual.com/posts/strava-vs-komoot-vs-ride-with-gps-elevation-gain-accuracy/
[twonav]: https://support.twonav.com/hc/en-us/articles/115001273092-Adjusting-altitude-data-on-the-GPS
[fitbit-patent]: https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8386008
[cop-dem]: https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM
[srtm-guide]: https://lpdaac.usgs.gov/documents/179/SRTM_User_Guide_V3.pdf
[gmaps-elev]: https://developers.google.com/maps/documentation/elevation/requests-elevation
[gmaps-elev-ov]: https://developers.google.com/maps/documentation/elevation/overview
[gmaps-price]: https://developers.google.com/maps/billing-and-pricing/pricing
[otd]: https://www.opentopodata.org/
[otd-api]: https://www.opentopodata.org/api/
[otd-srtm]: https://www.opentopodata.org/datasets/srtm/
[oe]: https://open-elevation.com/
[tiff-java]: https://github.com/ngageoint/tiff-java

Primary sources: Android developer reference & AOSP source (Sensor/SensorManager/SensorEvent/
Location/AltitudeConverter), Android 15 CDD §7.3.5, Bosch BMP390/BMP581 datasheets, GPS.gov 2020
SPS Performance Standard, Strava & Garmin official support articles and device manuals, NASA/USGS
SRTM User Guide v3, Copernicus DEM collection description, Google Maps Platform Elevation API
docs & pricing, Open Topo Data and Open-Elevation docs. Secondary corroboration: TopoFusion climb
analysis, Runalyze/GoldenCheetah docs, Sabatini & Genovese (Sensors 2014, doi:10.3390/s140813324).
