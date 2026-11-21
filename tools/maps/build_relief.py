from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time
import numpy as np
from PIL import Image
import rasterio
from rasterio.enums import Resampling

BASE=Path(__file__).resolve().parents[2]/'app/src/main/assets/relief'
BASE.mkdir(parents=True,exist_ok=True)
ramp=[(0,'087a77'),(250,'56ad69'),(500,'b2c660'),(1000,'e8bc65'),(1500,'ca8459'),(2500,'a69ac4')]
DEM_TILES=set(Path(__file__).with_name('dem-list.txt').read_text().splitlines())
colors=np.array([list(bytes.fromhex(c)) for _,c in ramp])
def build(key):
    y,x=key
    dest=BASE/f'N{y:02}E{x:03}.jpg'
    if dest.is_file(): return
    tile=f'Copernicus_DSM_COG_30_N{y:02}_00_E{x:03}_00_DEM'
    url=f'https://copernicus-dem-90m.s3.eu-central-1.amazonaws.com/{tile}/{tile}.tif'
    if tile not in DEM_TILES: return
    for attempt in range(3):
        try:
            with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN='EMPTY_DIR',GDAL_HTTP_TIMEOUT='30',GDAL_HTTP_MAX_RETRY='2',GDAL_HTTP_RETRY_DELAY='1'):
                with rasterio.open('/vsicurl/'+url) as ds:
                    data=ds.read(1,out_shape=(512,512),resampling=Resampling.bilinear)
            break
        except Exception:
            if attempt==2: raise
            time.sleep(1)
    rgb=np.stack([np.interp(data,[s for s,c in ramp],colors[:,i]) for i in range(3)],-1).astype('uint8')
    Image.fromarray(rgb).save(dest,quality=82)
if __name__=='__main__':
    with ThreadPoolExecutor(5) as ex:list(ex.map(build,[(y,x) for y in range(40,48) for x in range(17,25)]))
    print('relief',len(list(BASE.glob('*.jpg'))))

