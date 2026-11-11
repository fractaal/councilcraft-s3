import sys,struct,zlib,glob,os,collections,statistics as st
exec(open(sys.argv[1]).read().split("world=sys.argv[1]")[0])
world=sys.argv[2]
tot=n=0; levels=collections.Counter(); depth=[]; cols=[]
for path in glob.glob(world+'/region/r.*.mca'):
    rx,rz=map(int,os.path.basename(path).split('.')[1:3]); data=open(path,'rb').read()
    if len(data)<8192: continue
    for i in range(1024):
        off=struct.unpack('>I',data[i*4:i*4+4])[0]; sect=off>>8
        if sect==0: continue
        l=struct.unpack('>I',data[sect*4096:sect*4096+4])[0]
        if data[sect*4096+4]!=2: continue
        c=nbt(zlib.decompress(data[sect*4096+5:sect*4096+4+l]))
        H=c.get('Heightmaps',{})
        if c.get('Status')!='minecraft:full' or 'MOTION_BLOCKING' not in H or 'OCEAN_FLOOR' not in H: continue
        n+=1; miny=c.get('yPos',-4)*16; ws=hm(H['MOTION_BLOCKING'],miny); of=hm(H['OCEAN_FLOOR'],miny)
        cx=rx*32+(i%32); cz=rz*32+(i//32)
        for idx in range(256):
            tot+=1
            if ws[idx]>of[idx]:
                levels[ws[idx]]+=1; depth.append(ws[idx]-of[idx]); cols.append((cx*16+(idx&15),cz*16+(idx>>4)))
print('full chunks',n,'columns',tot,'columns with water',len(depth))
if depth:
    print('water surface y:',dict(levels.most_common(6)),'| depth median',st.median(depth),'max',max(depth),'| deeper than 16:',sum(d>16 for d in depth))
    xs=[c[0] for c in cols]; zs=[c[1] for c in cols]; print('water spans x',min(xs),max(xs),'z',min(zs),max(zs))
