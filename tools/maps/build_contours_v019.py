"""Offline, reproducible contours from the packaged pixel-centred GLO-90-derived DEM.

No network access. Requires numpy, contourpy and shapely. The 512x512 integer-metre
grids have ~150-220 m spacing here; a 100 m contour interval is NOT spatial accuracy.
Masked cells are never interpolated, including the absent N41E018 tile. Neighbor
halos preserve interpolation at degree boundaries. Each contour is clipped before
simplification so shared boundary crossings remain fixed.
"""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct

import contourpy
import numpy as np
from shapely.geometry import LineString, box

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/assets/route-heights"
OUT = ROOT / "app/src/main/assets/contours"
SIDE = 512
NO_DATA = -32768
MAGIC = 0x45564331  # EVC1, big-endian, gzip-compressed
INTERVALS = (100, 500, 1000)


def decode(path):
    delta = np.frombuffer(gzip.decompress(path.read_bytes()), dtype=">i2")
    assert delta.size == SIDE * SIDE
    return np.cumsum(delta.reshape(SIDE, SIDE).astype(np.int32), axis=1).astype(np.int16)


def contours(x, y, values, level):
    # corner_mask=False rejects the WHOLE cell if any of its corners is missing.
    masked = np.ma.masked_equal(values, NO_DATA)
    generator = contourpy.contour_generator(x=x, y=y, z=masked, corner_mask=False,
                                            line_type="Separate", name="serial")
    return generator.lines(level)


def pieces(geometry):
    if geometry.is_empty:
        return []
    if geometry.geom_type == "LineString":
        return [geometry]
    if hasattr(geometry, "geoms"):
        return [p for g in geometry.geoms for p in pieces(g)]
    return []


def encode(lines, west, south, size):
    result = bytearray(struct.pack(">II", MAGIC, len(lines)))
    for height, line in lines:
        xy = np.asarray(line.coords)
        local = (xy - (west, south)) / size
        quantized = np.rint(np.clip(local, 0, 1) * 65535).astype(">u2")
        # Remove adjacent quantization duplicates without moving the endpoints.
        keep = np.r_[True, np.any(quantized[1:] != quantized[:-1], axis=1)]
        quantized = quantized[keep]
        assert 2 <= len(quantized) <= 65535
        result += struct.pack(">hH", height, len(quantized)) + quantized.tobytes()
    return gzip.compress(bytes(result), compresslevel=9, mtime=0)


def self_test():
    # A plane h=200x must cross at x=.25 for 50m, with linear edge interpolation.
    x = y = np.array([0., 1.])
    plane = np.array([[0., 200.], [0., 200.]])
    line = contours(x, y, plane, 50)[0]
    assert np.allclose(line[:, 0], .25)
    assert sorted(line[:, 1]) == [0., 1.]
    assert not contours(x, y, plane, 201)
    assert not contours(x, y, np.array([[0, 200], [NO_DATA, 200]]), 50)
    assert not contours(x, y, np.full((2, 2), NO_DATA), 100)
    # Saddle: independent valid branches, no invented central elevation.
    saddle = contours(x, y, np.array([[0, 200], [200, 0]]), 100)
    assert len(saddle) == 2 and all(len(s) == 2 for s in saddle)
    assert all(any(np.isclose(p, .5)) for branch in saddle for p in branch)
    # Clipped adjacent tiles retain the identical crossing at their shared edge.
    original = LineString([(0, .1), (.4, .7), (1, .9)])
    a = original.intersection(box(0, 0, .5, 1)).simplify(.01)
    b = original.intersection(box(.5, 0, 1, 1)).simplify(.01)
    assert np.allclose(a.coords[-1], b.coords[0])
    payload = gzip.decompress(encode([(100, LineString([(0., 0.), (1., 1.)]))], 0, 0, 1))
    assert struct.unpack(">IIhH4H", payload) == (MAGIC, 1, 100, 2, 0, 0, 65535, 65535)
    print("Contour interpolation, no-data, saddle, clipping and codec tests passed.", flush=True)


