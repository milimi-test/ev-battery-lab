"""Prepare a directed OSM car graph for Serbia and Kosovo. Assets retain OSM topology and road shape."""
from array import array
from collections import defaultdict
import gzip
import math
from pathlib import Path
import struct
import sys
import time

BASE = Path(__file__).resolve().parent
APP = BASE.parents[1] / 'app/src/main/assets'
sys.path.insert(0, str(BASE.parents[1] / '.tooling/map-data'))
import osmium

PBF = [BASE / 'serbia.pbf', BASE / 'kosovo.pbf']
CLASSES = {'motorway':0, 'trunk':1, 'primary':2, 'secondary':3, 'tertiary':4,
           'residential':5, 'unclassified':5, 'living_street':5, 'service':5}
DENIED = {'private','no','agricultural','forestry','delivery','customers','destination','permit'}
IMPASSABLE = {'bollard','bus_trap','cycle_barrier','block','jersey_barrier','sump_buster',
              'chain','kissing_gate','stile','turnstile','log','yes','fence'}
def access(tags, suffix=''):
    for key in ('motorcar','motor_vehicle','vehicle','access'):
        value=tags.get(key+suffix)
        if value: return value
    return None
def way_profile(w):
    t=w.tags; typ=t.get('highway','').removesuffix('_link')
    if typ not in CLASSES or t.get('area')=='yes' or access(t) in DENIED: return
    if typ=='service' and t.get('service') in ('driveway','parking_aisle','emergency_access','drive-through'):return
    if t.get('motorroad')=='no' and typ=='motorway':return
    oneway=t.get('oneway:motor_vehicle',t.get('oneway'))
    if oneway in ('alternating','reversible'):return
    forward=oneway!='-1' and access(t,':forward') not in DENIED
    reverse=oneway not in ('1','yes','true') and access(t,':backward') not in DENIED
    if oneway is None and (typ=='motorway' or t.get('junction') in ('roundabout','circular')):reverse=False
    if not forward and not reverse:return
    return CLASSES[typ],int(forward)+2*int(reverse)

def each_way(locations=False):
    seen=set()
    for path in PBF:
        processor=osmium.FileProcessor(str(path))
        if locations:processor=processor.with_locations('flex_mem')
        processor=processor.with_filter(osmium.filter.KeyFilter('highway')).with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
        for way in processor:
            if way.id in seen:continue
            profile=way_profile(way)
            if profile and len(way.nodes)>1:
                seen.add(way.id)
                yield way,profile

def restrictions():
    blockers=set();turns={};extra=0
    for path in PBF:
        for item in osmium.FileProcessor(str(path)).with_filter(osmium.filter.KeyFilter('barrier','restriction','restriction:motorcar','restriction:motor_vehicle')):
            if item.is_node():
                a=access(item.tags)
                if a in DENIED or (a not in ('yes','permissive','designated') and item.tags.get('barrier') in IMPASSABLE):
                    blockers.add(item.id)
            if not item.is_relation() or item.tags.get('type')!='restriction':continue
            if any(x in ('motorcar','motor_vehicle','vehicle') for x in item.tags.get('except','').split(';')):continue
            typ=item.tags.get('restriction:motorcar',item.tags.get('restriction:motor_vehicle',item.tags.get('restriction','')))
            if not (typ.startswith('only_') or typ.startswith('no_')):continue
            via=[m.ref for m in item.members if m.role=='via' and m.type=='n']
            ws=[(m.role,m.ref) for m in item.members if m.type=='w']
            if len(via)!=1 or any(r=='via' for r,i in ws):
                extra+=1;continue
            turns[item.id]=(typ,via[0],tuple(i for r,i in ws if r=='from'),tuple(i for r,i in ws if r=='to'))
    return blockers,list(turns.values()),extra

def be(out,vals,typ='i'):
    data=array(typ,vals)
    if sys.byteorder=='little':data.byteswap()
    out.write(data.tobytes())

def main():
    blockers,turns,unsupported=restrictions()
    seen=set(); forks=set()
    for w,_ in each_way():
        ids=[n.ref for n in w.nodes]
        forks.add(ids[0]);forks.add(ids[-1])
        for ident in ids:
            if ident in seen:forks.add(ident)
            else:seen.add(ident)
    print('way nodes',len(seen),'junctions',len(forks),'blocked',len(blockers),'turns',len(turns),'via ways skipped',unsupported,flush=True)
    del seen
    nodes={};latitude=array('i');longitude=array('i')
    start=array('i');end=array('i');dist=array('i');flags=bytearray()
    shapeoff=array('i',[0]);shapey=array('i');shapex=array('i')
    turnvias={v for _,v,_,_ in turns}
    around=defaultdict(list)
    def index(ref,lat,lon):
        idx=nodes.get(ref)
        if idx is None:
            idx=len(latitude);nodes[ref]=idx;latitude.append(lat);longitude.append(lon)
        return idx
    for w,(kind,directions) in each_way(True):
        prev=None; first=None; poly=[];length=0.0
        for k,nd in enumerate(w.nodes):
            ref=nd.ref
            if ref in blockers or not nd.location.valid():
                prev=first=None; poly=[]; length=0.;continue
            y=round(nd.lat*100000); x=round(nd.lon*100000)
            if prev is None:
                prev=(y,x);first=(ref,y,x);continue
            length+=math.hypot(y-prev[0],(x-prev[1])*math.cos(math.radians(y/1e5)))*1.11195
            prev=(y,x)
            if ref not in forks and ref not in turnvias and length<180 and k<len(w.nodes)-1:
                poly.append((y,x));continue
            u=index(*first);v=index(ref,y,x)
            if length>=.5 and u!=v:
                num=len(start);start.append(u);end.append(v);dist.append(max(1,round(length*10)));flags.append(kind*4+directions)
                for py,px in poly:shapey.append(py);shapex.append(px)
                shapeoff.append(len(shapex))
                if first[0] in turnvias:around[first[0]].append((w.id,num,False))
                if ref in turnvias:around[ref].append((w.id,num,True))
            first=(ref,y,x);poly=[];length=0.
    forbidden=set()
    for typ,via,fr,to in turns:
        at=around.get(via,[])
        for fid,fe,fin in at:
            if fid not in fr:continue
            incoming=2*fe+int(not fin)
            for tid,te,tin in at:
                outgoing=2*te+int(tin)
                match=tid in to and (fe==te if typ in ('no_u_turn','only_u_turn') and fid==tid else True)
                if match==typ.startswith('no_'):
                    forbidden.add((incoming,outgoing))
    print('graph',len(latitude),'nodes',len(start),'links',len(shapey),'intermediate points',len(forbidden),'turns',flush=True)
    name=APP/'serbia-roads.graph'
    temp=BASE/'routing-graph.tmp'
    with gzip.open(temp,'wb',compresslevel=5) as f:
        f.write(b'OSR1')
        be(f,[len(latitude),len(start),len(shapey),len(forbidden)])
        for a in (latitude,longitude,start,end,dist,shapeoff,shapey,shapex):be(f,a)
        f.write(flags)
        for a,b in sorted(forbidden):f.write(struct.pack('>2i',a,b))
    from compact_routing import compress_graph
    compact=BASE/'road-graph-compressed.tmp'
    compress_graph(temp,compact)
    compact.replace(name)
    temp.unlink()
    print(name,name.stat().st_size,flush=True)
if __name__=='__main__':main()

