# Which map SDK for live in-run and session-detail maps?

**Ticket:** #19 (part of #18) · **Date:** 2026-07-14 · **Status:** Resolved

## Question

Which Android map SDK should the app adopt for (a) a live map during outdoor runs and
(b) route/polyline display in the session-detail screen? Candidates: Google Maps SDK for
Android (+ android-maps-compose), Mapbox Maps SDK, MapLibre Native/Compose, osmdroid.

## App context (verified in this repo)

- Toolchain: Kotlin **1.9.0**, Compose BOM **2023.08.00**, Compose compiler **1.5.1**,
  AGP **8.13.2**, Gradle 8.13, minSdk **26**, targetSdk/compileSdk **34**, Java **11**
  (`build.gradle.kts`, `app/build.gradle.kts`).
- Google Play Services is already a dependency: `play-services-location:21.1.0`;
  `LocationTracker` uses FusedLocationProvider at `PRIORITY_HIGH_ACCURACY` / 5 s.
- Route data already exists: `hr_samples` rows carry `latitude`/`longitude`/`paceMinPerKm`
  per ~5 s sample (`app/src/main/java/com/example/runningapp/data/AppDatabase.kt`), so a
  **pace-colored gradient polyline** is the natural session-detail rendering.
- Local-first, no backend; billing-backed free tiers acceptable (confirmed by user).

## TL;DR recommendation

**Adopt Mapbox Maps SDK for Android v11 (`com.mapbox.maps:android:11.x`) plus its
first-party Compose extension (`com.mapbox.extension:maps-compose:11.x`).**

It is the only candidate whose **current latest release works on the app's existing
toolchain with zero upgrades**, and the only one that combines real offline maps
(runner loses signal), a purpose-built camera-follow API, native pace-gradient
polylines, and a true dark style. Runner-up: MapLibre (see below) if avoiding a
billing account ever becomes a requirement.

---

## 1. Toolchain compatibility (the decisive constraint)

