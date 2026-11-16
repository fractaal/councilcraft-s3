#!/usr/bin/env bash
# Pre-flight for a pack push: does what each player's game will do on its next launch, here first.
#
#   packcheck.sh prepare         copies Ben's real instance, serves the pack repo's HEAD commit on localhost and runs the
#                                pack installer against it, exactly as the instance's pre-launch command does
#   packcheck.sh launch          starts that copy in its own headless gamescope and joins the lab server as "packcheck"
#   packcheck.sh shot <out.png>  saves the frame the game is showing
#   packcheck.sh key <key> ...   presses keys in the game
#   packcheck.sh errors          client-log lines worth reading
#   packcheck.sh stop | status
#
# PACKCHECK_JVM="<flags>" adds JVM flags to a launch. PACKCHECK_SOUND=<file.wav> writes what the player would hear to
# that file (OpenAL's "wave" driver: 48 kHz stereo float, still no sound device) with the master volume at full; the
# file is complete once the game has stopped.
#
# Nothing here touches the real instance (read only), the live server, or the wall rig's folders and processes.
set -u
HOME_DIR=/home/benjude/exosphere-aero-lab/packcheck          # on disk: /tmp is RAM-backed on this machine
GAME_DIR=$HOME_DIR/minecraft
SERVED=$HOME_DIR/served
RUN=$HOME_DIR/run
PACK=/home/benjude/exosphere-aero-pack
PRISM=/home/benjude/.local/share/PrismLauncher
SRC=$PRISM/instances/exosphere-aero-voxy/minecraft          # Ben's packwiz-managed instance, copied from, never run
LAB=/home/benjude/exosphere-aero-lab/lootcheck               # the full-pack lab server, port 25566
LAB_PORT=25566
HTTP_PORT=18747
JAVA=/usr/lib/jvm/java-21-openjdk/bin/java
NAME=packcheck
WIN_W=1280; WIN_H=720
RIG=/home/benjude/CodeProjects/MinecraftMods/Toms-Peripherals/councilcraft/rig   # only xkey.py is used from it
GAME_PY=$PACK/tools/gamebridge-mod/cli/game.py
# The instance's own JVM settings (instance.cfg: JvmArgs, MinMemAlloc, MaxMemAlloc).
JVM="-Xms6144m -Xmx8192m -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -XX:+ParallelRefProcEnabled -Xlog:gc,safepoint:file=logs/gc-client.log:time,uptime:filecount=2,filesize=20m -XX:SymbolTableSize=4194304 -XX:MetaspaceSize=1g"

game() { python3 -I "$GAME_PY" --server "$LAB" "$@"; }

