# Tides — Android tide tracker

Native Android app (Kotlin / Jetpack Compose / Material 3) showing tide times for the
user's location, plus a Jetpack Glance home-screen widget. Styled like the Pixel Weather app.

## Hard rules (do not violate)
- **Room = single source of truth.** UI and widget read ONLY from Room, never the network.
- **Widget never hits the network.** Reads the active station's Room cache in `provideGlance`.
- **No polling.** Tides are deterministic: fetch **30 days** at once per station, refresh to
  slide the window every **~3 weeks** (or when <9 days remain cached). Weather refreshes
  separately, ~1–2×/day or on open if stale.
- **No re-fetch on location switch.** Switching to a cached station costs zero API calls.
- **TideCheck quota: 50 req/day.** Only call it to: add a new station, slide the tide window,
  or search stations. Guard every call; handle 429 gracefully (fall back to cache, no retry loop).
- **No Hilt.** DI is manual via `di/ServiceLocator.kt` (held by `TideApp`).

## Stack
Kotlin · Compose + Material 3 · MVVM (ViewModel + StateFlow, unidirectional data flow) ·
Retrofit + OkHttp + kotlinx.serialization · Room · Jetpack Glance · WorkManager ·
FusedLocationProviderClient (COARSE) + built-in `Geocoder` · minSdk 26

## Data flow
Location (Fused, COARSE)
  └─> /stations/nearest         (only when location moved enough)
        └─> /station/{id}/tides?days=30
              └─> Room.replaceTidesForStation() [@Transaction] ← SINGLE SOURCE OF TRUTH
                    ├─> DAO Flow → TideViewModel → Compose UI
                    └─> DAO snapshot (selected station) → Glance widget (no network)

TideMath (pure Kotlin, no Android deps): derives current height, rising/falling, next high/low
from stored extremes via half-cosine interpolation. No time series fetched or stored.

## Multiple locations
Saved stations keyed by stationId in Room. Active station in single-row `app_state` table.
Switch via top location picker ONLY (no swipe pager). Fetch only on: first add, window expiry
(<9 days), or explicit pull-to-refresh.

## API — TideCheck
Base URL `https://tidecheck.com/api/` · key in `X-API-Key` header via `ApiKeyInterceptor`
· key from `local.properties` → `BuildConfig.TIDECHECK_API_KEY` (never hardcode).
- `GET /stations/nearest?lat=&lng=` → nearest station
- `GET /stations/search?q=` → free-text station search
- `GET /station/{id}/tides?days=30&datum=LAT` → extremes + dailyConditions
Heights in metres above ZH/LAT · timestamps ISO-8601 UTC · epoch millis in Room.
`datum=LAT` confirmed correct (Stage 0, 2026-06-17): SHOM residual 2–12 cm; `datum=MLLW` is
~2.9 m off and must never be used. Waterline formula: `water_IGN69 = ZH_Ref + H`.

## Weather — Open-Meteo
Free, no key, CC BY 4.0 (attributed in-app). Separate Retrofit instance (no TideCheck interceptor).
- Air: `api.open-meteo.com/v1/forecast` (daily max/min + current temp)
- Sea: `marine-api.open-meteo.com/v1/marine` (daily SST max + current)
Queried at STATION lat/lng (not device GPS) with `timezone=auto`. Failure degrades to "—",
never fails the tide refresh. Cached in `weather_daily` + station row current temps.

## Themes
Curated palettes (e.g. Marine & Gold, Ocean Dark) shared by app AND widget — switching a theme
re-skins both identically. Accent: blue = rising · gold/amber = falling (no pink). Subtle
day/night luminosity driven by sunrise/sunset (not a fixed clock window).

## i18n
FR + EN via `strings.xml` / `values-fr`. System locale default, manual override in settings.
`AppCompatDelegate.setApplicationLocales()` for live switching (no restart needed).
Widget renders in the app's selected locale, not the system default.

## Settings
Theme · Language (FR/EN) · Units (metric m/°C · imperial ft/°F) · Clock (12h/24h)
All persisted, applied app-wide AND in the widget.

## Module map (app/src/main/java/com/exilon/tides/)
`di/ServiceLocator.kt` — DI root · `data/TideRepository.kt` — ALL network logic ·
`data/local/` — Room DB, DAO, entities · `data/model/TideMath.kt` — pure, unit-tested ·
`ui/TideViewModel.kt` — single ViewModel · `ui/theme/` — curated palettes ·
`widget/TideWidget.kt` + `WidgetGraphics.kt` — Glance + bitmap pre-render ·
`work/TideRefreshWorker.kt` — slides tide window + weather refresh

## Conventions
- Epoch millis (`Long`) in Room; ISO-8601 parsed at DTO→entity boundary.
- One component per file under `ui/screen/components/`.
- `TideMath` must stay free of Android imports (unit-testable, shared by UI + widget).
- Widget mini-curve and arrow pre-rendered to Bitmap via `android.graphics.Canvas`
  (Glance/RemoteViews cannot draw Compose Canvas).