Kotlin binary metadata is forward-compatible by at most one language version: a 1.9
compiler can read *most* 2.0-built binaries (best-effort, not guaranteed) and **cannot**
read 2.1+ binaries ([Kotlin evolution principles](https://kotlinlang.org/docs/kotlin-evolution-principles.html),
[What's new in Kotlin 2.0](https://kotlinlang.org/docs/whatsnew20.html)).

| Candidate | Latest version (Jul 2026) | Compiled with | Usable from Kotlin 1.9.0 / BOM 2023.08? | Forced toolchain upgrade? |
|---|---|---|---|---|
| **Mapbox Maps v11 + maps-compose ext.** | 11.26.0 (2026-07-10) | Kotlin **1.7.20**, Compose compiler 1.3.2 / BOM 2023.01.00 | **Yes — latest version, as-is** | **None** |
| Google `play-services-maps` | 20.0.0 (2026-01-31) | Java binary (no Kotlin dep) | Yes | None |
| Google `android-maps-compose` | 8.3.1 (2026-07-07) | Kotlin **2.3.21**, BOM 2026.03.00 | **No.** Must pin **5.0.3** (2024-06-06, Kotlin 1.9.23; pulls Compose runtime up to 1.6.6). 5.0.4 moved to Kotlin 2.0.0; ≥6.6.0 (Kotlin 2.1.10 + Compose 1.8) is a hard break | None if pinned to 5.0.3; Kotlin 2.1+/new Compose to use anything current |
| MapLibre Native Android | 13.3.1 (2026-06-24) | Kotlin **2.2.10** (13.1.0+); 11.5.2–13.0.2 = Kotlin 2.0.20; 11.0.0 = Kotlin 1.9.21 | Pin **13.0.2** (best-effort) or **11.0.0** (guaranteed) | None if pinned; Kotlin 2.2+ for current versions |
| MapLibre Compose | 0.13.0 (2026-05-20) | Kotlin **2.3.21**, Compose Multiplatform 1.10.3 | **No — unusable, full stop** | Kotlin 2.3 + CMP migration |
| osmdroid | 6.1.20 (2024-08-18, **final**) | Pure Java, minSdk 8 | Yes | None |

Sources: Mapbox [v11 migration guide](https://docs.mapbox.com/android/maps/guides/migrate-to-v11/)
("requires Kotlin 1.6.0 or later … compiled with Kotlin 1.7.20"), confirmed against the
published [maps-compose 11.26.0 POM](https://api.mapbox.com/downloads/v2/releases/maven/com/mapbox/extension/maps-compose/11.26.0/maps-compose-11.26.0.pom)
(`kotlin-stdlib-jdk8:1.7.20`) and the repo's [libs.versions.toml](https://github.com/mapbox/mapbox-maps-android/blob/main/gradle/libs.versions.toml);
android-maps-compose per-tag `gradle/libs.versions.toml` (e.g. [v5.0.3](https://raw.githubusercontent.com/googlemaps/android-maps-compose/v5.0.3/gradle/libs.versions.toml),
[v6.6.0](https://raw.githubusercontent.com/googlemaps/android-maps-compose/v6.6.0/gradle/libs.versions.toml)) and
published metadata (e.g. [5.0.4 .module](https://repo1.maven.org/maven2/com/google/maps/android/maps-compose/5.0.4/maps-compose-5.0.4.module) → `kotlin-stdlib 2.0.0`);
[Compose ↔ Kotlin compatibility map](https://developer.android.com/jetpack/androidx/releases/compose-kotlin);
MapLibre per-version [POMs on Maven Central](https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/) and
[android-v13.3.1 libs.versions.toml](https://raw.githubusercontent.com/maplibre/maplibre-native/android-v13.3.1/platform/android/gradle/libs.versions.toml);
[maplibre-compose libs.versions.toml](https://github.com/maplibre/maplibre-compose/blob/main/gradle/libs.versions.toml) and
[getting-started](https://maplibre.org/maplibre-compose/getting-started/) ("assumes … a Compose Multiplatform project");
osmdroid [osmdroid-android build.gradle](https://github.com/osmdroid/osmdroid/blob/master/osmdroid-android/build.gradle) (no Kotlin plugin) and
[6.1.20 POM](https://repo1.maven.org/maven2/org/osmdroid/osmdroid-android/6.1.20/osmdroid-android-6.1.20.pom).

Other minimums, all cleared by this app: Mapbox minSdk 21 / compileSdk 33 / OpenGL ES 3.0
([migrate-to-v11](https://docs.mapbox.com/android/maps/guides/migrate-to-v11/)); Google Maps
SDK minSdk 23 / compileSdk 34 ([config](https://developers.google.com/maps/documentation/android-sdk/config));
MapLibre minSdk 23 since 12.0.0 ([release notes](https://github.com/maplibre/maplibre-native/releases/tag/android-v12.0.0)), Java 11.

## 2. Cost, account, and license for a personal app

| | Account/billing | Free tier | License |
|---|---|---|---|
| **Mapbox** | Mapbox account + public token; card on file expected ([accounts FAQ](https://docs.mapbox.com/accounts/faq/how-do-i-pay-for-mapbox-services/)). SDK Maven repo is anonymous — no secret token needed ([install guide](https://docs.mapbox.com/android/maps/guides/install/), verified empirically) | **25,000 MAU/month free**, then $4/1k ([pricing](https://www.mapbox.com/pricing), [Android pricing guide](https://docs.mapbox.com/android/maps/guides/pricing/)). One user = 1 MAU = $0 | Proprietary (Mapbox ToS); mandatory attribution + telemetry opt-out UI ([LICENSE.md](https://github.com/mapbox/mapbox-maps-android/blob/main/LICENSE.md), [attribution](https://docs.mapbox.com/help/dive-deeper/attribution/)) |
| Google | **Billing-enabled Cloud project required** for an API key ([get-started](https://developers.google.com/maps/get-started), [usage-and-billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing)) | Since 2025-03-01: "Maps SDK" mobile SKU **unlimited free without a map ID**; with a map ID it bills as "Dynamic Maps" — 10,000 free/month then $7/1k ([pricing](https://developers.google.com/maps/billing-and-pricing/pricing), [SKU details](https://developers.google.com/maps/billing-and-pricing/sku-details)) | Proprietary ToS; **no caching/pre-fetching of map content** (§3.2.3, [GMP terms](https://cloud.google.com/maps-platform/terms)). Wrapper lib itself Apache-2.0 |
| MapLibre | None for the SDK (BSD-2, [LICENSE](https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md)). Tiles: [OpenFreeMap](https://openfreemap.org/) is keyless/unlimited ("no limits on the number of map views or requests") but donation-funded, no SLA; MapTiler/Stadia free tiers are non-commercial-only with API keys ([MapTiler pricing](https://www.maptiler.com/cloud/pricing/), [Stadia limits](https://docs.stadiamaps.com/limits/)) | Free | BSD-2 (SDK); tile-provider terms apply |
| osmdroid | None; defaults to OSM tile servers, whose [usage policy](https://operations.osmfoundation.org/policies/tiles/) demands a distinct User-Agent, forbids bulk download/offline prefetch, and warns "access may be withdrawn at any point" | Free | Apache-2.0 |

## 3. Feature comparison against the app's needs

| Need | Mapbox v11 | Google Maps + maps-compose 5.0.3 | MapLibre (pinned) | osmdroid |
|---|---|---|---|---|
| **Polyline quality / pace gradient** | GPU vector; `line-gradient` on a GeoJSON source with `lineMetrics: true` ([style spec](https://docs.mapbox.com/style-spec/reference/layers/), [Android example](https://docs.mapbox.com/android/maps/examples/line-gradient/)); Compose `PolylineAnnotation` for simple lines ([example](https://docs.mapbox.com/android/maps/examples/compose/add-polyline-annotations/)) | Good: `Polyline` composable + `StyleSpan`/`StrokeStyle.gradientBuilder` gradients (SDK ≥18.1.0, spans in maps-compose since v4.4.0) ([shapes](https://developers.google.com/maps/documentation/android-sdk/shapes), [v4.4.0 notes](https://github.com/googlemaps/android-maps-compose/releases/tag/v4.4.0)) | GPU vector; same `line-gradient` style-spec support ([MapLibre style spec](https://maplibre.org/maplibre-style-spec/layers/)) | Canvas-drawn `Polyline` over raster tiles; no gradient; wiki warns multipoint overlays are expensive ([wiki](https://github.com/osmdroid/osmdroid/wiki/Markers,-Lines-and-Polygons-(Java))) |
| **Live camera-follow** | Purpose-built: location puck + viewport plugin `followPuckViewportState` ([user-location guide](https://docs.mapbox.com/android/maps/guides/user-location/location-on-map/), [viewport example](https://docs.mapbox.com/android/maps/examples/viewport-camera/)); Compose `MapViewportState` | DIY: collect FusedLocation and call `CameraPositionState.animate()`; `isMyLocationEnabled` + pluggable `LocationSource` ([README](https://github.com/googlemaps/android-maps-compose#controlling-a-maps-camera), [location docs](https://developers.google.com/maps/documentation/android-sdk/location)) | `LocationComponent` with `CameraMode.TRACKING*` modes ([location component](https://www.maplibre.org/maplibre-native/android/examples/location-component/)) | `MyLocationNewOverlay.enableFollowLocation()` ([source](https://github.com/osmdroid/osmdroid/blob/master/osmdroid-android/src/main/java/org/osmdroid/views/overlay/mylocation/MyLocationNewOverlay.java)) |
| **Offline (runner loses signal)** | **Yes**: style packs + `TileStore` tile regions, ≤750 tile packs, included in free tier ([offline guide](https://docs.mapbox.com/android/maps/guides/offline/), [manage offline data](https://docs.mapbox.com/android/maps/guides/offline/manage-offline-data/)) | **No** — only a transient in-memory tile cache; ToS §3.2.3 forbids pre-fetching/caching map content ([GMP terms](https://cloud.google.com/maps-platform/terms)) | Yes: `OfflineManager`/`OfflineRegion` inherited from the GL fork ([source tree](https://github.com/maplibre/maplibre-native/tree/android-v13.3.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/offline)); tile-provider terms must allow it (OpenFreeMap states no such restriction; OSM raster forbids it) | Strong: MBTiles/SQLite/GEMF archives, `setUseDataConnection(false)` ([offline wiki](https://github.com/osmdroid/osmdroid/wiki/Offline-Map-Tiles)) — but prefetching from OSM servers violates the tile policy |
| **Dark/light styling** | Standard style `lightPreset` = dawn/day/dusk/**night** + `theme`, switchable at runtime without style reload ([Standard style guide](https://docs.mapbox.com/map-styles/standard/guides/)) | On 5.0.3: JSON `MapStyleOptions` dark style (normal map type only). Native `MapColorScheme` (DARK/LIGHT/FOLLOW_SYSTEM) needs SDK 19+ *and* maps-compose ≥6.0.0 — behind the Kotlin 2.x wall ([configure-map](https://developers.google.com/maps/documentation/android-sdk/configure-map), [styling](https://developers.google.com/maps/documentation/android-sdk/styling)) | Swap style JSON URL; OpenFreeMap ships a real Dark style ([openfreemap.org](https://openfreemap.org/)) | `TilesOverlay.INVERT_COLORS` color-matrix filter over raster tiles — crude ([source](https://github.com/osmdroid/osmdroid/blob/master/osmdroid-android/src/main/java/org/osmdroid/views/overlay/TilesOverlay.java)) |
| **Battery** | GPU vector (Vulkan/GLES3); official perf tooling (Performance Statistics API); changelog notes a render-thread-when-backgrounded issue to handle via lifecycle ([profiling](https://docs.mapbox.com/android/maps/guides/debugging-and-profiling/), [CHANGELOG](https://github.com/mapbox/mapbox-maps-android/blob/main/CHANGELOG.md)) | No official power guidance; lite mode exists but can't animate the camera → unusable live, fine for summary cards ([lite](https://developers.google.com/maps/documentation/android-sdk/lite)) | GPU vector, Vulkan default since 13.0.0 ([release](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.0.0)) — but pinned 13.0.2/11.0.0 predates or straddles that | Software raster on the UI thread; weakest for smooth live follow |
| **Compose interop** | First-party `extension-compose`, same version train as SDK, works against Compose 1.3+ ([README](https://github.com/mapbox/mapbox-maps-android/blob/main/extension-compose/README.md), [Compose guide](https://docs.mapbox.com/android/maps/guides/using-jetpack-compose/)) | Best-in-class wrapper — but frozen at 5.0.3 on this toolchain | `AndroidView` wrapper only (maplibre-compose needs Kotlin 2.3) | `AndroidView` wrapper only; **project archived Nov 2024** ([README](https://github.com/osmdroid/osmdroid/blob/master/README.md), GitHub API `archived: true`) |

## 4. Recommendation

**Mapbox Maps SDK for Android v11 + `com.mapbox.extension:maps-compose` (latest 11.x).**

Why it wins for this app:

1. **Zero toolchain upgrade.** It is the only candidate where the *latest* release is
   consumable by Kotlin 1.9.0 / Compose BOM 2023.08.00 (compiled with Kotlin 1.7.20,
   Compose 1.3.x-era). Google and MapLibre both force pinning to 2024-era library
   versions; MapLibre Compose is flatly unusable.
2. **Offline is a real requirement** ("runner may lose signal"), and Mapbox is the only
   billing-backed candidate with an offline API — Google prohibits caching contractually
   and offers no API. Pre-download a style pack + tile region for the home running area.
3. **Live-run fit**: `followPuckViewportState` + location puck is exactly the in-run
   camera-follow behavior, driven by the existing FusedLocation stream.
4. **Session detail fit**: `LineLayer` with `lineMetrics(true)` + `line-gradient` renders
   the pace-colored route directly from the `hr_samples` lat/lng/pace data.
5. **Cost**: 1 MAU ≪ 25,000 free MAUs → $0/month. Acceptable per user's confirmation of
   billing-backed tiers (card on file expected).

Accepted trade-offs:
- Proprietary license tied to an account in good standing; mandatory Mapbox/OSM
  attribution and a telemetry opt-out (satisfied by keeping the built-in attribution
  button visible).
- Pace-gradient lines require the style-layer API (`LineLayer` + GeoJSON source), not the
  simpler `PolylineAnnotation` composable.
- Handle map lifecycle carefully when backgrounding mid-run (known render-thread issue).

**Runner-up — MapLibre Native pinned to 13.0.2 (verify) or 11.0.0 (guaranteed) with
OpenFreeMap tiles**: choose this if a Mapbox account/card ever becomes unacceptable.
Costs: `AndroidView` interop instead of Compose-native, a pinned SDK, and a no-SLA
community tile host. **Rejected**: Google Maps (no offline — disqualifying for the live
use case — and Compose wrapper frozen at 5.0.3); osmdroid (archived Nov 2024, raster-only
rendering, invert-filter dark mode, OSM tile-policy exposure).

## 5. Toolchain-upgrade implications

- **Adopting Mapbox now: no upgrade required.** Kotlin 1.9.0, BOM 2023.08.00, compiler
  1.5.1, AGP 8.13.2, minSdk 26, Java 11 all exceed Mapbox v11 minimums.
- **If the app later upgrades to Kotlin 2.x + current Compose** (worthwhile eventually):
  Mapbox keeps working unchanged; the Google option would unlock current maps-compose
  8.x + `MapColorScheme`; MapLibre would unlock 13.3.1 + maplibre-compose. The map-SDK
  choice does not block, and is not blocked by, that upgrade.
- Only new build inputs: the `mapbox_access_token` string resource (public token — keep
  out of git like the existing `GEMINI_API_KEY` pattern in `local.properties`) and the
  Mapbox Maven repository (`https://api.mapbox.com/downloads/v2/releases/maven`,
  anonymous) in `settings.gradle.kts`.