our_pid() {   # FILE DIR NAME: the PID in FILE, only if that process is alive, is called NAME and runs in DIR
    local pid
    pid=$(cat "$1" 2>/dev/null) || return 1
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "$3" ] || return 1
    [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$2" ] || return 1
    echo "$pid"
}
stop_pid() {
    local pid=$1 i
    kill -TERM "$pid" 2>/dev/null
    for ((i = 0; i < $2 * 5; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.2; done
    kill -KILL "$pid" 2>/dev/null
}
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

classpath() {   # the launcher's library list for Minecraft 1.21.1 + NeoForge 21.1.262, as Prism would build it
    python3 -I - "$PRISM" <<'EOF'
import json, os, sys
prism = sys.argv[1]
def path(name):
    g, a, v, *c = name.split(':')
    return os.path.join(prism, 'libraries', *g.split('.'), a, v, f"{a}-{v}{'-' + c[0] if c else ''}.jar")
def allowed(lib):
    rules = lib.get('rules')
    return not rules or any(r['action'] == 'allow' and r.get('os', {}).get('name', 'linux') == 'linux' for r in rules)
out = []
for meta in ('org.lwjgl3/3.3.3.json', 'net.minecraft/1.21.1.json', 'net.neoforged/21.1.262.json'):
    d = json.load(open(os.path.join(prism, 'meta', meta)))
    out += [path(l['name']) for l in d['libraries'] if allowed(l)]
out.append(path('com.mojang:minecraft:1.21.1:client'))
missing = [p for p in out if not os.path.exists(p)]
if missing: sys.exit('packcheck: launcher libraries missing:\n  ' + '\n  '.join(missing))
print(':'.join(dict.fromkeys(out)))
EOF
}

stop() {
    local j g
    j=$(our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java)
    g=$(our_pid "$GAME_DIR/gamescope.pid" "$GAME_DIR" gamescope-wl)
    [ -n "$j" ] && stop_pid "$j" 20
    if [ -n "$g" ]; then
        for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$g" 2>/dev/null || break; sleep 0.2; done
        kill -0 "$g" 2>/dev/null && stop_pid "$g" 5
    fi
    rm -f "$GAME_DIR/client.pid" "$GAME_DIR/gamescope.pid" "$GAME_DIR/display"
    [ -n "${j:-}${g:-}" ] && echo "packcheck: stopped (game pid ${j:--}, gamescope pid ${g:--})" || echo "packcheck: not running"
}

stop_http() {
    local h
    h=$(our_pid "$HOME_DIR/http.pid" "$SERVED" python3) && stop_pid "$h" 3
    rm -f "$HOME_DIR/http.pid"
}

prepare() {
    local e head before after
    our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java >/dev/null && { echo "packcheck: the game is running; stop it first" >&2; exit 1; }
    [ -f "$SRC/packwiz.json" ] || { echo "packcheck: $SRC is not a packwiz-managed instance" >&2; exit 1; }
    git -C "$PACK" diff --quiet HEAD -- || { echo "packcheck: the pack repo has uncommitted changes; HEAD is what gets pushed, commit first" >&2; exit 1; }
    head=$(git -C "$PACK" rev-parse --short HEAD)
    stop_http
    rm -rf /home/benjude/exosphere-aero-lab/packcheck/minecraft /home/benjude/exosphere-aero-lab/packcheck/served /home/benjude/exosphere-aero-lab/packcheck/run
    mkdir -p "$GAME_DIR" "$SERVED"
    # The copy shares its data with the original on disk (btrfs), so it is quick and costs no space. Left out: worlds,
    # maps, distant-terrain storage and old logs, which are large and have nothing to do with whether the pack loads.
    for e in $(ls -A "$SRC"); do
        case $e in saves|journeymap|.voxy|logs|crash-reports|screenshots|Distant_Horizons_server_data|skyislands-cache|fractals_peripherals) continue ;; esac
        cp -a --reflink=auto "$SRC/$e" "$GAME_DIR/"
    done
    mkdir -p "$GAME_DIR/logs"       # the JVM's -Xlog file needs the folder
    ls "$GAME_DIR/mods" | sort > "$HOME_DIR/mods-before.txt"
    echo "packcheck: copied the instance ($(wc -l < "$HOME_DIR/mods-before.txt") files in mods/)"

    # Serve exactly the commit a push would publish: a plain file server over an export of HEAD, nothing refreshed.
    git -C "$PACK" archive HEAD | tar -x -C "$SERVED"
    ( cd "$SERVED" && setsid nohup python3 -I -m http.server "$HTTP_PORT" --bind 127.0.0.1 > "$HOME_DIR/http.out" 2>&1 < /dev/null & echo $! > "$HOME_DIR/http.pid" )
    for _ in $(seq 50); do port_open "$HTTP_PORT" && break; sleep 0.1; done
    port_open "$HTTP_PORT" || { echo "packcheck: the local file server didn't start, see $HOME_DIR/http.out" >&2; exit 1; }

    # The instance's pre-launch command, with the address swapped for the local one and -g so no window opens.
    echo "packcheck: running the pack installer against commit $head (pack version $(sed -n 's/^version = //p' "$SERVED/pack.toml"))"
    ( cd "$GAME_DIR" && env -u DISPLAY -u WAYLAND_DISPLAY "$JAVA" -jar packwiz-installer-bootstrap.jar -g "http://127.0.0.1:$HTTP_PORT/pack.toml" ) > "$HOME_DIR/installer.out" 2>&1
    e=$?
    stop_http
    ls "$GAME_DIR/mods" | sort > "$HOME_DIR/mods-after.txt"
    echo "packcheck: installer exit status $e; last lines of $HOME_DIR/installer.out:"; tail -n 6 "$HOME_DIR/installer.out" | cut -c1-240 | sed 's/^/    /'
    echo "packcheck: mods/ changed by the update:"; diff "$HOME_DIR/mods-before.txt" "$HOME_DIR/mods-after.txt" | grep '^[<>]' | sed 's/^</    removed/; s/^>/    added  /'
    [ $e -eq 0 ] || { echo "packcheck: THE INSTALLER FAILED. A player's launch would fail the same way." >&2; exit 1; }
    echo "$head" > "$HOME_DIR/prepared-commit"
}

launch() {
    local libs t0 i
    [ -f "$HOME_DIR/prepared-commit" ] || { echo "packcheck: run prepare first" >&2; exit 1; }
    port_open "$LAB_PORT" || { echo "packcheck: the lab server isn't up on $LAB_PORT" >&2; exit 1; }
    { our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java || our_pid "$GAME_DIR/gamescope.pid" "$GAME_DIR" gamescope-wl; } >/dev/null && stop
    libs=$(classpath) || exit 1
    mkdir -p "$GAME_DIR"/{logs,tmp}
    rm -rf /home/benjude/exosphere-aero-lab/packcheck/run; mkdir -m 700 "$RUN"
    # The player's own options, except what would stop an unattended game: it must keep running without focus.
    for kv in pauseOnLostFocus:false fullscreen:false "overrideWidth:$WIN_W" "overrideHeight:$WIN_H" skipMultiplayerWarning:true onboardAccessibility:false narrator:0; do
        if grep -q "^${kv%%:*}:" "$GAME_DIR/options.txt"; then sed -i "s|^${kv%%:*}:.*|$kv|" "$GAME_DIR/options.txt"; else echo "$kv" >> "$GAME_DIR/options.txt"; fi
    done
    local drivers=null
    if [ -n "${PACKCHECK_SOUND:-}" ]; then
        drivers=wave
        # Simple Voice Chat opens a sound device of its own when the game joins a server. With the wave driver both
        # devices write to the one configured file name, and the later one starts again at the top of the file, writing
        # silence over what the game recorded: all that survived of two runs was the last 40 seconds. So the name is a
        # link. It points at the wanted file while the game's own sound system opens it, and is moved to a throwaway
        # file as soon as the log says that has happened, which is before the game joins.
        rm -f "$PACKCHECK_SOUND"
        ln -sfn "$PACKCHECK_SOUND" "$GAME_DIR/heard-link.wav"
        printf '[general]\nchannels = stereo\nfrequency = 48000\nhrtf = false\n[wave]\nfile = %s\n' "$GAME_DIR/heard-link.wav" > "$GAME_DIR/alsoft.conf"
        sed -i 's|^soundCategory_master:.*|soundCategory_master:1.0|' "$GAME_DIR/options.txt"
    fi
    cat > "$GAME_DIR/inner.sh" <<EOF
#!/usr/bin/env bash
# Runs inside gamescope. Written by packcheck.sh on every launch.
cd "$GAME_DIR" || exit 1
echo "\$DISPLAY" > display
echo \$\$ > client.pid
exec nice -n 10 "$JAVA" $JVM ${PACKCHECK_JVM:-} -Duser.language=en "-Djava.io.tmpdir=$GAME_DIR/tmp" -cp "$libs" \\
    io.github.zekerzhayard.forgewrapper.installer.Main \\
    --username $NAME --version 1.21.1 --gameDir "$GAME_DIR" --assetsDir "$PRISM/assets" --assetIndex 17 \\
    --uuid 00000000000000000000000000000000 --accessToken 0 --userType legacy --versionType release \\
    --fml.neoForgeVersion 21.1.262 --fml.fmlVersion 4.0.45 --fml.mcVersion 1.21.1 --fml.neoFormVersion 20240808.144430 \\
    --launchTarget forgeclient --width $WIN_W --height $WIN_H --quickPlayMultiplayer 127.0.0.1:$LAB_PORT > client.out 2>&1
EOF
    chmod +x "$GAME_DIR/inner.sh"
    cd "$GAME_DIR" || exit 1
    : > logs/latest.log         # so nothing below reads the last run's lines
    t0=$(date +%s)
    # Nothing from the desktop session is passed in: no display, no session bus, a private runtime folder, and OpenAL's
    # "null" driver, so the game can't open a window, a notification or a sound device there.
    (
        ulimit -c 0
        setsid nohup env -u WAYLAND_DISPLAY -u DISPLAY -u DBUS_SESSION_BUS_ADDRESS XDG_RUNTIME_DIR="$RUN" ALSOFT_DRIVERS=$drivers ALSOFT_CONF="$GAME_DIR/alsoft.conf" \
            bash -c 'echo $$ > gamescope.pid; exec gamescope --backend headless -W "$1" -H "$2" -r 60 -- "$3"' \
            _ "$WIN_W" "$WIN_H" "$GAME_DIR/inner.sh" > gamescope.out 2>&1 < /dev/null &
    )
    echo "packcheck: started, waiting for $NAME to join the lab server (the full pack takes minutes to load)"
    local relinked=
    for ((i = 0; i < 900; i++)); do
        if [ -n "${PACKCHECK_SOUND:-}" ] && [ -z "$relinked" ] && grep -q 'Sound engine started' logs/latest.log; then
            ln -sfn "$GAME_DIR/tmp/second-sound-device.wav" "$GAME_DIR/heard-link.wav"; relinked=1
        fi
        game players 2>/dev/null | grep -q "$NAME" && break
        if [ "$i" -gt 10 ] && ! our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java >/dev/null; then
            echo "packcheck: THE GAME EXITED BEFORE JOINING after $(( $(date +%s) - t0 )) s. Last lines of $GAME_DIR/client.out:" >&2
            tail -n 25 client.out | cut -c1-300 >&2
            ls -t crash-reports 2>/dev/null | head -2 >&2
            stop >/dev/null; exit 1
        fi
        sleep 1
    done
    game players 2>/dev/null | grep -q "$NAME" || { echo "packcheck: $NAME hasn't joined after 15 minutes; the game is left running, see $GAME_DIR/logs/latest.log" >&2; exit 1; }
    echo "packcheck: $NAME is in the lab world after $(( $(date +%s) - t0 )) s (game pid $(cat client.pid), X display $(cat display))"
}

shot() {
    local out sock tmp i
    out=$(readlink -f "${1:?usage: packcheck.sh shot <out.png>}")
    our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java >/dev/null || { echo "packcheck: the game isn't running" >&2; exit 1; }
    sock=$(cd "$RUN" && ls -d gamescope-[0-9] 2>/dev/null | head -n 1)
    [ -n "$sock" ] || { echo "packcheck: no gamescope socket in $RUN" >&2; exit 1; }
    tmp=$GAME_DIR/tmp/shot-$$.png
    rm -f "$tmp"
    XDG_RUNTIME_DIR=$RUN GAMESCOPE_WAYLAND_DISPLAY=$sock gamescopectl screenshot "$tmp" >/dev/null 2>&1
    for ((i = 0; i < 50; i++)); do
        if [ -s "$tmp" ] && [ "$(tail -c 8 "$tmp" | od -An -tx1 | tr -d ' \n')" = 49454e44ae426082 ]; then
            mv -f "$tmp" "$out"; echo "$out"; return 0
        fi
        sleep 0.1
    done
    rm -f "$tmp"; echo "packcheck: gamescope wrote no picture within 5 s" >&2; exit 1
}

errors() {
    local log=$GAME_DIR/logs/latest.log
    echo "mod list line:"; grep -m1 -i "Fractal's Peripherals" "$log" | cut -c1-160
    echo "lines from the mod or naming it at ERROR or worse: $(grep -E '/(ERROR|FATAL)\]' "$log" | grep -c -i -E 'fractals_peripherals|com\.tom\.peripherals')"
    grep -E '/(ERROR|FATAL)\]' "$log" | grep -i -E 'fractals_peripherals|com\.tom\.peripherals' | head -10 | cut -c1-300
    echo "stack frames from the mod anywhere in the log: $(grep -c -E '^\s+at com\.tom\.peripherals' "$log")"
    echo "the known Voxy lock error (not this mod): $(grep -c 'Section freed while marked as dirty' "$log")"
    echo "crash reports: $(ls "$GAME_DIR/crash-reports" 2>/dev/null | wc -l)"
    echo "ERROR/FATAL lines in all: $(grep -c -E '/(ERROR|FATAL)\]' "$log")"
}

case ${1:-} in
    prepare) prepare ;;
    launch) launch ;;
    shot) shot "${2:-}" ;;
    key) our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java >/dev/null || { echo "packcheck: not running" >&2; exit 1; }
         shift; python3 -I "$RIG/xkey.py" "$(cat "$GAME_DIR/display")" "$@" ;;
    errors) errors ;;
    stop) stop ;;
    status) if pid=$(our_pid "$GAME_DIR/client.pid" "$GAME_DIR" java); then echo "packcheck: running (game pid $pid, commit $(cat "$HOME_DIR/prepared-commit"))"; else echo "packcheck: not running"; fi ;;
    *) echo "usage: packcheck.sh prepare | launch | shot <out.png> | key <key> ... | errors | stop | status" >&2; exit 1 ;;
esac
