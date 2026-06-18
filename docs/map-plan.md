# Interactive Tide Map — Technical Plan (Level 2)

Scope Level 2: MapLibre GL Native + OSM vector basemap, OSM intertidal polygons, tide-fraction
inundation via TideMath. **No real DEM, no datum conversion.**

## Locked decisions
- **D1** Basemap = **MapTiler** vector tiles (key in `local.properties` → `BuildConfig.MAPTILER_KEY`).
- **D2** Waterline = **per-polygon crossfade** (substrate↔water opacity by tide fraction `f`) for v1;
  JTS band-morph is a later upgrade.
- **D3** OSM features = **live Overpass API + aggressive local cache** (file + Room metadata).
- **D4** Map extent = **fixed ~4 km box** around the active station (cache key = stationId).
- **D5** Datum mismatch (OSM coastline ≈ MHW vs heights MLLW) = **accepted + disclaimed**; tide
  fraction is normalized against the *visible window's* range, not an absolute datum.
- **D6** Basemap theming = add our own themed overlay layers + defensively override a short known
  list of basemap layer ids (water/land), null-safe.
- Map is **app-only** — never created in the widget/worker path.
- abiFilters: **arm64-v8a only** (APKs shared directly; keep size down). x86/x86_64 emulators won't
  have the native lib.

## Stage split
- **Stage 1 (DONE)** — plan §9 steps 1–3: MapLibre dep + init, `map/` package (MapScreen,
  MapViewModel, MapUiState, MapLifecycle, TideMapController), themed MapTiler basemap centered on the
  active station, GPS pin (LocationComponent), "Indicative only" disclaimer, nav entry (map icon in
  the TideScreen top bar → `"map"` AnimatedContent state), and a 12 h time slider whose readout is
  driven by `TideMath.fractionAt` / `heightAt` / `isRising`. No OSM, no overlay, no scrub-network.
- **Stage 2 (TODO)** — plan §9 steps 4–7: Overpass fetch + GeoJSON cache (file + Room
  `EstranCacheEntity`), substrate classification, substrate/water FillLayers, wire tide `f` →
  crossfade via `TideMapController.setTideFraction(f)`, graceful degradation when no intertidal data,
  then optional JTS moving-waterline.

## Stage 2 reference (not yet built)
- **Deps**: `org.locationtech.jts:jts-core:1.20.0` (only for the morph upgrade). GeoJSON via
  MapLibre's bundled `org.maplibre.geojson`.
- **Overpass** (POST body, bbox = station ± ~4 km): query `natural=beach|shingle|mud|bare_rock|reef`,
  `wetland=tidal_flat`, `natural=water`, `natural=coastline` (ways + multipolygon relations),
  `out geom;`.
- **Substrate mapping**: beach+surface=sand→sand(yellow); beach+gravel/shingle/pebbles or
  natural=shingle→gravel(grey); bare_rock/rock/reef→rock(near-black); mud/tidal_flat→mud(brown);
  water→theme ocean.
- **Cache**: geometry → `filesDir/estran/<stationId>.geojson`; metadata → Room
  `EstranCacheEntity(stationId, bboxHash, fetchedAtMillis, schemaVersion, hasIntertidal)`. Long TTL
  (~60 days). Lazy on first map open; 429/timeout → serve stale + back off (mirror TideCheck guard).
- **Overlay**: `GeoJsonSource("estran")` + `FillLayer("estran-substrate")` (data-driven color by
  `substrate`, opacity `1-f`) over `FillLayer("estran-water")` (ocean color, opacity `f`) over a
  permanent sea polygon → low tide shows substrate, high tide shows water.
- **Slider → overlay**: slider time → `TideMath.fractionAt(extremes, t, windowStart, windowEnd)` →
  `TideMapController.setTideFraction(f)` updates only the two layer opacities (throttled). No network.
- **Degradation**: `hasIntertidal=false` or Overpass down → basemap + pin + slider (height/state
  readout) + a "no intertidal data" note; never a broken overlay.
