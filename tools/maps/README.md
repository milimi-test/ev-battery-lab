# Offline map pipeline

These are the project's map preparation scripts, copied into a portable source location. Existing assets are already packaged and do not need rebuilding for normal Android development. Preparing a new region can take substantial disk space and network time.

## Inputs and environment

Use Python 3.11+ in a virtual environment. Install `pip install -r tools/maps/requirements.txt`. Raster scripts also need `curl.exe` on Windows (or adapt the executable name for another OS). Dependencies are listed without pinning because this pipeline has not been rebuilt against a newly created environment in this release.

Store input datasets next to the scripts:

- OSM `.pbf` extracts from Geofabrik. `build_streets.py` and `build_chargers.py` scan all `.pbf` files in this directory. Avoid overlapping extracts that duplicate ways. `build_routing.py` explicitly lists `serbia.pbf` and `kosovo.pbf`; change that list for a new region.
- `countries.geojson`: Natural Earth country polygons with the `ADMIN` property. The current vector builder explicitly selects `Republic of Serbia` and `Kosovo` and distinguishes their boundary styles. Adapt that selection as well as `BBOX` for another region.
- GeoNames: `cities.zip` containing `cities500.txt`; `RS.zip` containing `RS.txt`; `XK.zip` containing `XK.txt`. Edit the archive/name list in `build_vectors.py` for other country extracts.
- `dem-list.txt`: available Copernicus GLO-90 tile identifiers. The included list covers the current dataset. The height and relief builders fetch official public COG tiles listed here. Update coverage for a new region.
- Satellite builder fetches ESA WorldCover Sentinel-2 RGB composites, not a live commercial imagery API. Its COG overview selection and output pyramid determine actual detail; enlarging a low-resolution tile does not add detail.

## Sequence

```text
python tools/maps/build_vectors.py serbia.pbf kosovo.pbf
python tools/maps/build_vectors.py
python tools/maps/build_streets.py
python tools/maps/build_routing.py
python tools/maps/compact_routing.py
python tools/maps/build_satellite.py
python tools/maps/build_route_heights.py
python tools/maps/build_relief.py
python tools/maps/build_contours_v019.py
```

`compact_routing.py` converts OSR1 into the smaller OSR2 representation. Run it once on a newly generated OSR1 file, not repeatedly on an already compacted graph. Satellite and height builders can skip existing tiles: to replace a region or resolution, use a separate checkout/output folder or deliberately remove only the specific stale generated assets. Keep a backup of the current maps.

The first vector command extracts road caches (paths relative to tools/maps); the second combines them with polygons and places. Include all required PBF extracts in the first command. The contour builder works offline from packaged `route-heights` arrays. Run `python tools/maps/build_contours_v019.py --help` for options and `python tools/maps/verify_contours_v019.py` from the project root, after building the APK, to validate its embedded assets. Update metadata, coverage, source dates and attribution in the app when replacing datasets.

## Reader contracts

All output goes into `app/src/main/assets` relative to the project, regardless of the shell working directory. Android readers are `OfflineAtlas.kt`, `OfflineStreets.kt`, `RoadRouter.kt`, `RouteTerrain.kt` and `OfflineContours.kt`. Verify file magic, compression, byte order and bounds before changing a format. `OfflineAtlas.WEST/EAST/SOUTH/NORTH`, `OfflineAtlasView` pan limits and `RouteTerrain` tile indexing currently assume 17–25 E and 40–48 N. Raster coverage, street coverage and routing coverage are independent. A charger outside the road graph cannot automatically become a routable destination.

## Charger edits

For small curated changes use `import_chargers.py`, which validates fields and preserves the existing catalogue. `build_chargers.py` regenerates an OSM-only catalogue and can overwrite curated entries; run it in a separate checkout and merge deliberately. Names and operator notes are source data, not UI translations. Preserve source links, checked dates and OSM/OCM/other attribution. Never infer `free=true` from missing prices.

This release verified the JSON importer and retained existing map assets. It did not redownload or rebuild the regional map pipeline.
