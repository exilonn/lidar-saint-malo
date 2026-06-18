# Interactive Tide Map — Technical Plan (Level 3)

Scope Level 3: LiDAR-driven moving waterline, replacing the Level-2 OSM crossfade with real
terrain geometry and a geodetically-correct datum offset. **Pilot: Saint-Malo / Dinard (Ille-et-
Vilaine).** Level-2 is the fallback everywhere Litto3D is absent.

Inherits all Level-2 locked decisions (D1–D6 in `map-plan.md`). The ones that change:
- **D2** (per-polygon crossfade): overridden by Level-3 contour-band waterline when LiDAR is
  present; the crossfade stays as the fallback and for the substrate colouring layer.
- **D5** (datum mismatch accepted): overridden — Level-3 resolves the mismatch with a real
  ZH_Ref offset per station.

---

## §1 Datum — verification (Stage 0 action)

### Core formula
A terrain point is submerged when its IGN69 elevation is below the current tide surface:

```
submerged  ⟺  E_IGN69  <  H_ZH + ZH_Ref
```

- `H_ZH` — tide height above chart datum returned by TideCheck (metres)
- `ZH_Ref` — elevation of chart datum in NGF-IGN69 (metres, **negative** for French Atlantic/Channel
  ports; ZH is below IGN69 zero everywhere on the French coast)
- `E_IGN69` — terrain elevation from Litto3D (metres, NGF-IGN69)

Litto3D is already in NGF-IGN69, so no projection math is needed in-app. `ZH_Ref` is a single
scalar per station — not a spatial grid — because the datum surface is essentially planar at the
2–4 km scope we render.

### Stage 0 results — RESOLVED 2026-06-17

**D-L1 TideCheck datum — RESOLVED.**

`datum=LAT` is the correct parameter. The server honours it literally: every extreme differed
by exactly −2.892 m between `datum=MLLW` and `datum=LAT`, confirming they are distinct reference
levels. Cross-checked 6 high-water extremes (2026-06-16..18, station `saint_malo-410-fra-refmar`)
against the SHOM Annuaire des Marées: residual 2–12 cm across all six — within expected
FES2022-vs-official model tolerance. `datum=MLLW` was off by ~2.9–3.0 m and must not be used.

`CLAUDE.md` updated: `datum=LAT` is now the hard rule for all TideCheck tide calls.

**D-L2 ZH_Ref for Saint-Malo — RESOLVED.**

> **Saint-Malo — ZH_Ref = −6.2890 m NGF-IGN69**

Source: SHOM RAM shapefile (`data/ram/SHAPEFILE/RAM.dbf`), record 38, port "Saint-Malo /
Abords de Saint-Malo", 48.6412°N −2.0275°E. Geodetic determination by IGN, dated 2013/2014;
ZH itself at this reference port has been unchanged since 1923 (observatory-grade, not
interpolated).

Sanity cross-check passed:
- BMVE = 1.500 m above ZH → −4.789 m IGN69
- NM   = 6.780 m above ZH → +0.491 m IGN69
- PMVE = 12.200 m above ZH → +5.911 m IGN69
- Spring range PMVE − BMVE = 10.70 m  ✓ consistent with Saint-Malo's known average spring range

Residual uncertainty: TideCheck uses FES2022 tide model; SHOM Annuaire uses a refined model.
Observed residual 2–12 cm on 6 extremes. RAM ZH_Ref vs BathyElli known limit ~0.15 m. Combined
worst-case: ~0.27 m — acceptable for a disclaimer-bearing visualisation, not for navigation.

Confirmed datum param : **`datum=LAT`** — validated against SHOM Annuaire; MLLW off by ~2.9 m.
Confirmed ZH_Ref      : **−6.2890 m NGF-IGN69** — SHOM RAM record 38, geodetic det. 2013/2014.

**Sign convention check.** ZH_Ref is negative (ZH is below IGN69). Formula: `H_LAT + ZH_Ref`.
Example: a 3.0 m LAT tide → 3.0 + (−6.2890) = −3.2890 m IGN69. Terrain at −3.29 m IGN69 is at
the waterline. The observed 12.19 m spring high (Task 1) gives 12.19 + (−6.289) = +5.90 m IGN69
— consistent with PMVE at +5.911 m IGN69, confirming the formula and sign are correct.
A 0 m LAT extreme gives 0 + (−6.289) = −6.289 m IGN69 — matching the lowest exposed reef.

---

## §2 ZH_Ref storage in Room

**D-L3** Add `zhRef: Double?` to the existing station entity (or a one-to-one `StationDatumEntity`
if schema migration cost is a concern). `null` = no RAM match found → render stays at Level-2.

**D-L4 Nearest-RAM-port fallback.** Compile the ~40 French RAM reference ports into a
compile-time `List<RamPort>(name, lat, lng, zhRef)` constant in the data layer (a handful of
lines; not a Room table — RAM data changes only once per decade). When a new station is added:
1. Find the nearest RAM port by Haversine.
2. Store that port's `zhRef` in the station row.
3. Store `zhRef_source: String` ("observatory" | "nearest_ram:<portName>@<distKm>") for the
   limits note in the UI.

**Error bound (macrotidal Channel).** Adjacent RAM ports in the Gulf of Saint-Malo are
10–40 km apart. ZH varies by ~0.3 m per 10 km along the Channel coast (order-of-magnitude).
A 30 km gap → ~0.9 m ZH_Ref error → horizontal waterline error = 0.9 / tan(beach_slope). On
the Sillon (slope ≈ 1:80), that is ~72 m. On the Grève de Lancieux (slope ≈ 1:200), ~180 m.
Acceptable for a disclaimer-bearing visualization; not acceptable for navigation (hence the
disclaimer). For the pilot, ZH_Ref is first-hand observatory data, so this fallback error does
not apply.

---

## §3 Litto3D ingestion — offline pipeline (not runtime)

Litto3D is a French national LiDAR survey (IGN). Distribution: 1×1 km tiles, `.7z` archives,
~50–300 MB per tile. No API; download from `diffusion.shom.fr` or `geoservices.ign.fr`. Heights
in NGF-IGN69. Resolution: 0.5 m grid (coastal Litto3D-HD) or 1 m (standard). Always verify the
resolution for the specific tiles before starting.

### AOI for the pilot
Saint-Malo / Dinard intertidal zone: `bbox ≈ [48.615, −2.080, 48.680, −1.980]` (lat/lng).
Approximate tile count: 6–10 tiles. Download all tiles touching the bbox plus one-tile margin
(for geometry continuity at edges).

### Elevation step — D-L5: 0.25 m

Rationale: Saint-Malo spring range ≈ 13.5 m → 54 bands. On the Sillon (slope 1:80), one band =
~20 m horizontal, ≈ 2 pixels at the map's 4 km extent. Finer than 0.25 m (e.g., 0.1 m) would
be sub-pixel on-screen and multiplies the output size 2.5×. Coarser (0.5 m) produces visible
staircase artefacts on flat beaches. 0.25 m is the sweet spot.

### Processing pipeline (GDAL/Python, one-time offline)

```
Step 1  Unpack .7z tiles → GeoTIFF or ASC (no reproject yet)
Step 2  gdalbuildvrt mosaic.vrt *.tif   (virtual mosaic in source CRS)
Step 3  gdalwarp -t_srs EPSG:4326 -tr 0.00001 0.00001 mosaic.vrt dem_wgs84.tif
          (reproject to WGS84; 0.00001° ≈ 1 m; preserve elevations as-is, still IGN69)
Step 4  gdal_translate -projwin <bbox> dem_wgs84.tif dem_clip.tif
          (clip to AOI + 500 m buffer)
Step 5  Discretize: round each pixel to floor(E / 0.25) * 0.25
          (gdal_calc.py or numpy: dem_bands.tif, integer band IDs 0…N)
Step 6  gdal_polygonize dem_bands.tif -f GeoJSON bands_raw.geojson -mask …
          (one polygon per contiguous region of same band ID)
Step 7  ogr2ogr -simplify 2.0 bands_simp.geojson bands_raw.geojson
          (Douglas-Peucker at 2 m; removes sub-pixel jaggies, safe for 1:10 000 scale)
Step 8  Attach elevation property: band_id * 0.25 + E_min  →  "e" (float, IGN69 m)
Step 9  Merge features with same "e" into MultiPolygon, prune tiny slivers (area < 5 m²)
Step 10 Output: lidar_stmalo_v1.geojson  (one FeatureCollection, feature per band)
Step 11 gzip -9 → lidar_stmalo_v1.geojson.gz
```

Toolchain: GDAL ≥ 3.4, Python 3, `ogr2ogr` for simplification. No JTS in the pipeline. All
steps are idempotent; re-run with a new step size by changing Step 5 and bumping the version.

### Size budget

54 bands × ~10–20 simplified MultiPolygon features each ≈ 540–1 080 GeoJSON features. At
~500–2 000 vertices per feature after simplification: raw GeoJSON ≈ 2–5 MB; gzipped ≈ 400 KB–
1 MB. Well within GitHub release limits and a single LTE download.

If the uncompressed file exceeds 5 MB: increase the simplification tolerance to 3–4 m (Step 7)
or prune bands outside `[ZH_Ref − 0.5, ZH_Ref + 14.5]` IGN69 (bands that can never be
tidally relevant). FlatGeobuf is a future option (smaller, faster parse) but GeoJSON with gzip
is simpler for the pilot and natively understood by MapLibre.

### GeoJSON schema

```
FeatureCollection
  Feature per elevation band:
    properties: { "e": <float>  }   // floor elevation in IGN69 m (e.g. −5.75, −5.50 …)
    geometry:   MultiPolygon         // all terrain in [e, e+0.25) metres IGN69
```

`"e"` (short key) is intentional — it appears in every feature and in the MapLibre filter
expression; shaving 8 chars saves ~8 KB at 1 000 features.

---

## §4 GitHub Releases — delivery and cache

### Asset naming — D-L6

```
lidar_<zoneId>_v<N>.geojson.gz

Example: lidar_stmalo_v1.geojson.gz
```

`zoneId` is a stable short slug, not a TideCheck stationId. One zone covers multiple stations
that share the same DEM tile set (e.g., Saint-Malo and Dinard are both in `stmalo`). Version `N`
is an integer; bump on any pipeline-parameter change (step size, simplification tolerance, new
source tiles). Breaking the station→zone mapping out from the filename keeps the filename stable
across minor reprocessing.

### Manifest — D-L7

A single small JSON file on the same GitHub release tag:

```
lidar_manifest.json
{
  "version": 1,
  "zones": {
    "stmalo": {
      "asset": "lidar_stmalo_v1.geojson.gz",
      "stationIds": ["<tidecheck-id-saint-malo>", "<tidecheck-id-dinard>"],
      "bbox": [48.615, -2.080, 48.680, -1.980]
    }
  }
}
```

