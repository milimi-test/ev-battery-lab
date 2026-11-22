"""Distance-weighted speed/grade bins and conservative matched wind comparisons.

No network lookup, no imputed weather, no claim of causal wind attribution.
"""
import bisect
import math
import statistics
from collections import defaultdict

GRADE_NAMES = ['Down >3% / Spust >3%', 'Down 1–3% / Spust 1–3%',
               'Flat ±1% / Ravno ±1%', 'Up 1–3% / Uspon 1–3%', 'Up >3% / Uspon >3%']

def number(v):
    try:
        return float(v) if not isinstance(v, bool) and math.isfinite(float(v)) else None
    except (TypeError, ValueError, OverflowError):
        return None

def km_between(a, b):
    la, lb = math.radians(a['latitude']), math.radians(b['latitude'])
    x = math.sin((lb-la)/2)**2 + math.cos(la)*math.cos(lb)*math.sin(math.radians(b['longitude']-a['longitude'])/2)**2
    return 12742 * math.asin(min(1, math.sqrt(x)))

def bearing(a, b):
    la, lb = math.radians(a['latitude']), math.radians(b['latitude'])
    dl = math.radians(b['longitude']-a['longitude'])
    return math.degrees(math.atan2(math.sin(dl)*math.cos(lb), math.cos(la)*math.sin(lb)-math.sin(la)*math.cos(lb)*math.cos(dl))) % 360

def angle(a, b):
    return abs((a-b+180) % 360-180)

def grade_index(grade):
    return 0 if grade < -3 else 1 if grade < -1 else 2 if grade <= 1 else 3 if grade <= 3 else 4


