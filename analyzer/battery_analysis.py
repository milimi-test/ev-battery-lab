"""Auditable SOC/voltage pairs and recorded health trends, not a BMS diagnosis."""
import bisect
import json
import math
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

def stamp(t):
    return datetime.fromtimestamp(t/1000,timezone.utc).isoformat(timespec='seconds') if t else ''

def numeric(v):
    try: return float(v) if not isinstance(v,bool) and math.isfinite(float(v)) else None
    except (TypeError,ValueError,OverflowError): return None

def med(values):
    values=[v for v in values if v is not None]
    return statistics.median(values) if values else None

def quantile(values,p):
    values=sorted(values)
    if not values: return None
    x=(len(values)-1)*p; lo=math.floor(x); hi=math.ceil(x)
    return values[lo]+(values[hi]-values[lo])*(x-lo)

def references():
    base=Path(getattr(sys,'_MEIPASS',Path(__file__).resolve().parent))
    return json.loads((base/'references.json').read_text(encoding='utf-8'))

def estimate(voltage,points):
    """No extrapolation or clamping; the reference is not the vehicle's SOC."""
    if voltage is None or not math.isfinite(voltage) or not points[0][1]<=voltage<=points[-1][1]: return None
    for (a,v),(b,w) in zip(points,points[1:]):
        if v<=voltage<=w: return a+(voltage-v)/(w-v)*(b-a)
    return None

def voltage_analysis(rows, raw_rows):
    all_rows=[]; paired=[]; used=set(); times=[r['timestamp_ms'] for r in rows]; models=references()
    for r in rows:
        t=r['timestamp_ms']; raw=raw_rows[t]; at=numeric(raw.get('bms_extra_at_ms'))
        record=dict(snapshot_utc=stamp(t),timestamp_ms=t,soc_percent=r['soc_percent'],pack_voltage_v=r['battery_voltage_v'],
            mean_voltage_v=numeric(raw.get('cell_voltage_average_v')),min_voltage_v=numeric(raw.get('cell_voltage_min_v')),
            max_voltage_v=numeric(raw.get('cell_voltage_max_v')),current_a=r['battery_current_a'],battery_temp_c=r['battery_temp_avg_c'],
            voltage_utc=stamp(at),voltage_age_s=(t-at)/1000 if at else None,unique_paired=False)
        all_rows.append(record)
        v=r['cell_voltage_average_v']
        if at is None or at in used or v is None or not 1.5<=v<=5: continue
        # Pair the independently polled voltage with SOC/load at that measurement time.
        i=bisect.bisect_left(times,at)
        near=min((j for j in [i-1,i] if 0<=j<len(rows)),key=lambda j:abs(times[j]-at))
        sample=rows[near]
        if abs(sample['timestamp_ms']-at)>15000 or sample['soc_percent'] is None: continue
        used.add(at); record['unique_paired']=True
        paired.append(dict(t=int(at),measurement_utc=stamp(at),soc_percent=sample['soc_percent'],voltage_v=v,
            pack_voltage_v=sample['battery_voltage_v'],current_a=sample['battery_current_a'],battery_temp_c=sample['battery_temp_avg_c'],
            alignment_s=(sample['timestamp_ms']-at)/1000,low_current=sample['battery_current_a'] is not None and abs(sample['battery_current_a'])<=5))
    bins=defaultdict(list)
    for p in paired: bins[min(100,math.floor(p['soc_percent']+.5))].append(p)
    result=[]
    for soc,ps in sorted(bins.items()):
        volts=[p['voltage_v'] for p in ps]; low=[p for p in ps if p['low_current']]
        v=med(volts); qv=med(p['voltage_v'] for p in low)
        b=dict(soc_bin=soc,soc_median=med(p['soc_percent'] for p in ps),samples=len(ps),days=len({p['measurement_utc'][:10] for p in ps}),
            median_voltage_v=v,q25_voltage_v=quantile(volts,.25),q75_voltage_v=quantile(volts,.75),
            min_voltage_v=min(volts),max_voltage_v=max(volts),low_current_samples=len(low),low_current_voltage_v=qv,
            current_median_a=med(p['current_a'] for p in ps),temperature_median_c=med(p['battery_temp_c'] for p in ps))
        for ref in models:
            b[ref['id']+'_soc']=estimate(v,ref['points'])
            b[ref['id']+'_low_soc']=estimate(qv,ref['points'])
        result.append(b)
    conditions=defaultdict(list);endpoints=defaultdict(list)
    for p in paired:
        if p['battery_temp_c'] is None or p['current_a'] is None:continue
        temperature=math.floor(p['battery_temp_c']/5)*5
        load='low |I|≤5 A' if p['low_current'] else 'discharge I>5 A' if p['current_a']>5 else 'charge I<−5 A'
        key=(p['measurement_utc'][:7],min(95,math.floor(p['soc_percent']/5)*5),temperature,load)
        conditions[key].append(p)
        if p['low_current'] and (p['soc_percent']>=99.5 or p['soc_percent']<=.5):
            endpoints[(p['measurement_utc'][:10],'upper' if p['soc_percent']>=99.5 else 'lower',temperature)].append(p)
    condition_rows=[];endpoint_rows=[]
    for (month,soc,temp,load),ps in sorted(conditions.items()):
        voltage=med(p['voltage_v'] for p in ps);actual=med(p['soc_percent'] for p in ps)
        for ref in models:
            inferred=estimate(voltage,ref['points'])
            condition_rows.append(dict(month=month,vehicle_soc_band=f'{soc}–{soc+5}',vehicle_soc_median=actual,
                battery_temperature_band=f'{temp}–<{temp+5} °C',load=load,samples=len(ps),median_voltage_v=voltage,
                reference=ref['name'],reference_soc=inferred,reference_minus_vehicle_pp=inferred-actual if inferred is not None else None))
    for (day,edge,temp),ps in sorted(endpoints.items()):
        for ref in models:
            voltage=med(p['voltage_v'] for p in ps);inferred=estimate(voltage,ref['points']) if len(ps)>=3 else None
            endpoint_rows.append(dict(day_utc=day,endpoint=edge,temperature_band=f'{temp}–<{temp+5} °C',samples=len(ps),
                vehicle_soc_median=med(p['soc_percent'] for p in ps),median_voltage_v=voltage,reference=ref['name'],reference_soc=inferred,
                reference_headroom_pp=100-inferred if edge=='upper' and inferred is not None else None,
                reference_bottom_pp=inferred if edge=='lower' else None))
    return dict(soc_readings=all_rows,soc_pairs=paired,soc_medians=result,soc_conditions=condition_rows,soc_endpoints=endpoint_rows)

