import json,sys
# Noise settings that use the skyislands:terrain density function. usage: gen_java.py settings.json exosphere-overworld.json out.json
S=json.load(open(sys.argv[1])); base=json.load(open(sys.argv[2]))
detail={"type":"minecraft:old_blended_noise","xz_scale":0.25,"y_scale":0.25,"xz_factor":80.0,"y_factor":160.0,"smear_scale_multiplier":4.0}
terrain={"type":"skyislands:terrain","surface":"minecraft:surface","vegetation":"minecraft:vegetation","detail":detail,"settings":S}
base["noise_router"]["final_density"]={"type":"minecraft:squeeze","argument":{"type":"minecraft:mul","argument1":0.64,"argument2":{"type":"minecraft:interpolated","argument":{"type":"minecraft:blend_density","argument":terrain}}}}
# Aquifers are off, so this router slot is free. The water mixin finds the terrain function here.
base["noise_router"]["fluid_level_floodedness"]=terrain
base["noise"]["height"]=S.get("noiseTop",448)+64
json.dump(base,open(sys.argv[3],'w'))
