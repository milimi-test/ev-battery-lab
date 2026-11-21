"""Build the local RGB tile pyramids from annual Copernicus Sentinel-2 COGs."""
import concurrent.futures, io, os, struct, time, urllib.error, urllib.request
from pathlib import Path
import numpy as np
from PIL import Image
import rasterio
from rasterio.enums import Resampling

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/src/main/assets/atlas'
OUT.mkdir(parents=True,exist_ok=True)
WEST,EAST,SOUTH,NORTH=17,25,40,48
def build(key):
    lat,lon=key
    if (lat,lon)==(41,18):return  # entirely open Adriatic Sea
    name=f'N{lat:02}E{lon:03}'
    dest=OUT/(name+'.s2map')
    if dest.is_file():return
    levels=4 if 41<=lat<=46 and 18<=lon<=23 else 3
    # The 3,000-pixel COG overview is a native 4x decimation (about 27–37 m).
    # Use the 6,000-pixel overview in the central region, 3,000 in the surroundings.
    native=6000 if levels==4 else 3000
    url=f'https://esa-worldcover-s2.s3.eu-central-1.amazonaws.com/rgbnir/2021/N{lat:02}/ESA_WorldCover_10m_2021_v200_{name}_S2RGBNIR.tif'
    import subprocess, tempfile, tifffile
    scratch=Path(__file__).resolve().parent/'raster-work'
    scratch.mkdir(exist_ok=True)
    def fetch(first,last,target):
        for host in ('esa-worldcover-s2.s3.eu-central-1.amazonaws.com','s3.eu-central-1.amazonaws.com/esa-worldcover-s2','esa-worldcover-s2.s3.amazonaws.com'):
            alt=url.replace('esa-worldcover-s2.s3.eu-central-1.amazonaws.com',host)
            response=subprocess.run(['curl.exe','-fLsS','--retry','2','--retry-all-errors','--max-time','180',
                '--range',f'{first}-{last}','-o',str(target),alt])
            if response.returncode==0: break
        else: raise RuntimeError('could not fetch '+url)
        if target.stat().st_size != last-first+1: raise IOError(f'Short COG range: {target}')
    with tempfile.TemporaryDirectory(dir=scratch) as folder:
        header=Path(folder)/'header.tif'; payload=Path(folder)/'data'
        fetch(0,32767,header)
        with tifffile.TiffFile(header) as t:
            tag=t.pages[0].tags.get('GDAL_METADATA')
            if tag is not None: assert 'B04 (Red)' in str(tag.value)
            index=next(i for i,p in enumerate(t.pages) if p.imagewidth==native)
            page=t.pages[index]
            start=min(page.dataoffsets); stop=max(o+c for o,c in zip(page.dataoffsets,page.databytecounts))
        fetch(start,stop-1,payload)
        with header.open('r+b') as f, payload.open('rb') as data:
            import shutil
            f.seek(start);shutil.copyfileobj(data,f)
        with rasterio.open(f'GTIFF_DIR:{index+1}:{header}') as ds:
            assert ds.width==native
            edge=256*(2**levels)
            lut=np.uint8(np.clip((np.arange(65536,dtype=np.float32)/2450)**0.66*255,0,255))
            bands=[]
            for band in (1,2,3):
                a=ds.read(band,out_shape=(edge,edge),resampling=Resampling.bilinear)
                bands.append(Image.fromarray(lut[a])); del a
            rgb=Image.merge('RGB',bands)
    count=sum(4**l for l in range(levels+1))
    tmp=dest.with_suffix('.partial')
    with tmp.open('wb') as f:
        f.write(b'S2P1'+struct.pack('<II',levels,count)+bytes(4*(count+1)))
        offsets=[]
        for l in range(levels+1):
            n=2**l
            size=256*n
            image=rgb if size==rgb.width else rgb.resize((size,size),Image.Resampling.LANCZOS)
            for y in range(n):
                for x in range(n):
                    offsets.append(f.tell())
                    tile=image.crop((256*x,256*y,256*x+256,256*y+256))
                    tile.save(f,'JPEG',quality=73,subsampling=2,optimize=False)
        offsets.append(f.tell()); f.seek(12);f.write(struct.pack('<'+'I'*len(offsets),*offsets))
    tmp.replace(dest)
    print(name,levels,dest.stat().st_size,flush=True)
if __name__=='__main__':
    keys=sorted([(y,x) for y in range(SOUTH,NORTH) for x in range(WEST,EAST)],key=lambda p:abs(p[0]-44)+abs(p[1]-20))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        failures=[]
        for future in concurrent.futures.as_completed([ex.submit(build,key) for key in keys]):
            try: future.result()
            except Exception as e: failures.append(str(e)); print('FAILED',e,flush=True)
    if failures: raise RuntimeError(failures)

