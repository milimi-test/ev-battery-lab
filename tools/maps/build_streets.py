"""Offline street overlays from local Geofabrik OSM extracts, tiled at 0.25 degrees."""
from collections import defaultdict
from pathlib import Path
import gzip
import json
import math
import struct
import sys
import sqlite3

HERE = Path(__file__).resolve().parent
APP = HERE.parents[1] / 'app/src/main/assets'
sys.path.insert(0, str(HERE.parents[1] / '.tooling/map-data'))
import osmium

CLASSES = {'motorway': 0, 'trunk': 1, 'primary': 2, 'secondary': 3,
           'tertiary': 4, 'residential': 5, 'unclassified': 5,
           'living_street': 5, 'service': 6, 'pedestrian': 7}


def build():
    db = sqlite3.connect(HERE / 'street-build.sqlite')
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('CREATE TABLE IF NOT EXISTS done (source TEXT PRIMARY KEY, count INTEGER)')
    db.execute('CREATE TABLE IF NOT EXISTS ways (id INTEGER PRIMARY KEY)')
    db.execute('CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY, payload BLOB)')
    db.execute('CREATE TABLE IF NOT EXISTS tiles (tile INTEGER, record INTEGER)')
    stats = {}
    for source in sorted(HERE.glob('*.pbf')):
        completed = db.execute('SELECT count FROM done WHERE source=?', (source.name,)).fetchone()
        if completed:
            stats[source.name] = completed[0]
            continue
        count = 0
        processor = osmium.FileProcessor(str(source)).with_locations('flex_mem')
        processor = processor.with_filter(osmium.filter.KeyFilter('highway'))
        processor = processor.with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
        for way in processor:
            if way.tags.get('area') == 'yes':
                continue
            kind = CLASSES.get(way.tags.get('highway', '').removesuffix('_link'))
            if kind is None:
                continue
            coords = [(round(node.lon, 5), round(node.lat, 5)) for node in way.nodes if node.location.valid()]
            if len(coords) < 2:
                continue
            west = min(p[0] for p in coords)
            east = max(p[0] for p in coords)
            south = min(p[1] for p in coords)
            north = max(p[1] for p in coords)
            if east < 17 or west >= 25 or north < 40 or south >= 48:
                continue
            if not db.execute('INSERT OR IGNORE INTO ways VALUES (?)', (way.id,)).rowcount:
                continue
            count += 1
            name = way.tags.get('name:sr-Latn', way.tags.get('name', way.tags.get('ref', '')))
            encoded = name[:160].encode('utf8')
            for offset in range(0, len(coords) - 1, 180):
                segment = coords[offset:offset + 181]
                packed = struct.pack('>BH', kind, len(encoded)) + encoded
                packed += struct.pack('>H', len(segment))
                packed += struct.pack('>' + 'f' * (len(segment) * 2), *(v for p in segment for v in p))
                left = max(68, math.floor(min(p[0] for p in segment) * 4))
                right = min(99, math.floor(max(p[0] for p in segment) * 4))
                bottom = max(160, math.floor(min(p[1] for p in segment) * 4))
                top = min(191, math.floor(max(p[1] for p in segment) * 4))
                ident = db.execute('INSERT INTO records(payload) VALUES (?)', (packed,)).lastrowid
                db.executemany('INSERT INTO tiles VALUES (?,?)',
                    ((x * 1000 + y, ident) for y in range(bottom, top + 1) for x in range(left, right + 1)))
        stats[source.name] = count
        db.execute('INSERT INTO done VALUES (?,?)', (source.name, count))
        db.commit()
        print(source.name, count, 'streets', flush=True)
    dest = APP / 'streets'
    dest.mkdir(exist_ok=True)
    db.execute('CREATE INDEX IF NOT EXISTS tiles_by_tile ON tiles(tile)')
    tiles = db.execute('SELECT tile, count(*) FROM tiles GROUP BY tile').fetchall()
    for tile, count in tiles:
        x, y = divmod(tile, 1000)
        with gzip.open(dest / f'{x}_{y}.atlasdata', 'wb', compresslevel=7) as stream:
            stream.write(b'EVS1')
            stream.write(struct.pack('>I', count))
            for (record,) in db.execute('SELECT payload FROM records JOIN tiles ON records.id=tiles.record WHERE tile=?', (tile,)):
                stream.write(record)
    manifest = {'source': 'OpenStreetMap contributors / Geofabrik', 'license': 'ODbL 1.0',
                'extracts': stats, 'ways': db.execute('SELECT count(*) FROM ways').fetchone()[0], 'tiles': len(tiles),
                'bounds': [17, 40, 25, 48], 'tile_degrees': .25}
    (dest / 'sources.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf8')
    print(manifest, 'bytes', sum(p.stat().st_size for p in dest.glob('*.atlasdata')), flush=True)


if __name__ == '__main__':
    build()