App fetches the manifest (small, cacheable with ETag) when a station is added or refreshed.
Manifest version bump → re-fetch all zone assets. Manifest is the only moving target; zone
assets are immutable once uploaded (content-addressed by `vN`).

### Room cache entity — D-L8

```kotlin
// New entity: LidarZoneEntity
// PrimaryKey: zoneId (String)
// Fields: zoneId, assetVersion (Int), fetchedAtMillis (Long), filePath (String)
```

Cache logic:
1. On station add/refresh, check manifest for the station's zoneId.
2. If `LidarZoneEntity.assetVersion == manifest.version` AND file exists → Level-3 ready.
3. Otherwise: download asset (background, same Worker as tide refresh), decompress with
   `GZIPInputStream` to `filesDir/lidar/<zoneId>_v<N>.geojson`, upsert entity.
4. **No expiry TTL** — invalidation is version-driven only. LiDAR DEM topology doesn't change;
   only a re-survey or a pipeline-parameter change warrants a new asset.
5. Widget never touches LiDAR data (consistent with Level-2 constraint).

Asset download goes through a **dedicated OkHttp client without the TideCheck `ApiKeyInterceptor`**
(GitHub releases are public; sending the API key to GitHub releases CDN must never happen).

---

## §5 Render

### New API — D-L9

Replace `setTideFraction(f)` for Level-3 with `setTideHeight(hZH: Float, zhRef: Double)` on
`TideMapController`. `MapScreen` already calls `TideMath.heightAt(extremes, t)` for the slider
readout — pass that value + the station's `zhRef` to the controller. `setTideFraction` stays for
Level-2 fallback.

```
targetElev = hZH + zhRef   // IGN69 metres — computed in TideMapController, not MapScreen
```

### MapLibre layer structure (Level-3 path)

```
[MapTiler basemap]                                 — unchanged
[estran-substrate  FillLayer from OSM]             — solid fill, opacity 0.85, always visible
                                                     (substrate type: sand/rock/mud from OSM tags)
[lidar-water  FillLayer from LiDAR GeoJSON source] — fill-color = palette.mapWater
                                                     filter: ["<", ["get", "e"], targetElev]
                                                     fill-opacity = 1.0
[basemap symbol layers (labels)]                   — above everything
```

The LiDAR water layer sits above the OSM substrate layer. Bands with `e < targetElev` render as
solid water — covering the substrate beneath them. Bands with `e ≥ targetElev` are hidden — the
substrate shows through. The result is a hard, geometrically-correct waterline that moves as the
slider changes, with no crossfade artefact.

On each `setTideHeight` call: update the filter expression (`targetElev` is a literal float in
the expression — rebuild the expression object and call `layer.setFilter()`). MapLibre re-renders
only the affected tiles. No network; no full layer rebuild.

The Level-2 crossfade layers (`estran-substrate` opacity and `estran-water` opacity modulation)
are deactivated when the LiDAR source is present. The OSM substrate FillLayer keeps its base
opacity but is no longer driven by fraction.

This **subsumes the deferred JTS moving-waterline** from the Level-2 plan: real contour bands
from LiDAR are a strictly better geometry source than JTS morphing OSM polygons.

### Level-2 / Level-3 switching — D-L10

`MapViewModel` exposes a `lidarState: LidarUiState` (Idle | Loading | Ready(zoneId, zhRef) |
Unavailable). `MapScreen` picks the render path:
- `LidarUiState.Ready` → load LiDAR GeoJSON source, call `setTideHeight`
- otherwise → keep Level-2 crossfade, call `setTideFraction`

Both paths share the same OSM `estran-substrate` layer; only the water layer and its driver differ.

---

## §6 Coverage + Level-2 fallback

**Litto3D national coverage** (as of 2024): all metropolitan French Atlantic and Channel coast,
plus Corsica and most of the overseas departments. Gaps: rivers above tidal limit, rocky cliffs
(point cloud, no DTM), some overseas territories.

Detection: if the manifest has no `zoneId` for the station → `LidarUiState.Unavailable` →
Level-2 fallback silently. No error shown; the "no intertidal data" note is already handled
for the Level-2 path. No UI change needed for the fallback case (same disclaimer either way).

---

## §7 Honest limits

| Limit | Magnitude | Impact |
|---|---|---|
| LAT ≈ ZH approximation (France) | < 0.05 m | Negligible waterline error |
| Nearest-RAM-port fallback (non-pilot) | 0.5–1.5 m ZH_Ref | 50–200 m horizontal on flat beach |
| Litto3D vertical accuracy (bare ground) | ±0.15 m (1σ) | ~12 m horizontal on Sillon |
| Litto3D capture date vs current beach | 0.1–1.5 m seasonal | 10–150 m horizontal |
| TideCheck datum (Stage 0 confirmed: datum=LAT) | 0.02–0.12 m observed residual | Within FES2022 model tolerance; negligible |
| Rocky/vegetated areas (poor LiDAR) | > 0.5 m | Waterline shape unreliable on rocky headlands |
| **DEM coverage gap (pilot tile set)** | N of ~48.6447°N and W of ~−2.026° uncovered | Intra-muros, the Sillon, Grand Bé, Bon-Secours fall **outside** the downloaded Litto3D tiles (north edge Y≈6 850 000). No waterline there — renders as basemap. v1.1 extends tiles north/west if obtainable; else documented. |
| **Bathtub model — no hydraulic connectivity** (residual, after v1.1 datum fix) | Negligible at realistic tides (measured §11.1) | At realistic highs the only "disconnected" flooding is the **locked harbour basins** (Vauban/Duguay-Trouin/Bas-Sablons), which are genuinely always full → correct as water. No significant wrongly-flooded dry land. Connectivity re-scoped in v1.3 and **recommended deferred** (§11.4). |
| **FES2022 model vs SHOM RAM harmonic bounds** | H_LAT observed −1.4 m (production, below PBMA 0.01 m); occasional exceedance above PHMA possible | FES2022 is a continuous spectral model; SHOM RAM PBMA/PHMA are harmonic-table extremes derived from a fixed 18.6-year period. The model and the reference are different things. App flags (does not clamp) out-of-bound values in both production and debug display (v1.3, §12 Change A). Not investigated further. |

Permanent UI disclaimer: `map_disclaimer` (`"Indicatif uniquement — ne pas utiliser pour la
navigation"`) is already in both `strings.xml` and `values-fr/strings.xml`. For Level-3, append
the `zhRef_source` string to a second-line tooltip if `zhRef_source` is `nearest_ram:*` (i.e.,
not observatory-grade). Leave the disclaimer unchanged for the pilot (observatory data).

---

## §8 Stage breakdown

### Stage 0 — Datum verification (no app code) · **COMPLETE** (2026-06-17)
1. ✓ TideCheck honours `datum=LAT` literally; `datum=MLLW` is ~2.9 m off. CLAUDE.md updated.
2. ✓ SHOM RAM shapefile parsed; Saint-Malo ZH_Ref = −6.2890 m NGF-IGN69 (record 38, IGN 2013/2014).
3. ✓ 6 extremes cross-checked against SHOM Annuaire; residual 2–12 cm; formula and sign confirmed.
4. n/a — datum = ZH/LAT confirmed; no correction term needed.
*Deliverable: §1 filled in with verified numbers. Gate cleared for Stage 1.*

### Stage 1 — Offline preprocessing pipeline · **COMPLETE** (2026-06-17)
1. ✓ 17 Litto3D tiles unpacked (`data/litto3d-raw/0330_6850/…`); mosaic + clip in Lambert-93.
2. ✓ Vectorized 43 contour bands ([−4.789, +5.911] m IGN69, 0.25 m step) with `rasterio.features.shapes`
   + `unary_union` in L93, reprojected to WGS-84.
3. ✓ Noise filter: < 4 m² sub-polygons dropped. DP 2.0 m simplification (`preserve_topology=True`).
4. ✓ 43-feature GeoJSON with `z_min_m / z_max_m` properties. 8.7 MB uncompressed.
*Script: `data/scratch/pipeline_stage1b.py` + `data/scratch/finalize_4m2_2m.py`.*
*v1.0 asset published at `https://github.com/exilonn/lidar-saint-malo/releases/tag/lidar-saint-malo-v1.0`.*
*Defects found in field — see §9 for analysis and v1.1 fix plan.*

### Stage 2 — App integration (fetch + cache + render) · **COMPLETE** (2026-06-17)
1. ✓ Room: `LidarZoneEntity` (zoneId PK, assetVersion, fetchedAtMillis, filePath); DB migration 9→10.
2. ✓ `LidarRepository`: hardcoded zone map (`saint_malo-410-fra-refmar → stmalo`); dedicated OkHttp
   client (no TideCheck key); version-checked download → `filesDir/lidar/<zone>_v<N>.geojson`.
3. ✓ `ServiceLocator`: `githubOkHttp` + `lidarRepository` lazy singletons.
4. ✓ `MapViewModel`: `LidarUiState` sealed class; `loadLidar(stationId)` mirrors `loadEstran` pattern.
5. ✓ `TideMapController`: `loadLidar(geoJson, bmve, pmve)`; `applyLidarLayers()` freezes Level-2
   crossfade (substrate @ 0.85, water @ 0.0); adds `lidar-water` FillLayer with filter
   `["<", ["get", "z_min_m"], targetElev]`; `setTideFraction` branches on `lidarActive`.
6. ✓ `MapScreen` + `MainActivity` wired; Level-2 fallback intact for all non-LiDAR stations.
*Deliverable: Saint-Malo shows LiDAR waterline; all other stations keep Level-2 crossfade.*

### Stage 2 v1.1 — Render datum fix · **COMPLETE** (2026-06-18)
1. ✓ Removed fraction→[BMVE,PMVE] mapping. `LidarZoneInfo` now carries `zhRef` instead of `bmveIgn69`/`pmveIgn69`.
2. ✓ Added `TideMapController.setTideHeight(hZH)`: computes `targetElev = hZH + lidarZhRef` and updates the filter.
3. ✓ `MapScreen` drives LiDAR via `LaunchedEffect(height, lidar)` → `setTideHeight`; `setTideFraction` is a no-op when LiDAR is active (Level-2 layers frozen, no interference).
4. ✓ `MapViewModel.LidarUiState.Ready`, `LidarResult.Ready` updated to carry `zhRef`; `LidarRepository.ZONES` updated.
*At H_LAT=8.2 m (screenshot case), targetElev = 8.2 + (−6.289) = +1.911 m IGN69. Gare/Découverte ground is at +3.1 m → stays dry.*

