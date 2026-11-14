# Prints the compile classpath for the Prism instance's NeoForge client: the libraries Prism's metadata lists
# for Minecraft 1.21.1 and NeoForge 21.1.262, plus NeoForge's patched client jar.
import json, os, sys
prism = os.path.expanduser('~/.local/share/PrismLauncher')
libs = os.path.join(prism, 'libraries')
def path(name):
    g, a, v, *c = name.split(':')
    if '@' in v: v = v.split('@')[0]
    cls = ('-' + c[0]) if c else ''
    return os.path.join(libs, *g.split('.'), a, v, f'{a}-{v}{cls}.jar')
out = []
for meta in ('net.minecraft/1.21.1.json', 'net.neoforged/21.1.262.json'):
    d = json.load(open(os.path.join(prism, 'meta', meta)))
    for lib in d.get('libraries', []):
        p = path(lib['name'])
        if os.path.exists(p): out.append(p)
out.append(path('net.neoforged:neoforge:21.1.262:client'))
out.append(path('net.neoforged:neoforge:21.1.262:universal'))
# Classes NeoForge doesn't patch are only in the Mojang-mapped vanilla client (named "-srg" for history's sake).
out.append(path('net.minecraft:client:1.21.1-20240808.144430:srg'))
print(':'.join(dict.fromkeys(out)))
