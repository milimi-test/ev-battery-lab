from pathlib import Path
from zipfile import ZipFile
import gzip, hashlib, json, struct

apk=Path('app/build/outputs/apk/debug/app-debug.apk')
with ZipFile(apk) as z:
    meta=json.loads(z.read('assets/contours/metadata.json'))
    for name,digest in meta['source_sha256'].items():
        assert hashlib.sha256(z.read('assets/route-heights/'+name)).hexdigest()==digest,name
    totals={}
    for interval in (100,500,1000):
        names=[n for n in z.namelist() if n.startswith(f'assets/contours/{interval}/') and n.endswith('.ctz')]
        lines=size=points=0
        for name in names:
            content=z.read(name);size+=len(content);data=gzip.decompress(content)
            assert data[:4]==b'EVC1',name
            count=struct.unpack_from('>I',data,4)[0];lines+=count;offset=8
            for _ in range(count):
                height,n=struct.unpack_from('>hH',data,offset)
                assert 100<=height<=4000 and height%interval==0 and n>=2,name
                offset+=4+4*n;points+=n
            assert offset==len(data),name
        assert {'tiles':len(names),'lines':lines,'bytes':size}==meta['counts'][str(interval)]
        totals[interval]={'tiles':len(names),'lines':lines,'points':points,'bytes':size}
print(json.dumps({'result':'PASS','contours':totals,'dem_hashes':len(meta['source_sha256'])}))