def build():
    self_test()
    grids = {(int(p.stem[1:3]), int(p.stem[4:7])): decode(p) for p in SOURCE.glob("*.hgtz")}
    counts = {i: {"tiles": 0, "lines": 0, "bytes": 0} for i in INTERVALS}
    for interval in INTERVALS:
        (OUT / str(interval)).mkdir(parents=True, exist_ok=True)
    for (lat, lon), grid in sorted(grids.items()):
        # A one-pixel halo samples pixel centres on BOTH sides of each tile edge.
        halo = np.full((SIDE + 2, SIDE + 2), NO_DATA, dtype=np.int16)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                neighbor = grids.get((lat + dy, lon + dx))
                if neighbor is None:
                    continue
                target_y = slice(0, 1) if dy == 1 else slice(-1, None) if dy == -1 else slice(1, -1)
                source_y = slice(-1, None) if dy == 1 else slice(0, 1) if dy == -1 else slice(None)
                target_x = slice(0, 1) if dx == -1 else slice(-1, None) if dx == 1 else slice(1, -1)
                source_x = slice(-1, None) if dx == -1 else slice(0, 1) if dx == 1 else slice(None)
                halo[target_y, target_x] = neighbor[source_y, source_x]
        xs = lon + (np.arange(SIDE + 2) - .5) / SIDE
        ys = lat + 1 - (np.arange(SIDE + 2) - .5) / SIDE
        generator = contourpy.contour_generator(x=xs, y=ys,
            z=np.ma.masked_equal(halo, NO_DATA), corner_mask=False, line_type="Separate", name="serial")
        valid = halo[halo != NO_DATA]
        levels = range(max(100, math.ceil(int(valid.min()) / 100) * 100), int(valid.max()) + 1, 100)
        raw = [(level, LineString(line)) for level in levels for line in generator.lines(level) if len(line) >= 2]
        for interval in INTERVALS:
            cells = 4 if interval == 100 else 1
            # Error bound in DEM pixels: detail~55m, overview~220/435m N-S.
            tolerance = {100: .25, 500: 1., 1000: 2.}[interval] / SIDE
            for yy in range(cells):
                for xx in range(cells):
                    west, south, size = lon + xx / cells, lat + yy / cells, 1 / cells
                    clip = box(west, south, west + size, south + size)
                    selected = []
                    for level, original in raw:
                        if level % interval or not original.intersects(clip):
                            continue
                        for line in pieces(original.intersection(clip)):
                            simple = line.simplify(tolerance, preserve_topology=False)
                            # Tiny closed loops below drawing precision are omitted, never replaced.
                            if simple.is_empty or simple.length < tolerance * 2 or len(simple.coords) < 2:
                                continue
                            selected.append((level, simple))
                    content = encode(selected, west, south, size)
                    path = OUT / str(interval) / f"{lon * cells + xx}_{lat * cells + yy}.ctz"
                    path.write_bytes(content)
                    counts[interval]["tiles"] += 1
                    counts[interval]["lines"] += len(selected)
                    counts[interval]["bytes"] += len(content)
        print(f"N{lat:02}E{lon:03} generated", flush=True)
    metadata = {
        "format": "EVC1 gzip: u32 magic, u32 count; i16 metres, u16 points, repeated u16 x/y; big-endian",
        "source": "Packaged route-heights/*.hgtz, derived by bilinear resampling from Copernicus GLO-90 DSM",
        "source_grid": "512x512 pixel-centred samples per degree; integer metres; 1/512 degree spacing",
        "no_data": "Cells touching missing samples are excluded; absent square N41E018 has no contours",
        "bounds": {"west": 17, "east": 25, "south": 40, "north": 48},
        "absent_tiles": ["N41E018"],
        "vertical_intervals_m": list(INTERVALS),
        "minimum_drawn_level_m": 100,
        "simplification_DEM_pixels": {"100": .25, "500": 1, "1000": 2},
        "warning": "DSM surface, not surveyed ground. Do not infer 100m spatial or vertical accuracy from contour interval.",
        "source_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(SOURCE.glob("*.hgtz"))},
        "counts": counts,
    }
    (OUT / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(counts, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--test", action="store_true")
    options = parser.parse_args()
    self_test() if options.test else build()
