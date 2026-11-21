"""Encode shape coordinates as lossless signed deltas to reduce Android RAM usage."""
from array import array
import gzip, struct, sys
from pathlib import Path
BASE=Path(__file__).resolve().parent
def varint(buf,delta):
    n=(delta<<1)^(delta>>31)
    while n>=128:
        buf.append((n&127)|128)
        n>>=7
    buf.append(n)

def compress_graph(src,dst):
    with gzip.open(src,'rb') as f:
        assert f.read(4)==b'OSR1'
        nodes,edges,shape_count,turns=struct.unpack('>4i',f.read(16))
        def ints(n):
            a=array('i');a.frombytes(f.read(n*4))
            if sys.byteorder=='little':a.byteswap()
            return a
        lat,lon=ints(nodes),ints(nodes)
        start,end,dist=ints(edges),ints(edges),ints(edges)
        offsets=ints(edges+1)
        sy,sx=ints(shape_count),ints(shape_count)
        tail=f.read()
    compact=bytearray()
    loc=array('i',[0])
    for i in range(edges):
        y,x=lat[start[i]],lon[start[i]]
        for j in range(offsets[i],offsets[i+1]):
            varint(compact,sy[j]-y)
            varint(compact,sx[j]-x)
            y,x=sy[j],sx[j]
        loc.append(len(compact))
    with gzip.open(dst,'wb',compresslevel=5) as out:
        out.write(b'OSR2')
        out.write(struct.pack('>4i',nodes,edges,len(compact),turns))
        for a in (lat,lon,start,end,dist,loc):
            if sys.byteorder=='little':a.byteswap()
            out.write(a.tobytes())
        out.write(compact)
        out.write(tail)
    print('Compact geometry',len(compact),'bytes after inflate;',dst.stat().st_size,'bytes on disk')
if __name__=='__main__':
    original=BASE.parents[1]/'app/src/main/assets/serbia-roads.graph'
    tmp=BASE/'road-graph-compressed.tmp'
    compress_graph(original,tmp)
    tmp.replace(original)

