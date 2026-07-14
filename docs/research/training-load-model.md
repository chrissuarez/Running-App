# Training-Load / Fitness-Freshness Methodology for a HR-Only Run/Walk Coach

## Context

This app has heart rate at ~1 Hz, GPS pace/distance, and duration — **no power meter**. User settings reliably contain only `maxHr` (default 190) plus user-configured Zone-2 bounds; there is **no stored resting HR and no stored threshold/LTHR**. The target population is beginner-to-intermediate run/walk runners who take frequent walk breaks. This document compares the candidate load models (Banister TRIMP, Edwards zone-weighted TRIMP, Lucia TRIMP, TrainingPeaks hrTSS/HRSS, and Strava Relative Effort), evaluates each against our exact schema, then recommends one primary daily-load metric plus a fitness/fatigue/form curve (CTL/ATL/TSB) to build on top of it. The governing constraint is that **only `maxHr` is dependable** (and it may be an age estimate), **per-sample HR is available** in `hr_samples`, and **zone-time totals are already stored** in `sessions`.

## Comparison Table

| Model | Formula (short) | Params needed | Tolerance to rough params | Computable from our schema? | Run/walk pitfalls |
|---|---|---|---|---|---|
| **Banister TRIMP** | `Σ Δt · ΔHRr · 0.64·e^(1.92·ΔHRr)` (men) / `0.86·e^(1.67·ΔHRr)` (women); `ΔHRr = (HR−HRrest)/(HRmax−HRrest)` | max HR, **resting HR**, sex | **Low** — needs accurate HRmax **and** HRrest; exponential magnifies errors | Yes from `hr_samples` per-sample; **needs resting HR added**. Avg-BPM approximation possible but biased | Walk breaks pull avg BPM down → avg-based approximation under-counts; exponential term inflates late-session cardiac drift |
| **Edwards zone-weighted TRIMP** | `Σ (minutes in zone_i · i)`, zones = 50–60/60–70/70–80/80–90/90–100 %HRmax, weights 1–5 | **max HR only** | **High** — only depends on HRmax; step zones absorb small HRmax error | **Yes, directly** — from `hr_samples` binned into %HRmax deciles (or from `zone*Seconds` if those are decile zones) | Best-behaved: walk time falls into low zones (weight 1–2) and is correctly down-weighted, since it's per-sample not avg-based |
| **Lucia TRIMP** | `Σ (minutes in zone_i · i)`, 3 zones: <VT1 / VT1–VT2 / >VT2, weights 1,2,3 | **VT1 & VT2** (lab / ventilatory thresholds) | N/A — requires lab testing | **No** — VT1/VT2 not obtainable in-app | Same per-sample benefit as Edwards, but thresholds are unavailable |
| **HRSS (HR Stress Score)** | `HRSS = session-TRIMP / (1-hour-TRIMP-at-LT) · 100` | **LTHR** (+ resting/max for the underlying TRIMP) | Low — hinges on an accurate LTHR anchor | **No, not today** — no stored LTHR; would need adding + a threshold test | Threshold test hard for beginners; walk-heavy sessions poorly anchored |
| **hrTSS (TrainingPeaks)** | Time in LTHR-based zones → per-second TSS accumulated, normalized so 1 h @ threshold = 100 | **LTHR** | Low; **weak on variable/interval efforts** per TrainingPeaks | **No, not today** — needs stored LTHR | HR lag means intervals/surges under-scored; walk/run oscillation is exactly the variable pattern it handles worst |
| **Strava Relative Effort** | Proprietary: time in HR zones × progressively higher per-zone coefficients, then normalized across sport/athlete | max HR (age default) | High on paper, but formula undisclosed | **Not exactly** — proprietary, but the *concept* is reproducible as Edwards-style zone weighting | Concept identical to Edwards; our own Edwards implementation is the transparent stand-in |

## Model Details and Formulas

### Banister TRIMP