def speed_bin(speed):
    return 120 if speed==130 else int(speed//10)*10

def weighted(parts, key):
    valid = [(x[key], dt) for x,dt in parts if number(x.get(key)) is not None]
    return sum(v*dt for v,dt in valid)/sum(dt for _,dt in valid) if valid else None

def overlap_energy(x,start,end):
    left=max(start,x['t']); right=min(end,x['end'])
    if right<=left: return 0.0
    if 'power0' not in x or 'power1' not in x: return x['net']*(right-left)/x['ms']
    p=x['power0']+(x['power1']-x['power0'])*(left-x['t'])/x['ms']
    q=x['power0']+(x['power1']-x['power0'])*(right-x['t'])/x['ms']
    return (p+q)/2*(right-left)/3600000

def environment_report(gps, intervals, weather, trips=()):
    """Use non-overlapping >=300 m GPS windows; never span missing fixes."""
    starts = [x['t'] for x in intervals]
    weather = sorted([w for w in weather if number(w.get('fetched_at_ms')) is not None], key=lambda w:w['fetched_at_ms'])
    wt = [w['fetched_at_ms'] for w in weather]
    windows, route = [], []
    points=[]; travel=0; pass_id=0
    for p in gps:
        accuracy = number(p.get('accuracy_m'))
        if accuracy is None or not 0 <= accuracy <= 30:
            points=[]; travel=0; pass_id+=1; continue
        if not points or not 0 < p['t']-points[-1][0]['t'] <= 20000:
            points=[(p,0.)]; travel=0; pass_id+=1; continue
        step=km_between(points[-1][0], p)
        if step*3600000/(p['t']-points[-1][0]['t']) > 200:
            points=[(p,0.)]; travel=0; pass_id+=1; continue
        travel+=step; points.append((p,travel))
        if travel < .3: continue
        a=points[0][0]; start,end=a['t'],p['t']; parts=[]
        i=max(0,bisect.bisect_right(starts,start)-1)
        while i<len(intervals) and intervals[i]['t']<end:
            x=intervals[i]; overlap=max(0,min(end,x['end'])-max(start,x['t']))
            if overlap and x.get('speed') is not None: parts.append((x,overlap))
            i+=1
        covered=sum(dt for _,dt in parts)
        if end>start and covered/(end-start)>=.95:
            speed=weighted(parts,'speed')
            energy=sum(overlap_energy(x,start,end) for x,_ in parts)
            # GPS and the speed integral must refer to approximately the same distance.
            integrated=sum(x['speed']*dt/3600000 for x,dt in parts)
            if speed is not None and speed>=3 and abs(integrated-travel)/travel<=.20:
                heights=[(d*1000,number(q.get('altitude_m'))) for q,d in points]
                grade=residual=None
                if all(h is not None for _,h in heights) and len(heights)>=3:
                    mx=statistics.mean(x for x,_ in heights); my=statistics.mean(h for _,h in heights)
                    den=sum((x-mx)**2 for x,_ in heights)
                    slope=sum((x-mx)*(h-my) for x,h in heights)/den if den else 0
                    grade=slope*100
                    residual=max(abs(h-(my+slope*(x-mx))) for x,h in heights)
                    if abs(grade)>25 or residual>10: grade=None
                sp=[x.get('speed_min',x['speed']) for x,_ in parts]+[x.get('speed_max',x['speed']) for x,_ in parts]
                course=bearing(a,p); direct=km_between(a,p)
                trip=next((str(t.get('id',t['start'])) for t in trips if t.get('start',0)<=start and (t.get('end') or 0)>=end),f'pass-{pass_id}')
                s=dict(t=start,end=end,trip=trip,km=travel,net_kwh=energy,kwh_per_100km=100*energy/travel,
                       speed_kmh=speed,speed_low=speed_bin(speed),speed_range=max(sp)-min(sp),grade=grade,
                       altitude_residual_m=residual,latitude=(a['latitude']+p['latitude'])/2,
                       longitude=(a['longitude']+p['longitude'])/2,heading=course,straightness=direct/travel,
                       outside_c=weighted(parts,'temp'),battery_c=weighted(parts,'battery_temp'),soc=weighted(parts,'soc0'),
                       headwind_kmh=None,wind_kmh=None,rain_class=None,wind_eligible=False)
                wi=bisect.bisect_right(wt,start)-1
                if wi>=0 and 0<=start-wt[wi]<=3600000:
                    w=weather[wi]; wind=number(w.get('wind_speed_10m_kmh')); direction=number(w.get('wind_from_degrees'))
                    if number(w.get('latitude')) is not None and number(w.get('longitude')) is not None and km_between(a,w)<=25:
                        if wind is not None and 0<=wind<=200 and direction is not None and 0<=direction<=360:
                            s['wind_kmh']=wind; s['headwind_kmh']=wind*math.cos(math.radians(direction-course))
                        if s['outside_c'] is None: s['outside_c']=number(w.get('temperature_2m_c'))
                        rain=number(w.get('precipitation_mm')); snow=number(w.get('snowfall_cm'))
                        if rain is not None and snow is not None:
                            s['rain_class']='snow' if snow>0 else 'wet' if rain>.1 else 'dry'
                s['wind_eligible']=bool(grade is not None and abs(grade)<=.5 and residual is not None and residual<=2.5
                    and s['speed_range']<=6 and s['straightness']>=.97 and len(parts)>=4
                    and all(s[k] is not None for k in ['headwind_kmh','outside_c','battery_c','soc','rain_class']))
                windows.append(s)
        route.append(dict(t=p['t'],lat=p['latitude'],lon=p['longitude'],alt=p.get('altitude_m')))
        points=[(p,0.)]; travel=0
    matrix={}; terrain={}
    for s in windows:
        if s['grade'] is None: continue
        col=grade_index(s['grade']); key=(s['speed_low'],col)
        matrix.setdefault(key,[]).append(s); terrain.setdefault(col,[]).append(s)
    cells=[]
    for (speed,col), items in sorted(matrix.items()):
        km=sum(s['km'] for s in items)
        cells.append(dict(speed_low=speed,speed_band=f'{speed}–<{speed+10}',grade_index=col,grade=GRADE_NAMES[col],
            distance_km=km,segments=len(items),trips=len({s['trip'] for s in items}),
            net_kwh=sum(s['net_kwh'] for s in items),kwh_per_100km=100*sum(s['net_kwh'] for s in items)/km,
            evidence='observed' if km>=5 and len(items)>=10 else 'limited'))
    terrain_rows=[]
    for col,items in sorted(terrain.items()):
        km=sum(s['km'] for s in items)
        terrain_rows.append(dict(group=GRADE_NAMES[col],distance_km=km,net_kwh=sum(s['net_kwh'] for s in items),
            kwh_per_100km=100*sum(s['net_kwh'] for s in items)/km,minutes=sum(s['end']-s['t'] for s in items)/60000,
            segments=len(items),evidence='limited' if km<5 else 'observed'))
    wind,pairs=match_wind(windows)
    return dict(matrix=cells,terrain=terrain_rows,wind=wind,wind_pairs=pairs,segments=windows,route=route,
                wind_candidates=sum(s['wind_eligible'] for s in windows))

def match_wind(segments):
    """One-to-one matching on the same road/direction; never reuse a segment."""
    eligible=[s for s in segments if s['wind_eligible']]
    heads=[s for s in eligible if s['headwind_kmh']>5]
    tails=[s for s in eligible if s['headwind_kmh']< -5]
    grid=defaultdict(list)
    for i,s in enumerate(tails): grid[(s['speed_low'],math.floor(s['latitude']*100),math.floor(s['longitude']*100))].append(i)
    used=set(); pairs=[]
    for h in sorted(heads,key=lambda s:s['t']):
        candidates=[]; lat,lon=math.floor(h['latitude']*100),math.floor(h['longitude']*100)
        for x in range(lat-1,lat+2):
            for y in range(lon-1,lon+2): candidates.extend(grid[(h['speed_low'],x,y)])
        best=None
        for i in candidates:
            if i in used: continue
            t=tails[i]
            if t['trip']==h['trip'] or abs(t['t']-h['t'])<900000: continue
            separation=km_between(h,t)
            if separation>.3 or angle(h['heading'],t['heading'])>15: continue
            if abs(h['speed_kmh']-t['speed_kmh'])>2 or abs(h['grade']-t['grade'])>.25: continue
            if abs(h['outside_c']-t['outside_c'])>3 or abs(h['battery_c']-t['battery_c'])>5 or abs(h['soc']-t['soc'])>10: continue
            if h['rain_class']!=t['rain_class'] or abs(abs(h['headwind_kmh'])-abs(t['headwind_kmh']))>5: continue
            score=separation+abs(h['speed_kmh']-t['speed_kmh'])/10+abs(h['grade']-t['grade'])
            if best is None or score<best[0]: best=(score,i)
        if best is None: continue
        i=best[1]; used.add(i); t=tails[i]
        pairs.append(dict(speed_low=h['speed_low'],head_t=h['t'],tail_t=t['t'],head_trip=h['trip'],tail_trip=t['trip'],
            head_km=h['km'],tail_km=t['km'],head_consumption=h['kwh_per_100km'],tail_consumption=t['kwh_per_100km'],
            difference=h['kwh_per_100km']-t['kwh_per_100km'],head_speed=h['speed_kmh'],tail_speed=t['speed_kmh'],
            head_grade=h['grade'],tail_grade=t['grade'],headwind=h['headwind_kmh'],tailwind=t['headwind_kmh']))
    result=[]
    speeds=sorted({s['speed_low'] for s in segments if s['grade'] is not None and abs(s['grade'])<=1})
    for speed in speeds:
        ps=[p for p in pairs if p['speed_low']==speed]
        head_km=sum(p['head_km'] for p in ps); tail_km=sum(p['tail_km'] for p in ps)
        ht=len({p['head_trip'] for p in ps}); tt=len({p['tail_trip'] for p in ps})
        ready=len(ps)>=10 and head_km>=5 and tail_km>=5 and ht>=2 and tt>=2
        result.append(dict(speed_band=f'{speed}–<{speed+10} km/h',pairs=len(ps),head_trips=ht,tail_trips=tt,
            head_km=head_km,tail_km=tail_km,candidates=sum(s['speed_low']==speed for s in eligible),
            head_median=statistics.median(p['head_consumption'] for p in ps) if ready else None,
            tail_median=statistics.median(p['tail_consumption'] for p in ps) if ready else None,
            paired_delta=statistics.median(p['difference'] for p in ps) if ready else None,
            evidence='matched observation / upareno posmatranje' if ready else 'insufficient pairs / nedovoljno parova'))
    return result,pairs