### Stage 1 v1.1 — Pipeline fixes + republish · **COMPLETE** (2026-06-18)
1. ✓ `finalize_4m2_2m.py`: two-pass GEOS `make_valid` (L93 before reproject + WGS-84 after) + `orient(sign=1.0)` in both passes + zero-area sub-polygon drop.
2. ✓ Before: 29/43 self-intersecting, 11876/11877 CW exterior rings. After: 0/43 invalid, 0/12002 real CW rings (26 zero-area float64 rounding artifacts, harmless to earcut).
3. ✓ Asset: `lidar-saint-malo-v1.1.geojson`, 8.76 MB uncompressed / 2.86 MB gzip-9.
4. ✓ Published: `https://github.com/exilonn/lidar-saint-malo/releases/tag/lidar-saint-malo-v1.1` — URL resolves (302→CDN).
5. ✓ `LidarRepository.ZONES`: `assetVersion = 2`, URL → v1.1. Room cache invalidation automatic on next open.
*Honest limit: intra-muros, Sillon beach, Rance barrage area north/west of Y≈6 850 000 have no tile coverage. Those areas render as basemap (no LiDAR waterline) — transparent where no polygon, fallback to basemap. Tile extension deferred. OSM sea-mask evaluated and rejected.*

### Stage 1 v1.2 — Widen vectorization range to true astronomical extremes · **COMPLETE** (2026-06-18)
1. ✓ SHOM RAM record 38: PBMA = 0.01 m above ZH = **−6.279 m IGN69**; PHMA = 13.59 m above ZH = **+7.301 m IGN69**.
2. ✓ Vectorization range widened from [BMVE, PMVE] = [−4.789, +5.911] to **[PBMA−1.0, PHMA+1.0] = [−7.279, +8.301] m IGN69** (0.25 m step). No clamping (DEM min −16.93 m, max +53.3 m both well outside). Fixes the freeze where tides below H_LAT 1.5 m or above 12.2 m pinned the waterline at the lowest/highest band.
3. ✓ **63 bands** (was 43), 0 empty bands; **0/63 invalid features**, all exteriors CCW (33 zero-area float64 artifacts, harmless). 19,714 sub-polygons, 365,441 verts.
4. ✓ Asset `lidar-saint-malo-v1.2.geojson` — 14.71 MB uncompressed / 4.78 MB gzip-9. `pipeline_stage = "1C-v1.2"`.
5. ✓ Published `https://github.com/exilonn/lidar-saint-malo/releases/tag/lidar-saint-malo-v1.2`; `LidarRepository.ZONES`: `assetVersion = 3`, URL → v1.2, content-validation expected-stage → `1C-v1.2`.
*Scripts: `data/scratch/pipeline_stage1b.py` (mosaic reused) + `finalize_4m2_2m.py`. Field follow-up + v1.3 plan in §11.*

### Stage 1 v1.3 + App v1.3 · **COMPLETE** (2026-06-18)

**Change A (app-only, debug + production):**
1. ✓ `DEBUG_H_LAT_MAX_M` raised from 14.0 to **14.6 m** — top buffer band (z_max +8.301 = H_LAT 14.59) now reachable in QA.
2. ✓ Physical-bound flags added (debug readout + production height row): `>PHMA — non physique` (H_LAT > 13.59) and `<PBMA — niveau improbable` (H_LAT < 0.01). Values are **not clamped** — FES2022 is a continuous model that can predict H_LAT slightly below the harmonic-table PBMA (observed −1.4 m in production). Flag only; no suppression. See §7 honest limits.

**Change B (pipeline + render):**
1. ✓ Permanent water floor: **505,981 DEM cells** at −16.93 … −7.279 m IGN69 (deep Rance/main channel), vectorized as a single `permanent: true` feature (band_idx=−1, z_max_m=−7.279). Rendered as always-on water via `lidarWaterFilter` `permanent == true` branch — **not** subject to the `z_min_m < targetElev` test.
2. ✓ **64/64** bands retained (63 tide + 1 floor), **0/64 invalid features**, all exteriors CCW. Item 2 confirmed: 43 normal-range bands ([−4.789, +5.911]) unchanged in geometry.
3. ✓ Asset `lidar-saint-malo-v1.3.geojson` — 14.78 MB uncompressed / 4.81 MB gzip-9. `pipeline_stage = "1C-v1.3"`.
4. ✓ Published `https://github.com/exilonn/lidar-saint-malo/releases/tag/lidar-saint-malo-v1.3`; `LidarRepository.ZONES`: `assetVersion = 4`, URL → v1.3, expected-stage → `1C-v1.3`.
5. ✓ `assembleDebug` + `assembleRelease` both BUILD SUCCESSFUL. Release builds unaffected by debug flag logic (R8 eliminates dead `BuildConfig.DEBUG` branches).

**Deferred (with rationale, not re-opened):** Hydraulic connectivity (~nil ROI at realistic tides; naive version empties locked basins). Rance/Troctin clip-boundary edge (cosmetic; same root as coverage gap).

---

## §9 v1.1 Defect report — field findings (2026-06-17)

Four defects confirmed from screenshots after Stage 2 ship, then **grounded in direct measurement**
of `mosaic_lambert93.tif`, the v1.0 GeoJSON, and the Stage 2 render code (not inferred from the
screenshots alone). The measurements overturned two of the initial guesses — see §9.0.

### §9.0 What the measurements showed (the surprises)

Run against the actual data (`data/scratch/output/`):

- **The DEM does not cover the camera view.** Tiles stop at Lambert-93 Y≈6 850 000 (≈48.6447°N).
  The map camera is a ±2 km box around the tide gauge (48.6412°N, −2.0275°E), reaching 48.6592°N.
  **Intra-muros (48.6493°N), the Sillon, Grand Bé are entirely outside the DEM.** The published
  v1.0 bands span only lat [48.6144, 48.6457] — i.e. ~38 % of the camera's height (the iconic
  northern half) has **no band data at all**. → This is the real cause of #1 and #3, not a
  "missing cap band" or a "missing sea mask".
- **The flooding urban ground is fully measured and not low enough to flood at the screenshot
  tide.** A 80 m window over the railway station reads 0 % nodata, elevation +2.6…+3.7 m IGN69
  (median +3.1). At the screenshot's 8.2 m ZH the real water is only 8.2 + (−6.289) = **+1.911 m
  IGN69** — below +3.1, so the gare should be *dry*. It floods only because the renderer maps the
  slider's window-normalized fraction onto [BMVE, PMVE], putting slider-top at +5.911 m. → #4 is a
  **render datum bug**, not (primarily) the bathtub model.
- **Geometry really is malformed.** Of 43 v1.0 features, **29 are invalid (self-intersection)** and
  **11 876 / 11 877 exterior rings (100 %) are wound clockwise** — the opposite of RFC 7946. →
  confirms #2 and shows both `make_valid` *and* winding correction are required.
- **No `masque_source` layer exists on disk.** The unpacked tiles contain only `MNT1m/*.asc`. →
  the Litto3D sea/land mask option is unavailable without re-downloading the full product.

### §9.1 Defect table (corrected)