def health_analysis(rows, expected_groups=None):
    days=defaultdict(list); group_days=defaultdict(lambda:defaultdict(list)); seen_v=set(); seen_s=set(); soh_days=defaultdict(lambda:defaultdict(list))
    conditions=defaultdict(lambda:defaultdict(list))
    for r in rows:
        d=stamp(r['timestamp_ms'])[:10]; days[d].append(r)
        for key,at_key,seen,age in [('cell_voltages_v','cell_voltages_at_ms',seen_v,120000),('cell_soh_percent','cell_soh_at_ms',seen_s,600000)]:
            at=numeric(r.get(at_key)); raw=r.get(key)
            if at is None or at in seen or not 0<=r['timestamp_ms']-at<=age or not isinstance(raw,list) or not raw: continue
            values=[numeric(v) for v in raw]
            if expected_groups and len(values)!=expected_groups: continue
            if any(v is None or not (1.5<=v<=5 if key=='cell_voltages_v' else 0<=v<=100) for v in values): continue
            seen.add(at); day=stamp(at)[:10]
            if key=='cell_soh_percent':
                for i,v in enumerate(values,1): soh_days[i][day].append(v)
                continue
            mean=statistics.mean(values)
            soc,temp,current=[numeric(r.get(k)) for k in ['soc_percent','battery_temp_avg_c','battery_current_a']]
            condition=(math.floor(soc/5),math.floor(temp/5),math.floor(current/10)) if all(x is not None for x in [soc,temp,current]) else None
            for i,v in enumerate(values,1):
                deviation=(v-mean)*1000; group_days[i][day].append(deviation)
                if condition is not None: conditions[i][(day,condition)].append(deviation)
    daily=[]
    for d,rs in sorted(days.items()):
        daily.append(dict(day_utc=d,samples=len(rs),soh_median=med(r['soh_percent'] for r in rs),
            battery_temp_median=med(r['battery_temp_avg_c'] for r in rs),spread_median_mv=med(r['spread_mv'] for r in rs)))
    observed=[x for x in daily if x['soh_median'] is not None]
    pack=dict(first_day=observed[0]['day_utc'] if observed else None,last_day=observed[-1]['day_utc'] if observed else None,
        first_soh=observed[0]['soh_median'] if observed else None,last_soh=observed[-1]['soh_median'] if observed else None,
        change_pp=observed[-1]['soh_median']-observed[0]['soh_median'] if len(observed)>=2 else None)
    group_rows=[]; trend=[]
    for i in sorted(set(group_days)|set(soh_days)):
        by_day=group_days[i]; ds=sorted(by_day); sd=sorted(soh_days[i]); first=med(soh_days[i][sd[0]]) if sd else None; last=med(soh_days[i][sd[-1]]) if sd else None
        paired_changes=[]; early_n=recent_n=0
        # Compare only earliest/latest days with matching SOC, temperature and signed-load bins.
        if len(ds)>=2:
            early={c:v for (d,c),v in conditions[i].items() if d==ds[0]}
            recent={c:v for (d,c),v in conditions[i].items() if d==ds[-1]}
            for c in early.keys() & recent.keys():
                if len(early[c])>=3 and len(recent[c])>=3:
                    paired_changes.append(med(recent[c])-med(early[c])); early_n+=len(early[c]);recent_n+=len(recent[c])
        change=med(paired_changes) if early_n>=10 and recent_n>=10 else None
        group_rows.append(dict(group=i,first_day=ds[0] if ds else (sd[0] if sd else None),last_day=ds[-1] if ds else (sd[-1] if sd else None),
            first_deviation_mv=med(by_day[ds[0]]) if ds else None,last_deviation_mv=med(by_day[ds[-1]]) if ds else None,
            comparable_shift_mv=change,matched_conditions=len(paired_changes),early_samples=early_n,recent_samples=recent_n,
            bms_soh_first=first,bms_soh_last=last,bms_soh_change_pp=last-first if len(sd)>=2 else None))
        for day in ds: trend.append(dict(group=i,day_utc=day,readings=len(by_day[day]),median_deviation_mv=med(by_day[day]),bms_soh_median=med(soh_days[i].get(day,[]))))
    return dict(health_pack=pack,health_daily=daily,health_groups=group_rows,health_group_daily=trend)
