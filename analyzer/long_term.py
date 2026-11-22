"""Long-term descriptive statistics and conservative condition matching.

Missing conditions stay unknown. Energy-equivalent cycles use a fixed initial
usable energy reference, never the changing SOH-derived capacity.
"""
import bisect
import json
import math
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from comparisons import number, km_between, angle


def utc(t):
    return datetime.fromtimestamp(t/1000, timezone.utc).isoformat(timespec='seconds')


def median(xs):
    xs=[x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def parse_date(value):
    d=datetime.fromisoformat(value.replace('Z','+00:00'))
    if d.tzinfo is None: d=d.replace(tzinfo=timezone.utc)
    return int(d.timestamp()*1000)


def load_conditions(path=None):
    config=json.loads(Path(path).read_text(encoding='utf-8-sig')) if path else {}
    periods=config.get('periods',[]); capacities=config.get('reference_kwh',{})
    if not isinstance(periods,list) or not isinstance(capacities,dict): raise ValueError('Invalid conditions file')
    for profile,v in capacities.items():
        if number(v) is None or not 5<=float(v)<=300: raise ValueError('Reference kWh must be 5–300: '+profile)
    clean=[]
    for p in periods:
        q=dict(p); q['start']=parse_date(p['start_utc']);q['end']=parse_date(p['end_utc'])
        if not p.get('vehicle_profile') or q['end']<=q['start']: raise ValueError('Period needs vehicle profile and end after start')
        if p.get('tire_type','unknown') not in ['unknown','summer','winter','all-season']: raise ValueError('Unknown tire_type')
        if p.get('hvac','unknown') not in ['unknown','on','off']: raise ValueError('Unknown HVAC state')
        for old in clean:
            if old['vehicle_profile']==q['vehicle_profile'] and max(q['start'],old['start'])<min(q['end'],old['end']):
                raise ValueError('Overlapping condition periods for '+q['vehicle_profile'])
        clean.append(q)
    return dict(periods=clean,reference_kwh={k:float(v) for k,v in capacities.items()})


def reference_capacity(profile,config):
    if profile in config.get('reference_kwh',{}): return config['reference_kwh'][profile],'user fixed reference / korisnička fiksna osnova'
    if profile=='legacy-e208' or profile in ['peugeot_e208_50','peugeot_e2008_50','opel_corsa_e_50','opel_mokka_e_50','citroen_ec4_50','ds3_etense_50']:
        return 46.3,'profile initial usable energy / početna upotrebljiva energija profila'
    if profile.startswith('renault_zoe_'):
        for cap in [22,41,52]:
            if str(cap) in profile.split('_'): return float(cap),'profile nominal usable energy / nominalna upotrebljiva energija'
    return None,'unknown; set a fixed reference / unesi fiksnu osnovu'


def tire_type(label):
    s=str(label).lower()
    if any(x in s for x in ['all-season','all season','celogodi','cjelogodi']): return 'all-season'
    if 'winter' in s or 'zimsk' in s: return 'winter'
    if 'summer' in s or 'letnj' in s or 'ljetn' in s: return 'summer'
    return 'unknown'


def annotate_conditions(segments,rows,history,profile,config):
    history=sorted([h for h in history if number(h.get('changed_at_ms')) is not None],key=lambda h:h['changed_at_ms'])
    ht=[h['changed_at_ms'] for h in history];rt=[r['timestamp_ms'] for r in rows]
    periods=[p for p in config.get('periods',[]) if p['vehicle_profile']==profile]
    for s in segments:
        s.update(tire_type='unknown',tire_label='',tire_source='unknown',hvac='unknown',hvac_source='unknown')
        i=bisect.bisect_right(ht,s['t'])-1
        if i>=0 and (i+1==len(ht) or ht[i+1]>=s['end']):
            h=history[i];label=str(h.get('tire_profile') or '')
            s.update(tire_type=h.get('tire_type') if h.get('tire_type') in ['summer','winter','all-season'] else tire_type(label),tire_label=label,tire_source='export history')
        # Require a fresh, explicit status throughout the window. Temperatures are not a proxy.
        a=max(0,bisect.bisect_right(rt,s['t'])-1);b=bisect.bisect_left(rt,s['end'])+1
        states=[]
        for r in rows[a:b]:
            state=next((r[k] for k in ['hvac_on','climate_on','ac_on'] if isinstance(r.get(k),bool)),None)
            at=number(r.get('hvac_at_ms'))
            if at is None: at=r['timestamp_ms']
            states.append(('on' if state else 'off') if state is not None and 0<=r['timestamp_ms']-at<=15000 else 'unknown')
        if states and len(set(states))==1 and states[0]!='unknown': s.update(hvac=states[0],hvac_source='recorded status')
        for p in periods:
            if p['start']<=s['t'] and p['end']>=s['end']:
                if p.get('tire_type','unknown')!='unknown': s.update(tire_type=p['tire_type'],tire_label=p.get('tire_label',''),tire_source='manual dated period')
                if p.get('hvac','unknown')!='unknown' and s['hvac']=='unknown': s.update(hvac=p['hvac'],hvac_source='manual dated period')
        s['temperature_band']=f"{math.floor(s['outside_c']/5)*5}–<{math.floor(s['outside_c']/5)*5+5} °C" if s['outside_c'] is not None else 'unknown'


def aggregate(items):
    km=sum(s['km'] for s in items);energy=sum(s['net_kwh'] for s in items)
    return dict(distance_km=km,net_kwh=energy,kwh_per_100km=100*energy/km if km else None,
                segments=len(items),trips=len({s['trip'] for s in items}),
                median_speed=median(s['speed_kmh'] for s in items),median_outside_c=median(s['outside_c'] for s in items))


def match_effect(segments,effect):
    """No reused controls. Compare one factor while matching all recorded controls."""
    def side(s):
        if effect=='tires': return 1 if s['tire_type']=='winter' else 0 if s['tire_type']=='summer' else None
        if effect=='rain': return 1 if s['rain_class']=='wet' else 0 if s['rain_class']=='dry' else None
        if effect=='snow': return 1 if s['rain_class']=='snow' else 0 if s['rain_class']=='dry' else None
        if effect=='hvac': return 1 if s['hvac']=='on' else 0 if s['hvac']=='off' else None
        return 1 if s['outside_c']<5 else 0 if 15<=s['outside_c']<=25 else None
    eligible=[s for s in segments if s.get('wind_eligible') and s['tire_type']!='unknown' and s['hvac']!='unknown']
    controls=[s for s in eligible if side(s)==0];treated=[s for s in eligible if side(s)==1]
    grid=defaultdict(list)
    for i,s in enumerate(controls):grid[(s['speed_low'],math.floor(s['latitude']*100),math.floor(s['longitude']*100))].append(i)
    used=set();pairs=[]
    for h in sorted(treated,key=lambda s:s['t']):
        lat,lon=math.floor(h['latitude']*100),math.floor(h['longitude']*100);best=None
        for x in range(lat-1,lat+2):
            for y in range(lon-1,lon+2):
                for i in grid[(h['speed_low'],x,y)]:
                    if i in used:continue
                    c=controls[i];dist=km_between(h,c)
                    if h['trip']==c['trip'] or abs(h['t']-c['t'])<900000 or dist>.3 or angle(h['heading'],c['heading'])>15:continue
                    if abs(h['speed_kmh']-c['speed_kmh'])>2 or abs(h['grade']-c['grade'])>.25 or abs(h['soc']-c['soc'])>10:continue
                    if abs(h['battery_c']-c['battery_c'])>5 or abs(h['headwind_kmh']-c['headwind_kmh'])>3:continue
                    if effect!='temperature' and abs(h['outside_c']-c['outside_c'])>3:continue
                    if effect!='tires' and (h['tire_type'],h['tire_label'])!=(c['tire_type'],c['tire_label']):continue
                    if effect!='hvac' and h['hvac']!=c['hvac']:continue
                    if effect not in ['rain','snow'] and h['rain_class']!=c['rain_class']:continue
                    score=dist+abs(h['speed_kmh']-c['speed_kmh'])/10
                    if best is None or score<best[0]:best=(score,i)
        if best is None:continue
        i=best[1];used.add(i);c=controls[i]
        pairs.append(dict(effect=effect,speed_low=h['speed_low'],treated_utc=utc(h['t']),control_utc=utc(c['t']),
            treated_trip=h['trip'],control_trip=c['trip'],treated_km=h['km'],control_km=c['km'],
            treated_consumption=h['kwh_per_100km'],control_consumption=c['kwh_per_100km'],delta=h['kwh_per_100km']-c['kwh_per_100km']))
    result=[]
    for speed in sorted({p['speed_low'] for p in pairs}) or [None]:
        ps=[p for p in pairs if p['speed_low']==speed];a=sum(p['treated_km'] for p in ps);b=sum(p['control_km'] for p in ps)
        ready=len(ps)>=10 and min(a,b)>=5 and len({p['treated_trip'] for p in ps})>=2 and len({p['control_trip'] for p in ps})>=2
        base=median(p['control_consumption'] for p in ps);delta=median(p['delta'] for p in ps)
        result.append(dict(effect=effect,speed_band=f'{speed}–<{speed+10}' if speed is not None else '—',pairs=len(ps),treated_km=a,control_km=b,
            delta_kwh_100km=delta if ready else None,delta_percent=100*delta/base if ready and base and base>0 else None,
            evidence='matched observation' if ready else 'insufficient comparable data'))
    return result,pairs


def long_term_report(profile,rows,iv,trips,segments,history,config):
    annotate_conditions(segments,rows,history,profile,config)
    cap,basis=reference_capacity(profile,config)
    months=defaultdict(lambda:dict(readings=0,soh=[],temp=[],gross=0.,regen=0.,covered_ms=0.,km=0.,energy=0.,trips=0,eligible=0,capacity=[]))
    for r in rows:
        m=months[utc(r['timestamp_ms'])[:7]];m['readings']+=1;m['soh'].append(r.get('soh_percent'));m['temp'].append(r.get('outside_temp_c'))
    for x in iv:
        # Split exactly at UTC month boundaries; no unrecorded intervals are filled.
        t=x['t']
        while t<x['end']:
            d=datetime.fromtimestamp(t/1000,timezone.utc);next_month=datetime(d.year+(d.month==12),d.month%12+1,1,tzinfo=timezone.utc)
            end=min(x['end'],int(next_month.timestamp()*1000));f=(end-t)/x['ms'];m=months[utc(t)[:7]]
            # Linear positive/negative power integration, including zero crossing.
            a=x['power0']+(x['power1']-x['power0'])*(t-x['t'])/x['ms'];b=x['power0']+(x['power1']-x['power0'])*(end-x['t'])/x['ms']
            def positive(p,q):
                if min(p,q)>=0:return (p+q)/2*(end-t)/3600000
                if max(p,q)<=0:return 0.
                return max(p,q)**2/abs(q-p)/2*(end-t)/3600000
            m['gross']+=positive(a,b);m['regen']+=positive(-a,-b);m['covered_ms']+=end-t;t=end
    for trip in trips:
        m=months[trip['start_utc'][:7]];m['trips']+=1
        if trip['kwh_per_100km'] is not None:
            m['eligible']+=1;m['energy']+=trip['energy_kwh'];m['km']+=trip['odometer_km'] or trip['speed_integrated_km']
        m['capacity'].append(trip['capacity_estimate_kwh'])
    monthly=[]
    for month,m in sorted(months.items()):
        monthly.append(dict(month=month,readings=m['readings'],trips=m['trips'],covered_trips=m['eligible'],distance_km=m['km'],
            net_kwh=m['energy'],kwh_per_100km=100*m['energy']/m['km'] if m['km'] else None,
            discharge_kwh=m['gross'],energy_into_pack_kwh=m['regen'],energy_equivalent_cycles=m['gross']/cap if cap else None,
            covered_hours=m['covered_ms']/3600000,soh_median=median(m['soh']),outside_median_c=median(m['temp']),capacity_median_kwh=median(m['capacity'])))
    condition_rows=[]
    for year in ['all']+sorted({utc(s['t'])[:4] for s in segments}):
        for field in ['tire_type','tire_label','temperature_band','rain_class','hvac']:
            groups=defaultdict(list)
            for s in segments:
                if year=='all' or utc(s['t']).startswith(year):groups[s.get(field) or 'unknown'].append(s)
            for label,items in sorted(groups.items()):condition_rows.append(dict(year=year,factor=field,condition=label,**aggregate(items)))
    matched=[];pairs=[]
    for effect in ['tires','temperature','rain','snow','hvac']:
        r,p=match_effect(segments,effect);matched.extend(r);pairs.extend(p)
    counters=[];seen=set();previous=None;resets=0;missing=0
    for r in rows:
        at=number(r.get('lifetime_counters_at_ms'));v=number(r.get('lifetime_energy_out_kwh'))
        if at is None or at in seen or v is None or v<0 or not 0<=r['timestamp_ms']-at<=120000:continue
        seen.add(at)
        if previous and at<=previous[0]:continue
        if previous and at>previous[0]:
            delta=v-previous[1];hours=(at-previous[0])/3600000
            if delta<0:resets+=1
            elif delta>hours*500+1:missing+=1
            else:counters.append(dict(start_utc=utc(previous[0]),end_utc=utc(at),discharge_delta_kwh=delta,energy_equivalent_cycles=delta/cap if cap else None))
        previous=(at,v)
    total=sum(x['gross'] for x in iv)
    cycle=dict(reference_kwh=cap,reference_basis=basis,recorded_discharge_kwh=total,recorded_energy_efc=total/cap if cap else None,
        counter_discharge_kwh=sum(c['discharge_delta_kwh'] for c in counters) if counters and not resets and not missing else None,
        counter_energy_efc=sum(c['discharge_delta_kwh'] for c in counters)/cap if counters and cap and not resets and not missing else None,
        counter_resets=resets,rejected_counter_intervals=missing,counter_intervals=len(counters))
    return dict(monthly=monthly,conditions=condition_rows,condition_matches=matched,condition_pairs=pairs,cycle_summary=cycle,cycle_intervals=counters)
