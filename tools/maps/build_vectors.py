import gzip,json,zipfile,collections,sys,math,os,struct
from pathlib import Path
import osmium
from shapely.geometry import LineString, MultiLineString, box, shape
from shapely.ops import linemerge, unary_union

HERE=Path(__file__).resolve().parent
os.chdir(HERE)
BBOX=box(17,40,25,48)
OUT=HERE.parents[1]/'app/src/main/assets/atlas_vectors.atlasdata'
def lines(geom):
    if geom.is_empty: return
    if geom.geom_type in ('LineString','LinearRing'): yield geom
    elif hasattr(geom,'geoms'):
        for g in geom.geoms: yield from lines(g)
def flat(line):
    return [round(float(v),5) for xy in line.coords for v in xy[:2]]
def write_atlas(out, borders, roads, places):
    def label(f, value):
        encoded=value.encode('utf8')
        f.write(struct.pack('>H',len(encoded)))
        f.write(encoded)
    def line(f, kind, points):
        f.write(struct.pack('>BH',kind,len(points)//2))
        f.write(struct.pack('>'+str(len(points))+'f',*points))
    with gzip.open(out,'wb',compresslevel=7) as f:
        f.write(b'EVG1')
        f.write(struct.pack('>III',len(places),len(borders),len(roads)))
        for x,y,name,pop,tier in places:
            f.write(struct.pack('>ffiB',x,y,pop,tier))
            label(f,name)
        for kind,coords in borders:
            line(f,kind,coords)
        for kind,ref,coords in roads:
            label(f,ref)
            line(f,kind,coords)

def street_extract(path):
    cache=HERE/(path.stem+'-ways.gz')
    if cache.exists(): return
    ways={}; accepted={'motorway':0,'trunk':1,'primary':2}
    p=osmium.FileProcessor(str(path)).with_locations('flex_mem').with_filter(osmium.filter.KeyFilter('highway')).with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
    for w in p:
        highway=w.tags.get('highway','')
        label=w.tags.get('ref','').split(';')[0][:18]
        major=path.stem in ('serbia','kosovo') and label.isdigit() and 10<=int(label)<=99
        k=accepted.get(highway.removesuffix('_link'),2 if highway=='secondary' and major else None)
        if k is None or w.tags.get('access','') in ('private','no'):continue
        coords=[(n.lon,n.lat) for n in w.nodes if n.location.valid()]
        if len(coords)<2:continue
        geom=LineString(coords)
        if not BBOX.intersects(geom): continue
        ways[w.id]=[k,label,[flat(l) for l in lines(geom.intersection(BBOX).simplify(.00013,preserve_topology=False)) if len(l.coords)>=2]]
    with gzip.open(cache.with_suffix('.writing'),'wt',encoding='utf8') as f:json.dump(ways,f,separators=(',',':'))
    cache.with_suffix('.writing').replace(cache)
    print(path.name,len(ways),flush=True)

def build():
    raw=json.loads(Path('countries.geojson').read_text(encoding='utf8'))
    geomByName={f['properties']['ADMIN']:shape(f['geometry']) for f in raw['features']}
    rs=geomByName['Republic of Serbia']; ks=geomByName['Kosovo']
    territory=unary_union([rs,ks])
    border=territory.boundary.intersection(BBOX)
    another=unary_union([g.boundary.intersection(BBOX) for n,g in geomByName.items() if n not in ('Republic of Serbia','Kosovo') and BBOX.intersects(g)]).difference(border)
    inner=rs.boundary.intersection(ks.boundary).intersection(BBOX)
    borders=[]
    for k,g in [(1,another),(0,border),(2,inner)]:
        geom=linemerge(list(lines(g))) if g.geom_type!='LineString' else g
        borders.extend([[k,flat(l)] for l in lines(geom.simplify(.00065,preserve_topology=False))])
    places={}
    for arch,fname in [('cities.zip','cities500.txt'),('RS.zip','RS.txt'),('XK.zip','XK.txt')]:
        for line in zipfile.ZipFile(arch).open(fname):
            row=line.decode().split('\t')
            if row[6]!='P' or row[7] not in ('PPL','PPLA','PPLA2','PPLA3','PPLA4','PPLC','PPLX'):continue
            lat,lon=map(float,row[4:6]);pop=int(row[14] or 0)
            if not(40<=lat<48 and 17<=lon<25): continue
            tier=0 if row[7]=='PPLC' or pop>=180000 else 1 if pop>=45000 or row[7]=='PPLA' else 2 if pop>=7000 or row[7]=='PPLA2' else 3 if pop>=1100 or 'PPLA' in row[7] else 4
            if row[7]=='PPLX' and not pop:continue
            name=row[1]
            name={'Belgrade':'Beograd','Pristina':'Priština','Pristina ': 'Priština','Prishtina':'Priština','Skopje':'Skoplje','Bucharest':'Bukurešt','Sofia':'Sofija','Budapest':'Budimpešta','Tirana':'Tirana'}.get(name,name)
            places[row[0]]=[round(lon,5),round(lat,5),name,pop,tier]
    wayids=set(); groups=collections.defaultdict(list)
    for f in sorted(HERE.glob('*-ways.gz')):
        for ident,(k,ref,segments) in json.loads(gzip.decompress(f.read_bytes())).items():
            if ident in wayids:continue
            wayids.add(ident)
            for pts in segments: groups[k,ref].append(list(zip(pts[::2],pts[1::2])))
    roads=[]
    for (k,ref),segs in groups.items():
        for line in lines(linemerge(segs).simplify(.00013,preserve_topology=False)):
            pts=flat(line)
            for a in range(0,len(pts)-2,200):
                chunk=pts[a:a+202]
                if len(chunk)>=4: roads.append([k,ref,chunk])
    write_atlas(OUT,borders,roads,list(places.values()))
    print('OUTPUT',len(places),len(roads),len(borders),OUT.stat().st_size)
if __name__=='__main__':
    if len(sys.argv)>1:
        for p in sys.argv[1:]: street_extract(Path(p))
    else:build()