Banister's Training Impulse (1991) is `TRIMP = duration(min) × ΔHRratio × Y`, where `ΔHRratio` is fractional heart-rate reserve `= (HRexercise − HRrest) / (HRmax − HRrest)`, and `Y` is a sex-specific exponential weighting of that reserve fraction ([umit.net TRIMP guide](https://umit.net/ultimate-trimp-guide-runners/); [veohtu.com/trimp.html](https://www.veohtu.com/trimp.html)):

- Men: `Y = 0.64 · e^(1.92 · ΔHRratio)`
- Women: `Y = 0.86 · e^(1.67 · ΔHRratio)`

The exponential term models the disproportionate (roughly lactate-following) rise in physiological cost as intensity increases ([trainingimpulse.com/banisters-trimp](https://www.trainingimpulse.com/banisters-trimp-0); [RUNALYZE glossary](https://runalyze.com/glossary/trimp?_locale=en)). For a per-sample implementation the correct approach is to compute `ΔHRratio` for each ~1 Hz sample and integrate: `TRIMP = Σ_samples (Δt_i · ΔHRratio_i · 0.64·e^(1.92·ΔHRratio_i))`, which avoids the bias of using a single session-average HR. **Cost:** it requires a resting HR that the app does not store, and it is explicitly sensitive to the accuracy of both HRmax and HRrest — sources stress "Requires accurate HRmax and HRrest" ([umit.net](https://umit.net/ultimate-trimp-guide-runners/)).

### Edwards zone-weighted TRIMP (summated heart-rate zones)

Edwards (1993) divides the session into five heart-rate zones defined as percentages of HRmax and assigns integer weights 1–5 ([trainingimpulse.com/edwards-trimp](https://www.trainingimpulse.com/edwards-trimp)):

| Zone | %HRmax | Weight |
|---|---|---|
| 1 | 50–60% | 1 |
| 2 | 60–70% | 2 |
| 3 | 70–80% | 3 |
| 4 | 80–90% | 4 |
| 5 | 90–100% | 5 |

`Edwards TRIMP = Σ (minutes in zone_i × i)` ([trainingimpulse.com/edwards-trimp](https://www.trainingimpulse.com/edwards-trimp)). It needs **only HRmax** — no resting HR, no threshold. The coefficients are acknowledged to be arbitrary and without a dose-response physiological validation, but the method is the default in most commercial HR monitors precisely because it is cheap and robust ([trainingimpulse.com/edwards-trimp](https://www.trainingimpulse.com/edwards-trimp); [iamcoach.ai](https://www.iamcoach.ai/blog/trimp-training-load-explained)).

### Lucia TRIMP

Lucia et al. (2003) use three zones demarcated by the two ventilatory thresholds — low `<VT1`, moderate `VT1–VT2`, high `>VT2` — with coefficients 1, 2, 3; `TRIMP = Σ (minutes in zone_i × i)` ([trainingimpulse.com/lucias-trimp](https://www.trainingimpulse.com/lucias-trimp-0)). The physiological demarcation is less arbitrary than Edwards' deciles, but obtaining VT1/VT2 requires laboratory gas-exchange testing, so it is **not computable for our users** ([trainingimpulse.com/lucias-trimp](https://www.trainingimpulse.com/lucias-trimp-0)).

### HRSS (HR-based Stress Score)

HRSS expresses a session's TRIMP relative to the TRIMP of a one-hour effort at lactate threshold, scaled to 100: `HRSS = session-TRIMP / (1-hour-TRIMP-at-LT) × 100` ([veohtu.com/trimp.html](https://www.veohtu.com/trimp.html)). This gives a TSS-comparable 0–100+ scale but requires an LTHR anchor the app does not store.

### hrTSS (TrainingPeaks)

TrainingPeaks' hrTSS is built from heart-rate zones derived from the athlete's **lactate threshold heart rate (LTHR)**; it estimates the accumulated stress per unit time given the zone and sums it over the session, normalized so that **one hour at threshold = 100** (the same 100-point convention as power-based TSS) ([TrainingPeaks: TSS vs hrTSS](https://www.trainingpeaks.com/learn/articles/training-with-tss-vs-hrtss-whats-the-difference/); [TrainingPeaks TSS Explained](https://help.trainingpeaks.com/hc/en-us/articles/204071944-Training-Stress-Scores-TSS-Explained)). TrainingPeaks itself warns hrTSS is accurate for steady-state efforts but "begins to fall away when shorter and more intense efforts occur. The heart doesn't respond rapidly enough to weight efforts above threshold properly" — i.e., it under-scores variable/interval work ([TrainingPeaks: TSS vs hrTSS](https://www.trainingpeaks.com/learn/articles/training-with-tss-vs-hrtss-whats-the-difference/)). Run/walk oscillation is exactly this variable pattern, so hrTSS is a poor fit for our population even if we added LTHR.

### Strava Relative Effort

Relative Effort measures cardiovascular work by weighting **time spent in each heart-rate zone with progressively higher coefficients** (higher zone → higher coefficient), then normalizing against Strava's global dataset so scores compare across sports and athletes ([Strava Help: Relative Effort](https://support.strava.com/en-us/articles/15401794-relative-effort); [Strava Engineering: Quantifying Effort](https://medium.com/strava-engineering/quantifying-effort-through-heart-rate-data-e6a0e3dd6a52)). Strava tuned the coefficients on 10 k race data from thousands of athletes and applies sport-specific zone handling; the exact coefficients are **proprietary and undisclosed** ([Strava Engineering](https://medium.com/strava-engineering/quantifying-effort-through-heart-rate-data-e6a0e3dd6a52)). Relative Effort replaced the older "Suffer Score" and was developed with Dr. Marco Altini. **Conceptually it is Edwards zone-weighted TRIMP** with private coefficients and cross-sport normalization; by default Strava estimates HR zones from `220 − age` unless the user customizes them ([Strava Help: Heart Rate Zones](https://support.strava.com/hc/en-us/articles/216917077-Heart-Rate-Zones)). We cannot reproduce it exactly, but an in-house Edwards implementation is the transparent equivalent.

## Fitness / Fatigue / Form: CTL, ATL, TSB

TrainingPeaks' Performance Manager derives fitness and fatigue as **exponentially-weighted moving averages (EWMA) of daily training load** (TSS or TRIMP), with default time constants of **42 days for CTL** (Chronic Training Load = "Fitness") and **7 days for ATL** (Acute Training Load = "Fatigue"); form is `TSB = CTL − ATL` ("Training Stress Balance") ([TrainingPeaks: Science of the Performance Manager](https://www.trainingpeaks.com/learn/articles/the-science-of-the-performance-manager/); [TrainingPeaks: Fitness (CTL)](https://help.trainingpeaks.com/hc/en-us/articles/204071884-Fitness-CTL)). The model substitutes these simple EWMAs for the integral terms of Banister's original impulse-response model.

The exponential update, applied once per calendar day, is:

```
today = yesterday + (todayLoad − yesterday) · (1 − e^(−1/τ))
```

equivalently `today = yesterday · e^(−1/τ) + todayLoad · (1 − e^(−1/τ))`, with `τ = 42` for CTL and `τ = 7` for ATL, and `todayLoad = 0` on rest days ([TrainingPeaks: Science of the Performance Manager](https://www.trainingpeaks.com/learn/articles/the-science-of-the-performance-manager/)). Form is conventionally read from the **previous day's** values: `TSB = CTL(yesterday) − ATL(yesterday)`. Interpretation guide: roughly −10 to +10 is neutral, below −10 indicates accumulated fatigue, above +10 indicates freshness ([TrainingPeaks: Science of the Performance Manager](https://www.trainingpeaks.com/learn/articles/the-science-of-the-performance-manager/)). Crucially, **CTL/ATL/TSB are model-agnostic about the daily load input** — they work identically whether the daily number is TSS, Banister TRIMP, or Edwards TRIMP.

## Pitfalls Specific to Run/Walk Athletes

- **Walk breaks depress average HR.** Any model that collapses a session to a single `avgBpm` (e.g., a Banister approximation from `avgBpm` + `durationSeconds`) under-counts load, because walk-recovery time drags the mean down while the exponential weighting is then applied to an artificially low intensity. Our `walkBreaksCount` and `isRunWalkMode` flags mark exactly the sessions where this bias is worst. **Per-sample integration over `hr_samples` avoids it**, because each running interval and each walk interval is scored at its own HR.
- **HR lag and cardiac drift.** HR rises and falls slower than effort; over a long session HR drifts upward at constant pace (cardiac drift). Exponential models (Banister, hrTSS) magnify late-session drift into inflated load; step-zone models (Edwards) are more forgiving because a few extra bpm rarely crosses a decile boundary.
- **Variable-effort under-scoring.** TrainingPeaks explicitly notes hrTSS "the heart doesn't respond rapidly enough" during short/intense efforts, so it under-weights them ([TrainingPeaks: TSS vs hrTSS](https://www.trainingpeaks.com/learn/articles/training-with-tss-vs-hrtss-whats-the-difference/)). Run/walk is a continuous variable-effort pattern, so LTHR-anchored scores are structurally disadvantaged for our users.
- **Walk-recovery misattribution.** This is actually where zone-based per-sample scoring behaves *correctly*: walk-recovery time lands in Zone 1–2 and is down-weighted (weight 1–2), so the model naturally credits recovery time less than running time. The risk only appears if load is computed from `avgBpm` (which smears walk and run together) rather than from binned per-sample HR. This is a strong reason to compute the daily load from `hr_samples`, not from session averages.

## Recommendation

**Primary daily load: Edwards zone-weighted TRIMP, computed per-sample from `hr_samples` against %HRmax deciles.** Then build **CTL/ATL/TSB (42-day and 7-day EWMA, `TSB = CTL − ATL`) on top of that daily Edwards load** for the fitness/fatigue/form curve.

Rationale tied to our constraints:

1. **Only `maxHr` is reliable.** Edwards needs *only* HRmax — no resting HR, no LTHR — so it runs on the one parameter we trust, and its integer step-zones are tolerant of an age-estimated HRmax (a few bpm of HRmax error rarely moves a sample across a decile boundary). Banister, HRSS, and hrTSS all require parameters we don't have (resting HR / LTHR) and are explicitly *sensitive* to their accuracy; Lucia needs lab thresholds; Strava's coefficients are proprietary.
2. **Per-sample HR is available**, so compute Edwards by binning each ~1 Hz `hr_samples` row into %HRmax deciles and summing `Σ (seconds in zone_i × i)`. This correctly down-weights walk-recovery time and sidesteps the avg-BPM bias that hurts run/walk sessions — the single most important run/walk consideration.
3. **Zone times are already stored.** `zone1Seconds..zone5Seconds` can feed Edwards directly *if/when* those five zones are (re)defined as the 50–60…90–100 %HRmax deciles. Today they encode user-configured, Zone-2-centric bounds, so the initial implementation should compute Edwards deciles from `hr_samples`; a cheap follow-up is to also persist decile zone-times so the daily load is a pure lookup.
4. **It is the transparent, computable equivalent of Strava Relative Effort** — the same HR-zone-time weighting concept our users already understand from Strava — but fully in-house and explainable.
5. **CTL/ATL/TSB is model-agnostic**, so layering it on Edwards load gives standard, industry-recognizable fitness/fatigue/form curves without needing power or threshold data.

Optional upgrade path: once/if a resting HR is captured (e.g., from a morning reading or a wearable), offer **Banister TRIMP (per-sample)** as a higher-fidelity alternative daily-load input to the same CTL/ATL/TSB pipeline — but do not gate the v1 product on collecting resting HR or running a threshold test, which is friction our beginner run/walk audience will resist.
