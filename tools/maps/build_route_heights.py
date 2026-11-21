"""Build route terrain samples (metres) from the official Copernicus GLO-90 COGs."""
from concurrent.futures import ThreadPoolExecutor
import gzip
from pathlib import Path
import time

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.windows import from_bounds


SAMPLES = 512
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/route-heights"
TILES = set(Path(__file__).with_name("dem-list.txt").read_text().splitlines())


def build(pos):
    lat, lon = pos
    source = f"Copernicus_DSM_COG_30_N{lat:02}_00_E{lon:03}_00_DEM"
    destination = OUT / f"N{lat:02}E{lon:03}.hgtz"
    if destination.exists() or source not in TILES:
        return
    url = f"https://copernicus-dem-90m.s3.eu-central-1.amazonaws.com/{source}/{source}.tif"
    for attempt in range(4):
        try:
            with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_HTTP_TIMEOUT="35",
                              GDAL_HTTP_MAX_RETRY="1", GDAL_HTTP_RETRY_DELAY="1"):
                with rasterio.open("/vsicurl/" + url) as ds:
                    bounds = from_bounds(lon, lat, lon + 1, lat + 1, ds.transform)
                    a = ds.read(1, window=bounds, out_shape=(SAMPLES, SAMPLES),
                                masked=True, resampling=Resampling.bilinear)
            values = np.ma.filled(np.rint(a), -32768).astype(">i2")
            if values.shape != (SAMPLES, SAMPLES) or np.max(values) > 4000:
                raise ValueError(f"Unexpected elevation grid {source}")
            # Delta-coding per scanline compresses neighbouring integer elevations.
            values[:, 1:] = values[:, 1:] - values[:, :-1]
            tmp = destination.with_suffix(".partial")
            tmp.write_bytes(gzip.compress(values.tobytes(), compresslevel=6, mtime=0))
            tmp.replace(destination)
            print(destination.name, flush=True)
            return
        except Exception:
            if attempt == 3:
                raise
            time.sleep(1)


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    area = sorted(((y, x) for y in range(40, 48) for x in range(17, 25)),
                  key=lambda p: abs(p[0]-44) + abs(p[1]-20))
    with ThreadPoolExecutor(5) as pool:
        list(pool.map(build, area))