| # | Visual symptom | Root cause (measured) | Fix lands in |
|---|---|---|---|
| 2 | Radial fan / triangle shards across polygons | 100 % of exterior rings wound CW (RFC 7946 wants CCW) **and** 29/43 features self-intersecting. MapLibre's earcut mis-triangulates CW/invalid rings. | Pipeline (`finalize_4m2_2m.py`) |
| 4 | Low urban ground (Découverte, gare) floods at high slider | **Render datum bug.** Slider drives `TideMath.fractionAt` (normalized to the *window's* min/max), then `TideMapController` maps it onto [BMVE, PMVE] → slider-top = +5.911 m IGN69 even when the real high is 8.2 m ZH = +1.911 m. Over-floods by up to ~4 m. Gare is solid measured ground at +3.1 m. | Render (`MapScreen` + `TideMapController`) |
| 1 | Intra-muros & northern coast render as sea | **DEM coverage gap** (primary): tiles end at ≈48.6447°N; intra-muros at 48.6493°N has *no data*. **No above-PMVE cap** (secondary): in-coverage high ground has no polygon. | Pipeline (tiles + cap band) |
| 3 | Rectangular solid patch | **Coverage/clip boundary**: the ±3 km clip extends past the mosaic; the N & W strips are boundless-nodata, leaving a straight rectangular edge. Same root cause as #1. | Pipeline (tiles) |

Note on terminology: "no polygon = water" is only a *problem* where the missing area should be
**land**. Open sea and flooded harbour basins correctly render as the basemap water colour, which
is set to the same `palette.mapWater` as the `lidar-water` layer — so they already look like water.
This is why the sea-mask idea is demoted below.

---

### §9.2 Defect #2 — Geometry validity + winding (pipeline fix) — **CONFIRMED**

**Measured.** `shapely` reports 29/43 v1.0 features invalid, all "Self-intersection"; and 11 876 of
11 877 exterior rings have negative signed area (clockwise). RFC 7946 §3.1.6 requires exterior
rings CCW (positive area), holes CW. MapLibre Native triangulates fills with earcut, which infers
interior/winding from ring orientation; universally-CW exteriors plus self-intersections produce
the radial fan/shard tessellation seen on screen.

**Where it comes from.** Two independent faults: (1) the *winding* is wrong from the raster stage —
`rasterio.features.shapes` → `unary_union` → `shapely.mapping()` never enforces RFC 7946, so the
exteriors come out CW; (2) the *self-intersections* are introduced by `simplify(2.0,
preserve_topology=True)` collapsing thin slivers. Both must be repaired.

**Decision D-L11** — in `finalize_4m2_2m.py`, after `simplify()`, before reprojecting to WGS-84:

1. `simp = simp.buffer(0)` — canonical self-intersection repair (returns a valid geometry).
2. Drop if `simp.is_empty or not simp.is_valid` after `buffer(0)`.
3. `simp = ensure_rfc7946_winding(simp)` — `shapely.geometry.polygon.orient(sign=1.0)` per polygon
   (positive/CCW exterior). Run **after** `buffer(0)`, which can change orientation.

```python
from shapely.geometry.polygon import orient as _shapely_orient

def ensure_rfc7946_winding(geom):
    if geom.geom_type == 'Polygon':
        return _shapely_orient(geom, sign=1.0)
    if geom.geom_type == 'MultiPolygon':
        return MultiPolygon([_shapely_orient(p, sign=1.0) for p in geom.geoms])
    return geom
```

No render change. (Self-check after regen: `all(shape(f['geometry']).is_valid for f in features)`
and 0 CW exterior rings.)

---

### §9.3 Defect #4 — Render datum bug (render fix) — **PRIMARY, previously missed**

**Measured.** The railway-station window is 0 % nodata, +2.6…+3.7 m IGN69 (median +3.1). At the
screenshot tide of 8.2 m ZH the true water surface is 8.2 + (−6.289) = **+1.911 m IGN69**, so the
gare should be dry. It floods because of the render path, not the data or the bathtub model.

**Mechanism.** `MapScreen` computes `fraction = TideMath.fractionAt(extremes, t, nowAnchor,
windowEnd)`, which (by design, see its docstring) normalizes to the **window's** lowest/highest
water — *not* an absolute datum. `TideMapController.lidarWaterFilter` then does
`targetElev = bmve + f*(pmve − bmve)`. So slider-top (f≈1) always means +5.911 m IGN69, even on a
neap day whose real high is only +1.911 m. Result: everything below +5.911 m floods — a ~4 m
over-flood that submerges all low ground including the gare. This is exactly the failure §5/D-L9
warned about; Stage 2 took a `setTideFraction` shortcut instead of the specified `setTideHeight`.

**Decision D-L15** — use absolute height. `MapScreen` already computes `height =
TideMath.heightAt(extremes, selectedMillis)` for the readout; feed that to the LiDAR path:

- Add `zhRef: Double` to `LidarZoneInfo` (Saint-Malo = −6.2890) and carry it through
  `LidarResult.Ready` / `LidarUiState.Ready` to `TideMapController`.
- New controller entry point `setTideHeight(hZH: Double)`; compute `targetElev = hZH + zhRef`
  (the documented `water_IGN69 = ZH_Ref + H` formula). Filter stays `["<", z_min_m, targetElev]`.
- In `MapScreen`, when `lidar is LidarUiState.Ready`, drive the controller from `height` (metres
  ZH) instead of `fraction`. Keep the `fraction` path for the Level-2 fallback.
- `bmveIgn69`/`pmveIgn69` are no longer needed for the filter (any height works); keep them only
  as metadata if desired.

Cheap, render-only, no asset change. This single fix removes the dominant visible error.

---

### §9.4 Defect #1 — Coverage gap + above-PMVE cap (pipeline fix)

**Measured.** DEM north edge ≈48.6447°N; intra-muros at 48.6493°N is outside it; v1.0 bands reach
only lat 48.6457. So intra-muros and the northern beaches have **no elevation data** — no cap band
can help there (there is nothing to cap). Two distinct sub-fixes:

**(a) Coverage — D-L14 (proper fix, needs tiles).** The Stage 1 tile selection stopped at northing
6850; the AOI bbox in §3 (`north = 48.680`) needs tiles up to ≈6852. Download the missing northern
(and one western) Litto3D tiles — `0329…0334_6851`, `0329…0334_6852`, `0329_6846…6850` — unpack
into `data/litto3d-raw/`, add their keys to `TILES_*` in `pipeline_stage1b.py`, and re-run. This
makes coverage exceed the camera box on all sides, removing both the intra-muros gap (#1) and the
rectangular boundary (#3). **If the tiles cannot be obtained in this pass**, ship the other v1.1
fixes and record coverage as a documented limit (§7) — the southern/eastern waterline is still
correct and greatly improved by D-L15 + D-L11.

**(b) Above-PMVE cap — D-L12 (for high ground that IS in coverage).** Terrain above PMVE
(+5.911 m) has no tide band. Add one capping feature so in-coverage rock/walls render as permanent
land. After the band loop in `pipeline_stage1b.py`:

```python
cap_mask = valid_clip & (clip > Z_MAX)          # real terrain above PMVE = land (no sea is > 5.911 m here)
if cap_mask.sum() > 0:
    cap_shapes = rasterio.features.shapes(
        cap_mask.astype(np.uint8), mask=cap_mask.astype(np.uint8), transform=clip_tf)
    cap_polys = [shp_shape(gd) for gd, v in cap_shapes if v == 1]
    if cap_polys:
        features.append({
            "type": "Feature",
            "geometry": mapping(reproject(unary_union(cap_polys))),
            "properties": {
                "z_min_m": round(Z_MAX, 4), "z_max_m": None,
                "band_idx": n_bands, "pixel_count": int(cap_mask.sum()),
                "datum": "IGN69", "band_type": "land",
            },
        })
```

No land mask is needed for the cap: nothing in the DEM reads above +5.911 m IGN69 except real
terrain (the sea surface, where present, is nodata, not a >5.911 m elevation). The cap feature
flows through `finalize_4m2_2m.py` (4 m² filter, DP, `buffer(0)`, winding) unchanged.

*Render — D-L12 layer.* The water filter never shows `band_type=="land"` (z_min_m=5.911 is never
< targetElev unless the tide tops PMVE). Add a dedicated always-on `lidar-land` layer above
`lidar-water`:

```kotlin
val landFill = FillLayer(LIDAR_LAND_LAYER, LIDAR_SOURCE).withProperties(
    PropertyFactory.fillColor(palette.mapLand.toArgb()),
    PropertyFactory.fillOpacity(1.0f),
    PropertyFactory.fillAntialias(true),
)
landFill.setFilter(Expression.eq(Expression.get("band_type"), Expression.literal("land")))
if (firstSymbolId != null) style.addLayerBelow(landFill, firstSymbolId) else style.addLayer(landFill)
```

Inserted after the `lidar-water` `addLayerBelow` (same `firstSymbolId`): MapLibre inserts each
layer immediately below the reference, so the later (land) sits above water — correct occlusion.
Add `const val LIDAR_LAND_LAYER = "lidar-land"` and reskin it in `setPalette()` like `lidar-water`.

---

### §9.5 Defect #3 — Rectangular patch = coverage boundary (pipeline fix)

**Measured.** The mosaic is 4000×5000 m (X 329 999.5–333 999.5, Y 6 845 000.5–6 850 000.5) while
the pipeline clips a ±3 km box around the gauge with `boundless=True, fill_value=NODATA`. The
northern strip (Y 6 850 000–6 852 679) and western strip (X 326 838–329 999) of the clip are
therefore **entirely fill-nodata**, producing a straight rectangular boundary between real bands
and empty space — the "solid patch" on screen.

**Decision.** Same fix as #1(a): **extend the tiles** (D-L14) so coverage surrounds the camera.
That removes the in-frame boundary. No separate construct needed.

**Sea mask — DEMOTED (was D-L13).** A baked OSM-coastline land/sea mask was considered and is
**not adopted for v1.1**, for three reasons:
1. *Unnecessary.* Open-sea and flooded-basin nodata already render as basemap water, which is the
   same colour as `lidar-water` — they look correct without a mask.
2. *Fragile.* OSM `natural=coastline` ways do not close into rings inside a bbox, so the proposed
   `shapely.ops.polygonize(ways)` returns **zero** polygons → the "all-land fallback" → no masking
   at all. A correct mask would have to close the coastline against the bbox rectangle and
   classify faces by a seed point — more code than its benefit here.
3. *Source unavailable.* The authoritative alternative, Litto3D `masque_source`, is **not on disk**
   (tiles contain only `MNT1m/`), so it would require re-downloading the full product.

If, after D-L14, residual seaward sliver polygons look wrong, revisit a *robust* mask (close
coastline against the clip rectangle, or fetch OSM `natural=water`/dock polygons) in v1.2.

---

### §9.6 Defect #4 residual — bathtub model (deferred to v1.2)

After D-L15 (absolute height), the gare and most of Découverte stop flooding except when the
**real** tide exceeds their true ground elevation (e.g. +3.1 m IGN69 ⇒ ~9.4 m ZH, a large spring
high). At those genuine highs the bathtub model still floods dike/embankment-protected low ground
because it has no hydraulic connectivity. This residual is much smaller than the pre-v1.1 error.

**v1.2 path.** In `pipeline_stage1b.py`, after building each band mask, keep only the
connected-component(s) (`scipy.ndimage.label`) that touch an open-sea seed cell, so disconnected
inland depressions are excluded. Cost: +5–10 min pipeline; asset size unchanged or smaller.
Implement after v1.1 field validation. Until then the existing `map_disclaimer` covers it.

---

### §9.7 v1.1 change summary

| Item | v1.0 | v1.1 | Defect |
|---|---|---|---|
| Render tide driver | window fraction → [BMVE,PMVE] | **absolute `hZH + zhRef` (IGN69)** | #4 (primary), #1 |
| Geometry validity | 29/43 invalid, 100 % CW rings | `buffer(0)` + `orient(sign=1)`, all valid/CCW | #2 |
| Above-PMVE land | none | cap feature `band_type="land"` + `lidar-land` layer | #1 (in-coverage) |
| DEM coverage | stops at ≈48.6447°N | extend tiles N/W **if obtainable**, else documented limit | #1, #3 |
| Sea/land mask | — | **not adopted** (unnecessary + fragile + source absent) | #3 |
| Hydraulic connectivity | — | deferred to v1.2 | #4 (residual) |
| Asset / version | `…v1.0.geojson` / `assetVersion=1` | `…v1.1.geojson` / `assetVersion=2` | — |
| Feature properties | `z_min_m,z_max_m,band_idx,pixel_count,datum` | + `band_type` (`null`\|`"land"`) | — |

---

## §10 Consolidated implementation prompt — v1.1 single Sonnet pass

The following prompt is self-contained. Copy it into a new conversation to implement all v1.1
changes in one pass. It covers the pipeline, the GitHub Releases publish, and the app code.

---

**— BEGIN PROMPT —**

You are implementing v1.1 of the LiDAR waterline for the Tides Android app (Saint-Malo). The v1.0
asset/render has four confirmed defects, root-caused by measurement (see §9). Apply the changes
below, in order. Do not start a new coastal zone or change unrelated app code.

Changes, by impact:
1. **Render datum fix (do this first — biggest visible win, no asset regen).** Drive the LiDAR
   waterline from absolute IGN69 height (`hZH + zhRef`), not the window-normalized slider fraction.
2. **Geometry validity + winding** in the pipeline (`finalize_4m2_2m.py`) — fixes the triangle shards.
3. **Permanent-land cap band** (pipeline) + `lidar-land` render layer — fixes above-PMVE high ground.
4. **DEM tile coverage** (pipeline) — *conditional*: only if the missing northern/western Litto3D
   tiles can be obtained this pass; otherwise ship 1–3 and record coverage as a documented limit.
5. **Filename + version bump** and republish.

NOT in v1.1: the OSM/Litto3D sea mask (demoted — see §9.5) and hydraulic connectivity (v1.2).
Changes 2–4 require one regenerated asset (v1.1); changes 1 + the `lidar-land` layer are app code.

### Repository layout

```
D:\Perso\TideApp\
  data\scratch\
    pipeline_stage1b.py          ← Stage 1B: mosaic → clip → vectorize tide bands
    finalize_4m2_2m.py           ← Stage 1C: noise filter, DP 2.0 m, write final GeoJSON
    output\
      mosaic_lambert93.tif       ← intermediate, reuse
      waterline_bands.geojson    ← Stage 1B output
      lidar-saint-malo-v1.0.geojson   ← currently published
  app\src\main\java\com\exilon\tides\
    map\TideMapController.kt     ← Level-3 render; add lidar-land layer here
    data\LidarRepository.kt      ← bump assetVersion + URL here
```

Published GitHub repo: `https://github.com/exilonn/lidar-saint-malo`  
Current asset URL: `https://github.com/exilonn/lidar-saint-malo/releases/download/lidar-saint-malo-v1.0/lidar-saint-malo-v1.0.geojson`

Current GeoJSON feature properties: `z_min_m` (floor IGN69 m), `z_max_m` (ceiling), `band_idx`,
`pixel_count`, `datum`. 43 tide bands from −4.789 to +5.911 m step 0.25 m.
Current render filter: `["<", ["get", "z_min_m"], targetElev]`.

---

### Change 1 — Render: absolute IGN69 height, not window fraction  (app; do first)

Root cause (measured): `MapScreen` drives the LiDAR layer from `TideMath.fractionAt(...)`, which
normalizes to the *slider window's* min/max, then `TideMapController` maps that onto [BMVE, PMVE].
So slider-top = +5.911 m IGN69 even when the day's real high is 8.2 m ZH = +1.911 m → up to ~4 m
over-flood. Fix: use the documented `water_IGN69 = ZH_Ref + H` directly. `MapScreen` already has
`height = TideMath.heightAt(extremes, selectedMillis)`.

**`LidarRepository.kt`** — add `zhRef` to the zone info and result:
```kotlin
data class LidarZoneInfo(
    val zoneId: String, val assetVersion: Int, val assetUrl: String,
    val zhRef: Double,          // chart-datum elevation in IGN69 (Saint-Malo = -6.2890)
)
// In ZONES: zhRef = -6.2890   (bmveIgn69/pmveIgn69 no longer needed by the filter)

sealed interface LidarResult {
    data class Ready(val geoJson: String, val zhRef: Double) : LidarResult
    data object Unavailable : LidarResult
}
```

**`MapViewModel.kt`** — carry `zhRef` through `LidarUiState.Ready(geoJson, zhRef)`.

**`TideMapController.kt`** — replace the fraction→elevation mapping with absolute height:
```kotlin
private var lidarZhRef: Double = -6.2890

fun loadLidar(geoJson: String, zhRef: Double) {
    lidarZhRef = zhRef
    lidarActive = true
    pendingLidarGeoJson = geoJson
    style?.let { applyLidarLayers(it, geoJson) }
}

/** Level-3: set the waterline from the absolute tide height (metres above chart datum / LAT). */
fun setTideHeight(hZH: Double) {
    val s = style ?: return
    val targetElev = hZH + lidarZhRef            // IGN69 metres
    (s.getLayer(LIDAR_WATER_LAYER) as? FillLayer)?.setFilter(
        Expression.lt(Expression.get("z_min_m"), Expression.literal(targetElev)))
}
```
Keep `setTideFraction` for the Level-2 fallback only. Drop the `bmve/pmve` fields and the
`lidarBmve + f*(…)` math.

**`MapScreen.kt`** — for the LiDAR path, drive the controller from `height`, not `fraction`:
```kotlin
LaunchedEffect(lidar) {
    if (lidar is LidarUiState.Ready) controller.loadLidar(lidar.geoJson, lidar.zhRef)
}
LaunchedEffect(height, lidar) {
    if (lidar is LidarUiState.Ready) height?.let { controller.setTideHeight(it) }
    else controller.setTideFraction(fraction ?: 0f)   // Level-2 fallback unchanged
}
```

This is the single highest-impact fix and needs no asset regen — verify it on-device before
touching the pipeline.

---

### Change 2 — Geometry validity + RFC 7946 winding  (`finalize_4m2_2m.py`)

Measured: 29/43 v1.0 features invalid (self-intersection); 100 % of exterior rings wound CW.
MapLibre's earcut needs CCW exteriors (RFC 7946) → CW/invalid rings give fan/shard artifacts.

**Add import** at top:
```python
from shapely.geometry.polygon import orient as _shapely_orient
```
**Add helper** after imports:
```python
def ensure_rfc7946_winding(geom):
    """Exterior CCW (sign=1.0), holes CW. Run AFTER buffer(0)."""
    if geom.geom_type == 'Polygon':
        return _shapely_orient(geom, sign=1.0)
    if geom.geom_type == 'MultiPolygon':
        return MultiPolygon([_shapely_orient(p, sign=1.0) for p in geom.geoms])
    return geom
```
**Replace** the simplify/empty/reproject block in the loop:
```python
# OLD:
simp = filtered.simplify(DP_TOL_M, preserve_topology=True)
if simp.is_empty:
    dropped_bands += 1
    continue
out = reproject(simp, to_wgs)

# NEW:
simp = filtered.simplify(DP_TOL_M, preserve_topology=True)
simp = simp.buffer(0)                            # heal self-intersections
if simp.is_empty or not simp.is_valid:
    dropped_bands += 1
    continue
simp = ensure_rfc7946_winding(simp)              # exterior CCW
out = reproject(simp, to_wgs)
```

---

### Change 3 — Permanent-land cap band  (`pipeline_stage1b.py` + render)

Terrain above PMVE (+5.911 m IGN69) has no tide band → renders as basemap. Add one capping
feature so in-coverage rock/walls are permanent land. (No land mask needed: in this DEM nothing
reads above +5.911 m except real terrain — the sea is nodata, not a high elevation.)

**Move** `from shapely.geometry import shape as shp_shape` to the top of the file (currently inside
the loop). **Add** after the band loop, before the GeoJSON write:
```python
# ─── Cap band: permanent land above PMVE ─────────────────────────────────────
cap_mask = valid_clip & (clip > Z_MAX)
n_cap_px = int(cap_mask.sum())
if n_cap_px > 0:
    cap_iter = rasterio.features.shapes(
        cap_mask.astype(np.uint8), mask=cap_mask.astype(np.uint8), transform=clip_tf)
    cap_polys = [shp_shape(gd) for gd, v in cap_iter if v == 1]
    if cap_polys:
        features.append({
            "type": "Feature",
            "geometry": mapping(reproject(unary_union(cap_polys))),
            "properties": {
                "z_min_m": round(Z_MAX, 4), "z_max_m": None,
                "band_idx": n_bands, "pixel_count": n_cap_px,
                "datum": "IGN69", "band_type": "land",
            },
        })
        print(f"    Cap band (>PMVE): {n_cap_px:,} px  {len(cap_polys)} sub-polys")
```
The cap flows through `finalize_4m2_2m.py` (4 m² filter, DP, buffer(0), winding) unchanged.

**Render** — add to `TideMapController`. Companion: `const val LIDAR_LAND_LAYER = "lidar-land"`.
Inside the existing `if (style.getLayer(LIDAR_WATER_LAYER) == null)` block, **after** the
`lidar-water` `addLayerBelow`/`addLayer`:
```kotlin
val landFill = FillLayer(LIDAR_LAND_LAYER, LIDAR_SOURCE).withProperties(
    PropertyFactory.fillColor(palette.mapLand.toArgb()),
    PropertyFactory.fillOpacity(1.0f),
    PropertyFactory.fillAntialias(true),
)
landFill.setFilter(Expression.eq(Expression.get("band_type"), Expression.literal("land")))
if (firstSymbolId != null) style.addLayerBelow(landFill, firstSymbolId) else style.addLayer(landFill)
```
Same `firstSymbolId` → land inserts above water (correct occlusion). In `setPalette()`, reskin
`LIDAR_LAND_LAYER` with `palette.mapLand` like the water layer.

---

### Change 4 — Extend DEM tile coverage  (`pipeline_stage1b.py`) — *conditional*

Measured: tiles stop at northing 6850 (≈48.6447°N); intra-muros (48.6493°N), the Sillon and Grand
Bé are outside coverage, so they render as basemap (#1) and the clip's empty N/W strips show a
straight rectangular edge (#3). The §3 AOI (`north = 48.680`) needs tiles up to ≈6852.

**If the northern/western Litto3D tiles can be downloaded this pass** (geoservices.ign.fr; same
`MNT1m` ASC format): fetch `0329…0334_6851`, `0329…0334_6852`, and the `0329_6846…6850` column;
unpack into `data/litto3d-raw/`; add their `XXXX_YYYY` keys to the tile set in `pipeline_stage1b.py`
(and relax the `assert len(...) == 17`); re-run. Coverage will then exceed the ±2 km camera box on
all sides, resolving #1 and #3.

**If the tiles cannot be obtained**, skip this change: ship Changes 1–3 + 5 and leave the §7
coverage-gap limit in place. The southern/eastern waterline is already correct and is markedly
improved by Changes 1–3 alone. Do **not** block the release on this.

---

### Change 5 — Filename, metadata, version  (`finalize_4m2_2m.py` + `LidarRepository.kt`)

```python
# finalize_4m2_2m.py:
OUT_FILE = r"D:\Perso\TideApp\data\scratch\output\lidar-saint-malo-v1.1.geojson"
# metadata dict:
"pipeline_stage": "1C-v1.1",
"generated":      "2026-06-17",
# band_count is computed dynamically — already correct
```
In `LidarRepository.kt` bump `assetVersion = 2` and point `assetUrl` at the v1.1 download (below).
The version bump forces a re-download on the next map open (existing cache logic).

---

### App code (Changes 1, 3, 5)

All app edits are specified inline above: the render datum fix (Change 1: `LidarRepository`,
`MapViewModel`, `TideMapController.setTideHeight`, `MapScreen`), the `lidar-land` layer (Change 3),
and the `assetVersion`/`assetUrl` bump (Change 5). Then build:
```
.\gradlew assembleDebug
```
Must succeed with no new warnings. Only `LidarRepository.kt`, `MapViewModel.kt`,
`TideMapController.kt`, `MapScreen.kt` change; the widget and Level-2 path are untouched.

---

### Regenerate the asset (Changes 2–5)

```
python data\scratch\pipeline_stage1b.py
python data\scratch\finalize_4m2_2m.py
```

Verify before publishing:
- Feature count: **44** (43 tide + 1 cap) if tiles were *not* extended; more bands/larger extent if
  Change 4 was applied.
- Cap feature present: `band_type == "land"`, `z_min_m == 5.911`.
- **Validity self-check** (must pass): `all(shape(f['geometry']).is_valid for f in features)` and
  zero clockwise exterior rings (signed area > 0 for every exterior). This is the #2 gate.
- File size: ~7–10 MB uncompressed (more if Change 4 widened coverage).

---

### Publish to GitHub Releases

```
gh release create lidar-saint-malo-v1.1 ^
  "data\scratch\output\lidar-saint-malo-v1.1.geojson" ^
  --repo exilonn/lidar-saint-malo ^
  --title "Litto3D waterline geometry — Saint-Malo v1.1" ^
  --notes "Fixes: RFC 7946 winding/validity (triangle shards), permanent-land cap above PMVE (+5.911 m), and render now uses absolute IGN69 height (no more over-flood). Coverage extended north/west IF tiles obtained. Known limits: any uncovered area renders as basemap; dike-protected low ground still bathtub-floods at genuine spring highs (hydraulic connectivity → v1.2)."
```

Confirm the download URL resolves:
```
curl -sI https://github.com/exilonn/lidar-saint-malo/releases/download/lidar-saint-malo-v1.1/lidar-saint-malo-v1.1.geojson | head -3
```

---

### On-device acceptance

Open the Saint-Malo map and sweep the slider 0 → max:
- **No triangle/fan shards** anywhere (#2).
- The railway-station / Découverte area stays dry until the *readout height* genuinely exceeds its
  ground (≈9.4 m ZH), not at every high slider position (#4 — datum fix).
- In-coverage rock/headlands above PMVE stay land-coloured at full high (#1 — cap band).
- If Change 4 applied: intra-muros and the Sillon now have a real waterline and the rectangular
  edge is gone (#1/#3). If not: those northern areas render as basemap — expected, documented.
- A non-Litto3D station still uses the Level-2 crossfade (regression check).

---

### Known limits remaining after v1.1

- **Coverage gap** (if Change 4 deferred): intra-muros / Sillon / Grand Bé render as basemap, no
  waterline. Resolve by obtaining the northern/western tiles (Change 4) in a later pass.
- **Bathtub flooding (residual)**: after the datum fix this only appears at genuine spring highs,
  for dike/embankment-protected low ground (no hydraulic connectivity). `map_disclaimer` covers it.
  v1.2 adds `scipy.ndimage` sea-seed flood-fill per band.

**— END PROMPT —**

---

## §11 v1.2 field findings + v1.3 plan — connectivity re-scoped (2026-06-18)

After v1.2 widened the band range, the debug slider at **H_LAT = 14.0 m** floods La Découverte, the
railway-station district, La Guymauvière and a coastal strip down to Le Rosais. Initial hypothesis:
the "no hydraulic connectivity" bathtub limit (previously scoped only for the port basin) is now
visible over a much larger area because the widened range reaches the elevations these areas sit at.
**Direct measurement overturned this hypothesis** — same discipline as §9.0.

### §11.1 Measurements (the surprises)

Scripts: `data/scratch/analyze_connectivity_v13.py`, `inspect_rance_basins_v13.py`, against
`mosaic_lambert93.tif` + `lidar-saint-malo-v1.2.geojson`. ZH_Ref = −6.2890; H_LAT→IGN69 = H_LAT + ZH_Ref.

**DEM elevation at the named places** (±20 m window median, IGN69):

| place | median | floods @ 7.71 (H_LAT 14) | dry @ 1.911 (H_LAT 8.2) |
|---|---|---|---|
| La Découverte (quartier) | +3.08 | yes | **yes (dry)** |
| Gare district | +4.36 | yes | **yes (dry)** |
| La Guymauvière | +7.31 | yes | **yes (dry)** |
| Le Rosais | +3.18 | yes | **yes (dry)** |

**Sea-seeded connectivity flood-fill** (8-connectivity; sea propagates through wet cells *and*
nodata, since deep channels/open water have no LiDAR ground return → nodata):

| water level | bathtub-wet | sea-connected | DISCONNECTED (bug candidate) |
|---|---|---|---|
| +7.71 m IGN69 (H_LAT 14.0) | 3,053,027 m² | **98.8 %** | 1.2 % (36,805 m²) |
| +1.911 m IGN69 (H_LAT 8.2) | 1,234,824 m² | 97.4 % | 2.6 % (32,676 m²) |

Three findings overturn the hypothesis:

1. **H_LAT 14.0 is above PHMA.** PHMA (highest astronomical tide) = +7.301 m IGN69 = H_LAT 13.59 m.
   The debug slider's 14.0 m puts the water **0.41 m above the highest tide that can ever occur**.
   The flood is a debug-only excursion past reality; the **production slider is driven by real
   predictions and never reaches it** → no production-visible bug here.

2. **At 7.71 m, 98.8 % of the flooded area is genuinely sea-CONNECTED.** At that (non-physical) level
   the connected sea legitimately overtops Saint-Malo's lower quays/embankments and reaches the low
   urban ground — a connectivity flood-fill would **not** dry it. Only 1.2 % (36,805 m²) is truly
   disconnected, in small scattered pockets. **Connectivity would barely change the H_LAT=14 view.**

3. **At realistic high tide (1.911 m), the disconnected area is almost entirely the LOCKED HARBOUR
   BASINS** — a 26,223 m² component at (48.643, −2.000), the Bassin Vauban / Duguay-Trouin / Bouvet
   wet-dock complex (water +0.69…+1.91 m). Lock gates keep these full; showing them as water is
   **correct**. A naive sea-seeded flood-fill would **empty them — a regression**. There is **no
   significant wrongly-flooded dry land at realistic tides.**

**Locked-basin geography** (nodata = deep water, no LiDAR return → already renders as basemap water):

| basin | sample | result |
|---|---|---|
| Bas-Sablons marina | (48.6363, −2.0258) | **100 % nodata** → basemap water (permanently full) |
| Bassin Vauban | (48.6455, −2.0145) | **100 % nodata** → basemap water |
| Bassin Duguay-Trouin | (48.6470, −2.0120) | **100 % nodata** → basemap water |
| East harbour shallows | (48.6430, −2.0005) | 0 % nodata, +0.69…+1.45 m → measured cells a flood-fill would wrongly dry |

→ The deep locked basins are **already** permanently water via nodata→basemap; the lidar-water
connectivity logic only touches *valid measured* bands. So "locked basins must stay full" is mostly
about **not drying the measured wet-dock fringes**, not about Bas-Sablons (which is nodata).

*Method caveat:* propagating the sea through nodata is the correct "where can water physically reach"
model (it lets the estuary reach legitimately-connected basins); not propagating would wrongly
disconnect the channel and over-count. The two qualitative findings above (14 > PHMA; realistic-tide
disconnection = locked basins) are independent of the exact connected-% and hold regardless.

### §11.2 Item 4 — Rance/Troctin "rectangle": measured, not eyeballed → NATURAL CLIP EDGE

548 v1.2 rings have ≥2 vertices on the west DEM data boundary (lon ≈ −2.0247). The **longest** straight
boundary run spans only ~233 m (band 5, z_min −6.029); on-boundary vertices form a few consecutive
runs along the constant-longitude clip line, with natural stair-stepped jaggedness elsewhere. The
asset is **0/63 invalid, all exteriors CCW**. → The "rectangle" is the deep Rance channel bands
**truncated by the rectangular DEM-clip boundary** (a straight edge where the channel meets the data
extent), **not** a winding/validity artifact. Same root cause as defects #1/#3 (§9.4/§9.5); the only
fix is to extend tiles or apply a sea/coastline mask. **Cosmetic, low priority.**

### §11.3 Item 3 — permanent water floor

DEM minimum = −16.93 m IGN69; lowest v1.2 band floor = −7.279 m. 505,981 valid cells (5.45 %) lie
below the floor — the deep Rance/main channel (centroid 48.625, −2.021). At the **lowest realistic
tide** (PBMA = −6.279 m IGN69), the four deepest bands (z_min −7.279…−6.529) already stay wet, and the
sub-floor channel has no polygon → renders as basemap water. So permanent water is **effectively
already provided** by the 1.0 m buffer below PBMA plus basemap-water. The only residual risk is a
**colour seam** at the −7.279 m inner edge if the basemap water colour differs from `palette.mapWater`
over the channel.

**Decision D-L16:** add an always-on permanent-water floor — symmetric to the §9.4 land cap — as cheap
insurance against the seam, **only if a seam is actually observed at lowest tide.** Preferred form:
one asset feature `band_type="water"` = union of valid cells `< Z_MIN`, rendered always-on water
(mirrors the `band_type="land"` cap). A render-time rule is harder (needs a coverage polygon to bound
the fill), so the asset feature is the chosen form if implemented.

### §11.4 Decision — v1.3 scope (measurement-driven)

**Hydraulic connectivity is NOT made the v1.3 headline.** Measured ROI is ~1.2 % of wet area at a
non-physical level and ~nil at realistic tides, while the dominant disconnected feature at realistic
tides is a locked basin we must keep wet (naive flood-fill = regression). v1.3 is deliberately small:

| # | Item | Decision | Risk | Impact |
|---|---|---|---|---|
| 1 | **Debug-slider PHMA flag** | **DO** — flag `> PHMA (non-physical)` in the debug readout; raise `DEBUG_H_LAT_MAX_M` to 14.6 so the top buffer band (z_max +8.301 = H_LAT 14.59) is reachable. App-only, debug-only. | none | stops mis-reading the debug flood as a bug (the trigger for this whole pass) |
| 2 | **Permanent water floor (D-L16)** | **DO IF a seam is seen** — one asset feature, symmetric to the land cap | low | removes a possible deep-channel colour seam |
| 3 | **Hydraulic connectivity** | **DEFER** — safe spec retained (§12 Change C, optional); gate on a field-confirmed objectionable inland puddle; MUST include a locked-basin keep-wet allowlist | high if naive | ~nil at realistic tides |
| 4 | **Rance/Troctin straight edge** | **DEFER** to coverage work (extend tiles / sea-mask) | low | cosmetic |

**Production is unaffected today** (real tides ≤ PHMA; no wrongly-flooded dry land at realistic tides).
The smallest honest v1.3 is **Change A alone**; Changes B and C are gated on field observation.

---

## §12 Consolidated v1.3 implementation prompt (single Sonnet pass)

Self-contained; ordered by impact. **Change A** is the only unconditional change.

**— BEGIN PROMPT —**

You are implementing v1.3 of the LiDAR waterline for the Tides Android app (Saint-Malo). v1.2 widened
the vectorized band range to [−7.279, +8.301] m IGN69 (63 bands; PBMA−1.0 … PHMA+1.0). Field follow-up
(see `docs/lidar-plan.md` §11) **measured** that the H_LAT=14 flood is a debug-only excursion above
PHMA (+7.301 m IGN69), not a connectivity bug: at realistic tides there is no wrongly-flooded dry land,
and the only "disconnected" flooding is the locked harbour basins, which must stay water. Do the
changes below in order. Do not start a new zone or touch unrelated code.

Repo paths:
```
D:\Perso\TideApp\
  data\scratch\pipeline_stage1b.py      ← Stage 1B vectorize (Z_MIN/Z_MAX, band loop)
  data\scratch\finalize_4m2_2m.py       ← Stage 1C finalize (metadata, OUT_FILE)
  data\scratch\output\                  ← mosaic_lambert93.tif (reuse), lidar-saint-malo-v1.2.geojson
  app\src\main\java\com\exilon\tides\
    map\MapScreen.kt                     ← debug slider lives here (DEBUG_H_LAT_MAX_M, DebugTideSlider)
    map\TideMapController.kt             ← Level-3 layers/filter
    data\LidarRepository.kt             ← assetVersion / URL / pipelineStage
```
ZH_Ref = −6.2890. PHMA = +7.301 m IGN69 (H_LAT 13.59). Asset feature props: `z_min_m, z_max_m,
band_idx, pixel_count, datum, band_type` (`null`|`"land"`). Filter: `["<", ["get","z_min_m"], targetElev]`.

### Change A — Debug-slider PHMA flag  (app, debug-only; DO FIRST, no asset)

In `MapScreen.kt`:
1. Raise the constant: `private const val DEBUG_H_LAT_MAX_M = 14.6f` (top band z_max +8.301 = H_LAT 14.59 m).
2. Add `private const val PHMA_H_LAT_M = 13.59f` (highest astronomical tide for Saint-Malo).
3. In `DebugTideSlider`, when `debugHeight != null && debugHeight!! > PHMA_H_LAT_M`, append
   ` ">PHMA · non-physical"` (error colour) to the readout line so an above-PHMA test is never mistaken
   for a real flood. Pure UI; release builds unaffected (already inside `if (BuildConfig.DEBUG)`).

Build `.\gradlew assembleDebug` — must succeed. This alone may be the whole v1.3.

### Change B — Permanent water floor  (pipeline + render; ONLY if a deep-channel colour seam is seen at lowest tide)

Mirror the §9.4 land cap. In `pipeline_stage1b.py`, after the band loop (and any cap band), before the
GeoJSON write:
```python
# Permanent water floor: valid terrain below the lowest band (always submerged).
floor_mask = valid_clip & (clip < Z_MIN)
n_floor_px = int(floor_mask.sum())
if n_floor_px > 0:
    floor_iter = rasterio.features.shapes(
        floor_mask.astype(np.uint8), mask=floor_mask.astype(np.uint8), transform=clip_tf)
    floor_polys = [shp_shape(gd) for gd, v in floor_iter if v == 1]
    if floor_polys:
        features.append({
            "type": "Feature",
            "geometry": mapping(reproject(unary_union(floor_polys))),
            "properties": {"z_min_m": None, "z_max_m": round(Z_MIN, 4),
                           "band_idx": -1, "pixel_count": n_floor_px,
                           "datum": "IGN69", "band_type": "water"},
        })
        print(f"    Water floor (<Z_MIN): {n_floor_px:,} px  {len(floor_polys)} sub-polys")
```
It flows through `finalize_4m2_2m.py` unchanged (4 m² filter, DP, make_valid, winding). In
`TideMapController`, the existing `lidar-water` filter must also show `band_type=="water"` regardless of
`targetElev`:
```kotlin
fun setTideHeight(hZH: Double) {
    val s = style ?: return
    val targetElev = hZH + lidarZhRef
    (s.getLayer(LIDAR_WATER_LAYER) as? FillLayer)?.setFilter(
        Expression.any(
            Expression.lt(Expression.get("z_min_m"), Expression.literal(targetElev)),
            Expression.eq(Expression.get("band_type"), Expression.literal("water")),
        ))
}
```

### Change C — Hydraulic connectivity  (pipeline; OPTIONAL — implement only if field testing finds an objectionable disconnected inland puddle at a realistic tide)

⚠ Measured ROI is ~nil at realistic tides and a naive version **empties the locked harbour basins**.
Do NOT ship without the keep-wet allowlist below.

In `pipeline_stage1b.py`, replace each band's `band_mask` with a sea-connected version:
```python
from scipy import ndimage
# Build once, before the band loop:
#   sea_passable(W) = (valid & clip<W) | ~valid   # wet measured OR nodata (deep water/open sea)
# Seed from clip boundary (open sea enters at the W/N edges). Keep components touching the seed.
# A band cell is kept only if it lies in a sea-connected component at its own band's upper edge.
LOCKED_BASINS_WGS = [   # keep-wet allowlist: locked wet docks (always full). Lon/lat polygons.
    # Bassin Vauban/Duguay-Trouin/Bouvet complex ~ (48.643..48.647, -2.000..-2.015),
    # Bas-Sablons marina ~ (48.635..48.637, -2.024..-2.027). Fill exact rings before enabling.
]
# For each band i with upper edge z_hi: keep band_mask cells that are EITHER
#   (a) in a sea-connected component of sea_passable(z_hi), OR
#   (b) inside a LOCKED_BASINS_WGS polygon (rasterised to the clip grid).
```
Seed/label with `ndimage.label(passable, structure=np.ones((3,3)))`, keep labels intersecting the
boundary rows/cols. Bump to v1.3 either way (Change B and/or C → asset regen). Verify `0` invalid
features and that the locked-basin cells remain present.

### Version bump + publish  (only if Change B or C regenerated the asset)

```python
# finalize_4m2_2m.py
OUT_FILE = r"D:\Perso\TideApp\data\scratch\output\lidar-saint-malo-v1.3.geojson"
"pipeline_stage": "1C-v1.3",
"generated":      "2026-06-18",
```
```
python data\scratch\pipeline_stage1b.py
python data\scratch\finalize_4m2_2m.py
gh release create lidar-saint-malo-v1.3 ^
  "data\scratch\output\lidar-saint-malo-v1.3.geojson" ^
  "data\scratch\output\lidar-saint-malo-v1.3.geojson.gz" ^
  --repo exilonn/lidar-saint-malo --title "Litto3D waterline — Saint-Malo v1.3" ^
  --notes "Permanent water floor and/or sea-connectivity (locked basins preserved)."
```
In `LidarRepository.kt`: `assetVersion = 4`, URL → v1.3, `pipelineStage = "1C-v1.3"`. If only Change A
shipped, **leave the asset at v1.2** (no Room/URL change).

### On-device acceptance
- Debug readout shows `>PHMA · non-physical` above H_LAT 13.59 (Change A).
- If Change B: at the lowest predicted tide the deep channel reads as solid water (no seam).
- If Change C: the locked harbour basins and Bas-Sablons marina stay water at every slider position;
  no realistic-tide inland puddle remains; a non-LiDAR station still uses Level-2.

**— END PROMPT —**

---

## §13 Verification prompt — item 2 (no regression in the normal operating range)

**— BEGIN PROMPT —**

Confirm v1.2's widened band range did NOT introduce flooding of low urban ground in the **normal**
tide range (isolate the extreme-tide connectivity question from everyday behaviour). Inspect only;
change nothing.

Run `data/scratch/analyze_connectivity_v13.py` (already parameterised for H_LAT 8.2 m → +1.911 m
IGN69). Assert, from its output:
1. **Named places dry at H_LAT 8.2.** La Découverte (+3.08), Gare district (+4.36), La Guymauvière
   (+7.31), Le Rosais (+3.18) all have median ground **> +1.911 m IGN69** → `dry @1.911 = yes`.
2. **No new disconnected dry land.** At +1.911 m the disconnected-wet area is dominated by the locked
   harbour-basin component (~26 k m² at 48.643,−2.000, elev +0.69…+1.91 m) — expected water, not a
   regression. Confirm there is **no large disconnected component over measured dry ground (median
   elevation > +1.911 m)**.
3. **Range unchanged in the operating band (within tolerance).** v1.2 only *added* bands below
   H_LAT 1.5 m and above H_LAT 12.2 m. NB the band edges re-anchor: `np.arange(Z_MIN,…)` starts at the
   new Z_MIN (−7.279), so overlap-range edges are offset ~0.01 m from v1.1's (−7.279 + 0.25k vs
   −4.789 + 0.25k; 2.49 m ≠ integer × 0.25). That 1 cm shift is far below Litto3D vertical accuracy
   (±0.15 m) and sub-pixel on-screen → functionally identical, **not** byte-identical. Spot-check that
   bands covering z ≈ [−4.789, +5.911] still exist and cover the same extent as v1.1.

PASS = all three hold. Report the H_LAT 8.2 table row for each named place and the largest
disconnected component's centroid + median elevation.

**— END PROMPT —**

---

---

## §14 Ground-truth investigation — "zero on-device visual change" (2026-06-18)

Three asset regenerations (v1.1/v1.2/v1.3) reported success but produced apparently zero on-device
visual change. Ordered investigation: (A) which asset is actually rendered? (B) what datum does
production code use? No code changes during this pass — evidence only.

### §14.1 Finding A — Asset loaded on device (hypothesis: "v1.0 served forever" = FALSE)

**Method:** `adb shell run-as com.exilon.tides` — list `files/lidar/`, then read first 1000 bytes
of the v4 file.

**Evidence:**

```
files/lidar/
  stmalo_v1.geojson    8,741,584 bytes   1970-01-21 15:55  (epoch-zero ts = first dev download)
  stmalo_v4.geojson   14,784,065 bytes   2026-06-18 19:52  ← v1.3, downloaded today
```

First 1000 bytes of `stmalo_v4.geojson` (directly from device):
```json
{"type":"FeatureCollection","metadata":{..."pipeline_stage":"1C-v1.3","band_count":64,...}}
```

**Conclusion: the v1.3 asset IS on-device.** `stmalo_v1.geojson` is an orphaned file — with
`assetVersion=4` in code, `lidarFile(info)` = `stmalo_v4.geojson`; the old file is never
referenced. The cache validation path (Room entity version + file exists + pipeline_stage match)
would serve `stmalo_v4.geojson` as a cache hit when Room entity has `assetVersion=4`.

**Corollary on triangles:** v1.3 geometry has CCW winding and 0 invalid features (Python-verified).
If triangle artifacts persist while v1.3 is confirmed on-device, the cause is **not** polygon
winding. Most likely scenario: the user's "zero change" observation predated the v1.3 download
(file timestamp 19:52 today). Once the user reconnects the device, pull logcat on fresh app launch
to see the LidarRepo cache-hit log line and confirm which asset was active during the observation.

**Still uncertain:** Room entity `assetVersion` — device disconnected before database pull. A
fresh-launch logcat (`adb logcat -s LidarRepo:V`) will show
`ensureZone: entity.ver=N expected=4 file=stmalo_v4.geojson exists=true` + the cache-hit or
download line.

### §14.2 Finding B — MLLW datum confirmed throughout production code (hypothesis: CONFIRMED)

**Method:** `grep -n DEFAULT_DATUM` + reading `TideApi.kt` and `TideRepository.kt`.

**Evidence (three independent source locations):**

| File | Line | Text |
|---|---|---|
| `TideApi.kt` | 39 | `@Query("datum") datum: String = "MLLW"` — Retrofit interface default |
| `TideRepository.kt` | 563 | `const val DEFAULT_DATUM = "MLLW"` — named constant |
| `TideRepository.kt` | 383 | `api.tides(stationId, days=30, datum=DEFAULT_DATUM)` — production fetch |

**Every production API call sends `datum=MLLW`.** Stage 0 (2026-06-17) verified `datum=LAT` was
correct using a throwaway harness — but that fix was never ported to the production
`TideRepository`. The constant `DEFAULT_DATUM` was always `"MLLW"`.

*Note:* Open question #1 ("RESOLVED 2026-06-17, datum=LAT confirmed") referred to the Stage 0
harness only, not the production code. The resolution was incorrect. Reopened → resolved here.

### §14.3 Contamination path and waterline error magnitude

```
api.tides(datum="MLLW")
  → TideExtremeEntity.heightMeters in Room  (MLLW-referenced, stored as "heightMeters")
  → TideMath.heightAt(extremes, t)          (returns H_MLLW, no datum awareness)
  → effectiveHeight in MapContent            (H_MLLW)
  → TideMapController.setTideHeight(H_MLLW)
  → targetElev = H_MLLW + ZH_Ref            ← WRONG (ZH_Ref is the LAT/ZH elevation; formula
                                               requires H_LAT, not H_MLLW)
```

At Saint-Malo: MLLW is **2.892 m above LAT** (Stage 0 measurement). Therefore:
`H_MLLW = H_LAT − 2.892`, and:

```
targetElev_actual  = H_MLLW + ZH_Ref = (H_LAT − 2.892) + (−6.289) = targetElev_correct − 2.892
```

**The waterline is set 2.892 m below correct IGN69 at all times.**

| Displayed H (MLLW) | True H_LAT | targetElev (actual) | targetElev (correct) | Error | Bands shown |
|---|---|---|---|---|---|
| −1.5 m (daily low) | +1.39 m | −7.789 m | −4.897 m | −2.892 m | **0** (all z_min > −7.789) |
| 0.0 m | +2.892 m | −6.289 m | −3.397 m | −2.892 m | 0 |
| +4.0 m | +6.89 m | −2.289 m | +0.601 m | −2.892 m | ~20 (correct ~30) |
| +10.5 m (spring high) | +13.39 m | +4.211 m | +7.099 m | −2.892 m | ~46 (correct ~59) |

**The waterline disappears completely** when displayed H_MLLW < −7.279 + 6.289 = **−0.990 m**.
Every typical Saint-Malo low tide reads H_MLLW ≈ −1.0 to −2.0 m → no bands visible at low tide.

**Interaction with the reported defects:**

- **"Sub-1.5m waterline" (no waterline at low tide):** not a pipeline range issue. The v1.2 range
  extension to z_min = −7.279 m is irrelevant: targetElev at displayed −1.5 m is −7.789 m, which
  is already below −7.279 m. The root cause is MLLW datum, not insufficient range.

- **Triangle artifacts at high tide:** with MLLW, at displayed H ≈ 10–11 m, targetElev ≈ +3.7..+4.7 m
  — above some band floors — so LiDAR bands ARE shown. With v1.0 geometry (CW), triangles appeared.
  With v1.3 (CCW), they should not. If the user's observation predated v1.3's download, they saw
  v1.0/v1.1/v1.2 with MLLW datum.

- **Rance "square":** DEM clip boundary artifact; unaffected by datum. Always expected.

### §14.4 Fix scope — datum-only, no asset changes needed

The v1.3 LiDAR geometry is correct: CCW winding, 0 invalid features, band range correct, permanent
floor correct. **No pipeline re-run or asset regen required.** Three changes:

1. **`TideRepository.kt:563`** — `const val DEFAULT_DATUM = "MLLW"` → `"LAT"`
2. **`TideApi.kt:39`** — interface default `datum: String = "MLLW"` → `"LAT"` (makes default
   consistent; the production call is driven by the constant, but the default should match)
3. **Room migration 10 → 11** — purge MLLW-contaminated tide extremes and reset `fetchedAtMillis`
   so the app re-fetches with `datum=LAT` on next open. `daily_condition` rows are datum-independent
   (sunrise/sunset/moon/spring-neap) and can stay.

**Implementation prompt in §15.**

---

## §15 Sonnet prompt — datum fix (no asset changes)

**— BEGIN PROMPT —**

Fix the production datum bug identified in §14: the TideCheck API has been called with
`datum=MLLW` since the beginning; all `TideExtremeEntity.heightMeters` in Room are MLLW-referenced
instead of LAT-referenced, causing the IGN69 waterline to be set **2.892 m below correct** at all
times. Fix involves two constant changes + one Room migration. Do NOT regenerate the LiDAR asset.

### Change 1 — Constant in TideRepository.kt

In `app/src/main/java/com/exilon/tides/data/TideRepository.kt`, line ~563 in companion object:
```kotlin
// Before:
const val DEFAULT_DATUM = "MLLW"
// After:
const val DEFAULT_DATUM = "LAT"
```

No other call-site changes needed: all three usages (lines 219, 292, 383) already pass
`DEFAULT_DATUM` — they will automatically use `"LAT"`.

### Change 2 — Interface default in TideApi.kt

In `app/src/main/java/com/exilon/tides/data/remote/TideApi.kt`, line ~39:
```kotlin
// Before:
@Query("datum") datum: String = "MLLW",
// After:
@Query("datum") datum: String = "LAT",
```

Belt-and-suspenders: production uses the constant, but the interface default should match.

### Change 3 — Room migration 10 → 11

In `app/src/main/java/com/exilon/tides/data/local/TideDatabase.kt`:

1. Bump `version = 10` → `version = 11`.
2. Add migration:
```kotlin
/** Purge MLLW-contaminated tide extremes so the next open fetches LAT datum. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM tide_extreme")
        db.execSQL("UPDATE saved_station SET fetchedAtMillis = 0")
    }
}
```
3. Add `MIGRATION_10_11` to the `databaseBuilder` in `ServiceLocator.kt` (the `.addMigrations(…)`
   call that already lists the earlier migrations).

**Do NOT use `fallbackToDestructiveMigration`** — the user's saved station list, weather data,
and LiDAR entity must survive. Only `tide_extreme` is contaminated; `daily_condition`,
`weather_daily`, `weather_hourly`, `saved_station` (all except `fetchedAtMillis`), `lidar_zone`,
and `app_state` are all safe.

### Post-fix verification

1. Build and install. On first open Room auto-runs migration 10→11, clears extremes, resets
   `fetchedAtMillis`. The app calls `fetchAndStore` → `api.tides(datum="LAT")`. Confirm by:
   ```
   adb logcat | grep -E "okhttp|TideRepo|LidarRepo"
   ```
   The OkHttp log should show a URL containing `datum=LAT` (not `datum=MLLW`).
2. At the next low-tide moment (or use the debug slider at H_LAT ≈ 1.5 m):
   `targetElev = 1.5 + (−6.289) = −4.789 m IGN69`. Bands with z_min < −4.789 should render —
   the ~10 deepest intertidal bands should be visible as water.
3. At the next high-tide moment (H_LAT ≈ 12.5 m):
   `targetElev = 12.5 + (−6.289) = +6.211 m IGN69`. ~55 of 63 bands should render.
4. Triangle artifacts: if v1.3 was not on-device during the "zero change" observation, this is
   the first clean test. After the fix, triangles should be absent (v1.3 CCW geometry).
5. `<PBMA — niveau improbable` production flag: with `datum=LAT`, the FES2022 model may still
   occasionally predict H_LAT slightly below 0.01 m (PBMA). Flag should appear; no suppression.

### What this does NOT fix

- Rance/Troctin straight edge — DEM clip boundary artifact; needs tile extension.
- Coverage gap (intra-muros, Sillon) — tile acquisition needed.
- Connectivity/locked-basin rendering — deferred (Change C, gated on field observation).

**— END PROMPT —**

---

## Open questions

1. ~~**TideCheck datum.**~~ ~~RESOLVED 2026-06-17. `datum=LAT` confirmed.~~ **REOPENED then
   RESOLVED 2026-06-18.** Stage 0 "confirmation" was the throwaway harness, not production code.
   Production `TideRepository` always used `datum=MLLW`. Fix: §15.

2. ~~**ZH_Ref exact value.**~~ **RESOLVED 2026-06-17.** ZH_Ref = −6.2890 m NGF-IGN69 (SHOM RAM).

3. **Litto3D tile coverage — REOPENED (was "resolved").** Measurement shows the downloaded set
   stops at northing 6850 (≈48.6447°N): **intra-muros, the Sillon and Grand Bé are uncovered**, and
   the v1.0 bands span only ~62 % of the camera's height. **Blocking question for Change 4:** are
   tiles `…_6851` / `…_6852` (and the `0329` west column) available and scriptably downloadable
   from geoservices.ign.fr this pass? If yes, #1/#3 are fully fixed in v1.1; if no, they become a
   documented coverage limit and v1.1 ships Changes 1–3 + 5 only.

4. **Level-3 camera framing.** The camera centres on the tide gauge (south of intra-muros) with a
   fixed ±2 km box, so the iconic northern intertidal zone sits at the top edge even with full
   coverage. Should the Level-3 camera frame the **coverage/intertidal centroid** rather than the
   gauge? Affects what the user sees first; no datum impact. Decide before widening tiles.

5. **Station-to-zone mapping for future zones.** Compile-time map in `LidarRepository.kt` is
   sufficient for the pilot. The manifest architecture (§4 D-L7) is deferred until a second zone
   is added; at that point `ZONES` in the repository needs replacing with a manifest fetch.

6. **OSM substrate + LiDAR waterline coherence.** OSM polygon boundaries (mapped at roughly MHW)
   and LiDAR band boundaries (real geometry) will not align. Accepted for the pilot. If the
   mismatch is visually distracting at mid-tide, the OSM substrate layer can be narrowed or removed
   for Level-3 stations in a future pass. (Unaffected by the v1.1 datum fix.)

7. ~~**Hydraulic connectivity — REPLANNED, recommend DEFER.**~~ **DEFERRED (2026-06-18).** Measurement
   confirmed ~nil ROI at realistic tides and naive flood-fill empties locked basins. Change C spec
   retained in §12 gated on a field-confirmed inland puddle. No further action unless triggered.

8. ~~**Permanent water floor (v1.3 Change B).**~~ **SHIPPED (2026-06-18).** Implemented as permanent
   `band_idx=-1` feature; always-on water in `lidarWaterFilter`. Change B now closed.

9. **Locked-basin allowlist coordinates.** Only needed if Change C (connectivity) is ever enabled.
   Approximate bounding boxes in §12; precise OSM `harbour`/`dock` rings required before enabling C.

10. ~~**Debug-slider max above PHMA.**~~ **RESOLVED (2026-06-18).** Confirmed intentional at 14.6 m
    (to reach top buffer band); flagged `>PHMA — non physique` in the readout. Closed.
