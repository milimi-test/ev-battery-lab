"""Battery Lab Analyzer. Offline, standard-library-only export analysis."""
from __future__ import annotations
import argparse, bisect, csv, html, io, json, math, statistics, sys, zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from comparisons import environment_report, overlap_energy, speed_bin
from battery_analysis import voltage_analysis, health_analysis, references
from long_term import load_conditions, long_term_report

VERSION = '0.2.0'
MAX_GAP_MS = 30_000
FIELDS = {
    'soc_percent': ('SOC', '%'), 'soh_percent': ('Pack SOH / SOH paketa', '%'),
    'battery_voltage_v': ('Pack voltage / Napon paketa', 'V'),
    'battery_current_a': ('Current / Struja', 'A'), 'battery_power_kw': ('Power / Snaga', 'kW'),
    'battery_energy_kwh': ('BMS energy / Energija BMS', 'kWh'),
    'cell_voltage_average_v': ('Mean cell-group voltage / Srednji napon', 'V'),
    'cell_voltage_min_v': ('Minimum group voltage / Najmanji napon', 'V'),
    'cell_voltage_max_v': ('Maximum group voltage / Najveći napon', 'V'),
    'spread_mv': ('Cell spread / Razlika napona', 'mV'),
    'vehicle_speed_kmh': ('Vehicle speed / Brzina vozila', 'km/h'),
    'instant_consumption_kwh_100km': ('Instant consumption / Trenutna potrošnja', 'kWh/100 km'),
    'battery_temp_avg_c': ('Battery temperature / Temperatura baterije', '°C'),
    'outside_temp_c': ('Outside temperature / Spoljna temperatura', '°C'),
    'motor_temp_c': ('Motor temperature', '°C'), 'obc_temp_c': ('OBC temperature', '°C'),
    'motor_coolant_temp_c': ('Motor coolant / Rashladna tečnost motora', '°C'),
    'heating_coolant_temp_c': ('Heating coolant / Tečnost grejanja', '°C'),
    'traction_coolant_temp_c': ('Traction coolant / Pogonska rashladna tečnost', '°C'),
    'inverter_temp_c': ('Inverter temperature', '°C'), 'dcdc_temp_c': ('DC/DC temperature', '°C'),
    'auxiliary_voltage_v': ('12 V battery / Akumulator', 'V'),
    'insulation_kohm': ('HV insulation / HV izolacija', 'kΩ'),
}

def num(v):
    if isinstance(v, bool): return None
    try:
        v = float(v)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError, OverflowError): return None

