"""Build the packaged offline public charging-station catalogue from OSM extracts."""
import json
import math
import re
from pathlib import Path

import osmium

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / 'app/src/main/assets/charging-stations.json'
stations = {}


def power(value):
    match = re.fullmatch(r'\s*([0-9.]+)\s*(kW|W)?\s*', value or '', re.I)
    if not match:
        return None
    n = float(match[1])
    if (match[2] or '').lower() == 'w' or n > 1000:
        n /= 1000
    return n if 0 < n <= 1000 else None


def add(item, lat, lon, country):
    if not (40 <= lat <= 48 and 17 <= lon <= 25):
        return
    tags = dict(item.tags)
    if tags.get('access', '') in ('no', 'private', 'permit') or tags.get('disused') == 'yes':
        return
    if tags.get('motorcar') == 'no':
        return
    sockets = []
    compatible = []
    for key, label, kind in [('type2_combo', 'CCS2', 'dc'), ('type2', 'Type 2', 'ac'),
                              ('type2_cable', 'Type 2 kabl', 'ac'), ('chademo', 'CHAdeMO', ''),
                              ('tesla_supercharger', 'Tesla', ''), ('schuko', 'Šuko', 'ac')]:
        count = tags.get('socket:' + key, '')
        if count and count not in ('no', '0'):
            sockets.append(label)
            if kind:
                compatible.append((kind, power(tags.get('socket:' + key + ':output', ''))))
    dc = [p for k, p in compatible if k == 'dc' and p is not None]
    ac = [p for k, p in compatible if k == 'ac' and p is not None]
    fee = tags.get('charge:fee', tags.get('fee', '')).lower()
    price = tags.get('charge', '')
    # Explicit positive price overrides a contradictory free tag. Access and parking may cost separately.
    conditional = tags.get('fee:conditional') or tags.get('charge:conditional')
    free = fee == 'no' and not conditional and (not price or price.lower() in ('no', 'free', '0', '0 eur', '0 rsd'))
    is_dc = any(k == 'dc' for k, _ in compatible)
    compatible_power = dc if is_dc else ac
    station = dict(id=('n' if item.is_node() else 'w') + str(item.id), lat=round(lat, 6), lon=round(lon, 6),
                   name=tags.get('name:sr-Latn') or tags.get('name') or tags.get('operator') or 'Javni punjač',
                   operator=tags.get('operator', ''), sockets=', '.join(sockets),
                   kind='dc' if is_dc else 'ac' if compatible else 'unknown',
                   compatible=bool(compatible), free=free,
                   fee='free' if free else 'paid' if fee == 'yes' or price else 'unknown',
                   price=price or conditional or '', access=tags.get('access', 'unknown'), hours=tags.get('opening_hours', ''),
                   powerKw=max(compatible_power) if compatible_power else None,
                   address=' '.join(filter(None, [tags.get('addr:city'), tags.get('addr:street'), tags.get('addr:housenumber')])),
                   country=country, checked=tags.get('check_date', tags.get('survey:date', '')))
    stations[station['id']] = station


for source in sorted(HERE.glob('*.pbf')):
    proc = osmium.FileProcessor(str(source)).with_locations('flex_mem').with_filter(
        osmium.filter.TagFilter(('amenity', 'charging_station')))
    before = len(stations)
    for item in proc:
        if item.is_node() and item.location.valid():
            add(item, item.lat, item.lon, source.stem)
        elif item.is_way():
            pts = [(n.lat, n.lon) for n in item.nodes if n.location.valid()]
            if pts:
                add(item, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts), source.stem)
    print(source.name, len(stations) - before, flush=True)

result = dict(source='© OpenStreetMap contributors, ODbL 1.0', sourceUrl='https://www.openstreetmap.org/copyright',
              snapshot='2026-11', bounds=[17, 40, 25, 48],
              stations=sorted(stations.values(), key=lambda p: p['id']))
OUT.write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print('TOTAL', len(stations), 'FREE', sum(s['free'] for s in stations.values()), 'BYTES', OUT.stat().st_size)
