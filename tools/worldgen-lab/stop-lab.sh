#!/bin/bash
# Stops the running worldgen lab server (a normal shutdown: the test world is saved).
PIDS=$(pgrep -f "java.*worldgenlab.settings=")
[ -z "$PIDS" ] && { echo "No worldgen lab is running."; exit 0; }
kill $PIDS
for p in $PIDS; do timeout 120 tail --pid=$p -f /dev/null; done
echo "Worldgen lab stopped."