def median(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None

def iso(t):
    return datetime.fromtimestamp(t / 1000, timezone.utc).isoformat(timespec='seconds') if t else ''

def fresh(r, field, stamp, age=120000):
    value, at, t = num(r.get(field)), num(r.get(stamp)), num(r.get('timestamp_ms'))
    return value if value is not None and at is not None and t is not None and 0 <= t-at <= age else None

def distance(a, b):
    la, lb = math.radians(a['latitude']), math.radians(b['latitude'])
    x = math.sin((lb-la)/2)**2 + math.cos(la)*math.cos(lb)*math.sin(math.radians(b['longitude']-a['longitude'])/2)**2
    return 6371 * 2 * math.asin(min(1, math.sqrt(x)))

def positive_integral(a, b, hours):
    if a >= 0 and b >= 0: return (a+b)/2 * hours
    if a <= 0 and b <= 0: return 0.0
    top = max(a,b)
    return top/2 * hours * top/abs(b-a)

def load_exports(paths):
    """Read only known entries, without extracting ZIP paths onto disk."""
    groups = {}
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > 20000 or sum(x.file_size for x in entries) > 750*1024**2:
                raise ValueError('Export too large (750 MB unpacked / 20,000 files).')
            if any(x.file_size > 120*1024**2 for x in entries): raise ValueError('Oversized ZIP entry.')
            info = json.loads(archive.read('manifest.json')) if 'manifest.json' in archive.namelist() else {}
            profile = str(info.get('vehicle_profile') or 'legacy-e208')
            group = groups.setdefault(profile, dict(profile=profile, name=info.get('vehicle_name') or 'Peugeot e-208 (legacy export)',
                bms={}, gps={}, records=defaultdict(dict), files=[], malformed=0, duplicates=0, meta=info))
            group['files'].append(Path(path).name)
            for entry in entries:
                name = Path(entry.filename).name
                if name.endswith('.jsonl'):
                    with archive.open(entry) as stream:
                        for raw in stream:
                            try:
                                row = json.loads(raw.decode('utf-8-sig'))
                                if not isinstance(row, dict): raise ValueError('row')
                            except (ValueError, UnicodeError):
                                group['malformed'] += 1; continue
                            if name.startswith('bms-readings'):
                                stamp = num(row.get('timestamp_ms'))
                                if stamp is None or not 946684800000 <= stamp <= 4102444800000:
                                    group['malformed'] += 1; continue
                                stamp = int(stamp)
                                if stamp in group['bms']: group['duplicates'] += 1
                                # Preserve non-null values from overlapping exports.
                                group['bms'].setdefault(stamp, {}).update({k:v for k,v in row.items() if v is not None and v != []})
                            else:
                                key = row.get('id', row.get('fetched_at_ms', row.get('day', row.get('recorded_at_ms'))))
                                if key is None: key = json.dumps(row,sort_keys=True)
                                old = group['records'][name].get(str(key))
                                # A finalized session wins over an older, in-progress snapshot.
                                rank = lambda r: num(r.get('end',r.get('ended_at_ms'))) or num(r.get('samples',r.get('observation_count'))) or 0
                                if old is None or rank(row) >= rank(old): group['records'][name][str(key)] = row
                elif name.startswith('gps-') and name.endswith('.csv'):
                    with archive.open(entry) as stream:
                        for row in csv.DictReader(io.TextIOWrapper(stream, encoding='utf-8-sig')):
                            try:
                                t = datetime.fromisoformat(row['time_utc'].replace('Z','+00:00'))
                                if t.tzinfo is None: t = t.replace(tzinfo=timezone.utc)
                                p = {k:num(row.get(k)) for k in ['latitude','longitude','altitude_m','speed_m_s','accuracy_m']}
                                if p['latitude'] is None or p['longitude'] is None or not -90 <= p['latitude'] <= 90 or not -180 <= p['longitude'] <= 180: raise ValueError('coordinate')
                                p['t'] = int(t.timestamp()*1000)
                                group['gps'][p['t']] = p
                            except (ValueError, KeyError, OverflowError): group['malformed'] += 1
    if not any(g['bms'] for g in groups.values()): raise ValueError('No BMS readings found / Nema BMS očitavanja u izabranim ZIP fajlovima.')
    return groups

def clean_rows(group):
    rows = []
    for t, raw in sorted(group['bms'].items()):
        r = dict(raw, timestamp_ms=t)
        for key in FIELDS: r[key] = num(raw.get(key))
        if r['battery_power_kw'] is None:
            v, a = r['battery_voltage_v'], r['battery_current_a']
            if v is not None and a is not None: r['battery_power_kw'] = v*a/1000
        if r['battery_power_kw'] is not None and abs(r['battery_power_kw']) > 500: r['battery_power_kw'] = None
        for k in ['soc_percent','soh_percent']:
            if r[k] is not None and not 0 <= r[k] <= 100: r[k] = None
        r['vehicle_speed_kmh'] = fresh(raw,'vehicle_speed_kmh','vehicle_speed_at_ms',15000)
        if r['vehicle_speed_kmh'] is not None and not 0 <= r['vehicle_speed_kmh'] <= 250: r['vehicle_speed_kmh'] = None
        r['instant_consumption_kwh_100km'] = 100*r['battery_power_kw']/r['vehicle_speed_kmh'] if r['vehicle_speed_kmh'] is not None and r['vehicle_speed_kmh']>=10 and r['battery_power_kw'] is not None else None
        for k in ['cell_voltage_average_v','cell_voltage_min_v','cell_voltage_max_v']:
            r[k] = fresh(raw,k,'bms_extra_at_ms')
        for k in ['outside_temp_c','motor_temp_c','dcdc_temp_c','obc_temp_c','heating_coolant_temp_c','motor_coolant_temp_c','traction_coolant_temp_c']:
            r[k] = fresh(raw,k,'vcu_extra_at_ms')
        r['inverter_temp_c'] = fresh(raw,'inverter_temp_c','inverter_at_ms')
        lo, hi = r['cell_voltage_min_v'], r['cell_voltage_max_v']
        r['spread_mv'] = (hi-lo)*1000 if lo is not None and hi is not None and 0 < lo <= hi < 5 else None
        rows.append(r)
    return rows

def intervals(rows):
    result = []
    for a,b in zip(rows,rows[1:]):
        dt = b['timestamp_ms']-a['timestamp_ms']
        p,q = a['battery_power_kw'], b['battery_power_kw']
        if not 0 < dt <= MAX_GAP_MS or p is None or q is None: continue
        speed = (a['vehicle_speed_kmh']+b['vehicle_speed_kmh'])/2 if a['vehicle_speed_kmh'] is not None and b['vehicle_speed_kmh'] is not None else None
        hours = dt/3600000
        gross, regen = positive_integral(p,q,hours), positive_integral(-p,-q,hours)
        result.append(dict(t=a['timestamp_ms'],end=b['timestamp_ms'],ms=dt,power0=p,power1=q,net=gross-regen,gross=gross,regen=regen,
            km=speed*hours if speed is not None else None,speed=speed,temp=median([a['outside_temp_c'],b['outside_temp_c']]),
            speed_min=min(a['vehicle_speed_kmh'],b['vehicle_speed_kmh']) if speed is not None else None,
            speed_max=max(a['vehicle_speed_kmh'],b['vehicle_speed_kmh']) if speed is not None else None,
            battery_temp=median([a['battery_temp_avg_c'],b['battery_temp_avg_c']]),
            soc0=a['soc_percent'],soc1=b['soc_percent']))
    return result

def summary(v):
    vals = [num(x) for x in v]; vals = [x for x in vals if x is not None]
    return dict(count=len(vals),min=min(vals),median=median(vals),max=max(vals),last=vals[-1]) if vals else dict(count=0,min=None,median=None,max=None,last=None)

def bins_add(bins, key, energy, km, ms):
    b = bins.setdefault(key,dict(group=key,net_kwh=0,distance_km=0,minutes=0,segments=0))
    b['net_kwh'] += energy; b['distance_km'] += km; b['minutes'] += ms/60000; b['segments'] += 1

def finish_bins(bins):
    for v in bins.values():
        v['kwh_per_100km'] = 100*v['net_kwh']/v['distance_km'] if v['distance_km']>0 else None
        v['evidence'] = 'limited / malo podataka' if v['distance_km']<5 else 'observed / izmereno'
    return list(bins.values())

def analyze_group(g, conditions=None):
    rows = clean_rows(g); iv = intervals(rows); starts = [r['timestamp_ms'] for r in rows]
    stats = {k:summary(r[k] for r in rows) for k in FIELDS}
    speed_bins, temp_bins = {}, {}
    for x in iv:
        if x['speed'] is None or x['speed']<3 or x['km'] is None: continue
        low = speed_bin(x['speed'])
        label='120–130 km/h' if low==120 else f'{low}–<{low+10} km/h'
        bins_add(speed_bins,label,x['net'],x['km'],x['ms'])
        if x['temp'] is not None:
            low = math.floor(x['temp']/5)*5
            bins_add(temp_bins,f'{low}–{low+5} °C',x['net'],x['km'],x['ms'])
    trips = []; iv_starts=[x['t'] for x in iv]
    for raw in sorted(g['records']['trip-sessions.jsonl'].values(),key=lambda x:x.get('start',0)):
        start,end = num(raw.get('start')),num(raw.get('end'))
        if not start or not end or end<=start: continue
        pairs = []
        begin=max(0,bisect.bisect_right(iv_starts,start)-1)
        finish=bisect.bisect_left(iv_starts,end)
        for x in iv[begin:finish]:
            overlap = max(0,min(end,x['end'])-max(start,x['t']))
            if overlap: pairs.append((x,overlap/x['ms']))
        covered = sum(x['ms']*f for x,f in pairs); net = sum(overlap_energy(x,start,end) for x,f in pairs)
        km = sum(x['km']*f for x,f in pairs if x['km'] is not None)
        km_covered = sum(x['ms']*f for x,f in pairs if x['km'] is not None)
        odo0,odo1 = num(raw.get('odo0')),num(raw.get('odo1'))
        odo = odo1-odo0 if odo0 is not None and odo1 is not None and odo1>=odo0 else None
        if odo is not None and odo > (end-start)/3600000*250: odo = None
        coverage = min(100,covered/(end-start)*100)
        soc0,soc1 = num(raw.get('soc0')),num(raw.get('soc1'))
        drop = soc0-soc1 if soc0 is not None and soc1 is not None else None
        capacity = net/(drop/100) if drop is not None and 10<=drop<=100 and coverage>=95 and net>0 else None
        denominator = odo if odo is not None and odo>0 else km if km_covered/(end-start)>=.95 else None
        trips.append(dict(start_utc=iso(start),end_utc=iso(end),minutes=(end-start)/60000,odometer_km=odo,
            speed_integrated_km=km,energy_kwh=net,coverage_percent=coverage,
            kwh_per_100km=100*net/denominator if denominator and coverage>=95 else None,
            soc_start=soc0,soc_end=soc1,soc_drop=drop,capacity_estimate_kwh=capacity))
    cells = {}; seen = set(); temps = {}; seen_t = set(); soh = {}; seen_s = set()
    for r in rows:
        for key,atkey,target,visited,age,lo,hi in [
            ('cell_voltages_v','cell_voltages_at_ms',cells,seen,120000,1.5,5),
            ('probe_temperatures_c','probe_temperatures_at_ms',temps,seen_t,120000,-50,120),
            ('cell_soh_percent','cell_soh_at_ms',soh,seen_s,600000,0,100)]:
            at = num(r.get(atkey)); values = r.get(key,[])
            if at is None or at in visited or not 0<=r['timestamp_ms']-at<=age or not isinstance(values,list): continue
            parsed = [num(x) for x in values]
            if not parsed or any(v is None or not lo<=v<=hi for v in parsed): continue
            expected = num(g['meta'].get('series_groups'))
            if key!='probe_temperatures_c' and expected and len(parsed)!=expected: continue
            visited.add(at)
            avg = statistics.mean(parsed)
            for index,v in enumerate(parsed,1):
                c = target.setdefault(index,dict(values=[],deviation=[],lowest=0))
                c['values'].append(v); c['deviation'].append(v-avg)
                if v==min(parsed): c['lowest'] += 1
    cell_rows = []
    for idx,c in sorted(cells.items()):
        s = summary(c['values']); sh = summary(soh.get(idx,{}).get('values',[]))
        cell_rows.append(dict(group=idx,readings=s['count'],min_v=s['min'],median_v=s['median'],max_v=s['max'],
            last_v=s['last'],median_deviation_mv=median(c['deviation'])*1000,lowest_count=c['lowest'],bms_soh_last=sh['last']))
    probes = [dict(probe=k,**summary(v['values'])) for k,v in sorted(temps.items())]
    soc_bins = []
    used = set(); by_soc = defaultdict(list)
    for r in rows:
        at = r.get('bms_extra_at_ms'); v = r['cell_voltage_average_v']; soc = r['soc_percent']
        if at is None or at in used or v is None or soc is None: continue
        used.add(at); by_soc[min(95,int(soc//5)*5)].append(r)
    for low,rs in sorted(by_soc.items()):
        quiet = [r for r in rs if r['battery_current_a'] is not None and abs(r['battery_current_a'])<=5]
        soc_bins.append(dict(soc_band=f'{low}–{low+5}%',samples=len(rs),median_voltage_v=median(r['cell_voltage_average_v'] for r in rs),
            low_current_samples=len(quiet),low_current_voltage_v=median(r['cell_voltage_average_v'] for r in quiet),
            median_battery_temp_c=median(r['battery_temp_avg_c'] for r in rs)))
    gps = sorted(g['gps'].values(),key=lambda p:p['t'])
    env = environment_report(gps, iv, list(g['records']['weather-samples.jsonl'].values()), list(g['records']['trip-sessions.jsonl'].values()))
    voltage = voltage_analysis(rows,g['bms'])
    health = health_analysis(rows,num(g['meta'].get('series_groups')))
    annual = long_term_report(g['profile'],rows,iv,trips,env['segments'],list(g['records']['tire-history.jsonl'].values()),conditions or {})
    charges = []
    for c in g['records']['charge-sessions.jsonl'].values():
        energy = num(c.get('battery_energy_added_kwh')); coverage = num(c.get('power_coverage_percent'))
        valid = c.get('energy_basis')=='negative_power_integral' and coverage is not None and coverage>=95 and c.get('ended_at_ms')
        meters = [m for m in g['records']['charger-meter.jsonl'].values() if m.get('charge_id')==c.get('id')]
        meter = max(meters,key=lambda m:m.get('recorded_at_ms',0)) if meters else {}
        wall = num(meter.get('charger_delivered_kwh'))
        loss = 100*(wall-energy)/wall if valid and wall and energy is not None and 0<=energy<=wall else None
        charges.append(dict(start_utc=iso(c.get('started_at_ms')),kind=c.get('charge_kind','unknown'),battery_kwh=energy,
            coverage_percent=coverage,charger_kwh=wall,loss_percent=loss))
    days = defaultdict(list)
    for r in rows: days[iso(r['timestamp_ms'])[:10]].append(r)
    daily = [dict(day_utc=d,readings=len(rs),soc_min=min([r['soc_percent'] for r in rs if r['soc_percent'] is not None],default=None),
        soc_max=max([r['soc_percent'] for r in rs if r['soc_percent'] is not None],default=None),
        soh_last=summary(r['soh_percent'] for r in rs)['last'],battery_temp_median=median(r['battery_temp_avg_c'] for r in rs),
        spread_max_mv=max([r['spread_mv'] for r in rs if r['spread_mv'] is not None],default=None)) for d,rs in sorted(days.items())]
    timeline = [{**{k:r[k] for k in FIELDS},'t':r['timestamp_ms']} for r in rows]
    # Keep chart payload bounded, preserving per-bucket extrema for every signal.
    if len(timeline)>12000:
        step=math.ceil(len(timeline)/500); selected=set()
        for i in range(0,len(timeline),step):
            chunk=list(range(i,min(i+step,len(timeline)))); selected.update([chunk[0],chunk[-1]])
            for key in FIELDS:
                valid=[j for j in chunk if timeline[j][key] is not None]
                if valid: selected.update([min(valid,key=lambda j:timeline[j][key]),max(valid,key=lambda j:timeline[j][key])])
        timeline=[timeline[i] for i in sorted(selected)]
    return dict(profile=g['profile'],name=g['name'],files=g['files'],readings=len(rows),duplicates=g['duplicates'],malformed=g['malformed'],
        start_utc=iso(starts[0]) if starts else '',end_utc=iso(starts[-1]) if starts else '',stats=stats,timeline=timeline,
        trips=trips,cells=cell_rows,probes=probes,soc_bins=soc_bins,daily=daily,speed=finish_bins(speed_bins),
        temperature=finish_bins(temp_bins),terrain=env['terrain'],wind=env['wind'],route=env['route'],charges=charges,
        speed_grade=env['matrix'],wind_pairs=env['wind_pairs'],environment_segments=env['segments'],wind_candidates=env['wind_candidates'],
        **voltage,**health,**annual,
        charging_gaps=len(g['records']['charge-gaps.jsonl']),untracked=list(g['records']['untracked-intervals.jsonl'].values()),
        gps_points=len(gps),weather_samples=len(g['records']['weather-samples.jsonl']),tire=g['meta'].get('tire_profile',''),
        hvac_recorded=any(any(k in r for k in ['hvac_on','climate_on','ac_on']) for r in rows))

def environment(gps, iv, weather):
    result = environment_report(gps, iv, weather)
    return result["terrain"], result["wind"], result["route"]


def write_csv(path, rows):
    if not rows: return
    with path.open('w',newline='',encoding='utf-8-sig') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(rows[0])); writer.writeheader()
        for row in rows:
            # Protect spreadsheet users from formulas embedded in untrusted export text.
            safe={k: ("'"+v if isinstance(v,str) and v.startswith(('=','+','-','@')) else v) for k,v in row.items()}
            writer.writerow(safe)

def run(paths, destination, conditions_path=None):
    config=load_conditions(conditions_path)
    groups=load_exports(paths); report=[analyze_group(g,config) for g in groups.values() if g['bms']]
    destination=Path(destination); destination.mkdir(parents=True,exist_ok=True)
    base=Path(getattr(sys,'_MEIPASS',Path(__file__).resolve().parent))
    payload=dict(version=VERSION,created_utc=datetime.now(timezone.utc).isoformat(),fields=FIELDS,vehicles=report,references=references(),
        aging_research=json.loads((base/'aging_research.json').read_text(encoding='utf-8')))
    template=(base/'report.html').read_text(encoding='utf-8')
    template=template.replace('__REPORT_CSS__',(base/'report.css').read_text(encoding='utf-8')).replace('__REPORT_JS__',(base/'report.js').read_text(encoding='utf-8'))
    data=json.dumps(payload,ensure_ascii=False,allow_nan=False).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
    output=destination/'report.html'; output.write_text(template.replace('__REPORT_DATA__',data),encoding='utf-8')
    for index,g in enumerate(report,1):
        for key in ['trips','cells','probes','soc_bins','daily','speed','temperature','terrain','wind','charges',
                    'soc_readings','soc_pairs','soc_medians','speed_grade','wind_pairs','environment_segments',
                    'health_daily','health_groups','health_group_daily','soc_conditions','soc_endpoints',
                    'monthly','conditions','condition_matches','condition_pairs','cycle_intervals']:
            write_csv(destination/f'vehicle-{index}-{key}.csv',g[key])
    (destination/'summary.json').write_text(json.dumps(payload,ensure_ascii=False,allow_nan=False,indent=2),encoding='utf-8')
    return output

def main():
    parser=argparse.ArgumentParser(description='Offline Battery Lab ZIP analysis')
    parser.add_argument('exports',nargs='+',type=Path); parser.add_argument('-o','--output',type=Path,required=True)
    parser.add_argument('--conditions',type=Path,help='Dated tire/HVAC periods and fixed initial energy references (JSON)')
    args=parser.parse_args(); print(run(args.exports,args.output,args.conditions))

if __name__=='__main__': main()
