import sys,struct,zlib,glob,os,io,collections,statistics as st
def nbt(buf):
    f=io.BytesIO(buf)
    def rd(t):
        if t==1:return struct.unpack('>b',f.read(1))[0]
        if t==2:return struct.unpack('>h',f.read(2))[0]
        if t==3:return struct.unpack('>i',f.read(4))[0]
        if t==4:return struct.unpack('>q',f.read(8))[0]
        if t==5:return struct.unpack('>f',f.read(4))[0]
        if t==6:return struct.unpack('>d',f.read(8))[0]
        if t==7:
            n=struct.unpack('>i',f.read(4))[0];return f.read(n)
        if t==8:
            n=struct.unpack('>H',f.read(2))[0];return f.read(n).decode('utf8','replace')
        if t==9:
            tt=f.read(1)[0];n=struct.unpack('>i',f.read(4))[0];return [rd(tt) for _ in range(n)]
        if t==10:
            d={}
            while True:
                tt=f.read(1)[0]
                if tt==0:return d
                n=struct.unpack('>H',f.read(2))[0];k=f.read(n).decode('utf8','replace');d[k]=rd(tt)
        if t==11:
            n=struct.unpack('>i',f.read(4))[0];return struct.unpack('>%di'%n,f.read(4*n))
        if t==12:
            n=struct.unpack('>i',f.read(4))[0];return struct.unpack('>%dq'%n,f.read(8*n))
        raise Exception(t)
    t=f.read(1)[0];n=struct.unpack('>H',f.read(2))[0];f.read(n);return rd(t)
def hm(l,minY):
    vals=[]
    for lng in l:
        lng&=(1<<64)-1
        B=int(__import__('os').environ.get('HMBITS','9'))
        for k in range(64//B): vals.append((lng>>(B*k))&((1<<B)-1))
    return [v+minY-1 for v in vals[:256]]
