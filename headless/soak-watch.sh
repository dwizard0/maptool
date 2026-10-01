#!/bin/bash
# Record the health of the running headless server once a minute.
#
# Intended to be left running for hours or days. Writes one CSV row per sample
# to soak.csv and one line per *event* to soak-events.log, so a long quiet run
# stays small and anything odd is easy to find:
#
#   headless/soak-watch.sh &
#   grep -c . run/soak/soak.csv            # samples taken
#   cat run/soak/soak-events.log           # what went wrong, if anything
set -uo pipefail

UNIT="${SOAK_UNIT:-maptool-headless}"
OUT="${SOAK_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/run/soak}"
INTERVAL="${SOAK_INTERVAL:-60}"
SAVE_DIR="${SOAK_SAVE_DIR:-/home/freeman/maptool-headless/run/data/server}"

mkdir -p "$OUT"
CSV="$OUT/soak.csv"
LOG="$OUT/soak-events.log"

[[ -f "$CSV" ]] || echo "time,state,pid,restarts,rss_mb,threads,model_thread,connections,heap_used_mb,backups,save_age_s,errors_total" > "$CSV"

event() { echo "$(date -Is) $*" >> "$LOG"; }

last_restarts=""
last_errors=""

while true; do
  now=$(date -Is)
  state=$(systemctl is-active "$UNIT" 2>/dev/null)
  pid=$(systemctl show "$UNIT" -p MainPID --value 2>/dev/null)
  restarts=$(systemctl show "$UNIT" -p NRestarts --value 2>/dev/null)

  rss=""; threads=""; model=""; conns=""; heap=""
  if [[ "$pid" =~ ^[0-9]+$ ]] && [[ "$pid" != "0" ]] && [[ -d "/proc/$pid" ]]; then
    rss=$(awk '/VmRSS/ {printf "%.0f", $2/1024}' "/proc/$pid/status" 2>/dev/null)
    threads=$(awk '/Threads/ {print $2}' "/proc/$pid/status" 2>/dev/null)
    dump=$(timeout 20 jstack "$pid" 2>/dev/null)
    model=$(grep -c '"maptool-model"' <<< "$dump")
    conns=$(grep -c '"SocketConnection.ReceiveThread' <<< "$dump")
    # " garbage-first heap   total 289792K, used 189548K [0x...]"
    heap=$(timeout 20 jcmd "$pid" GC.heap_info 2>/dev/null \
      | awk '/garbage-first heap/ {for (i=1;i<NF;i++) if ($i == "used") {gsub(/[^0-9]/,"",$(i+1)); printf "%.0f", $(i+1)/1024; exit}}')
  fi

  backups=$(ls -1 "$SAVE_DIR"/*.bak 2>/dev/null | wc -l)
  save_age=""
  if [[ -f "$SAVE_DIR/campaign.cmpgn" ]]; then
    save_age=$(( $(date +%s) - $(stat -c %Y "$SAVE_DIR/campaign.cmpgn") ))
  fi
  errors=$(journalctl -u "$UNIT" --since "1 hour ago" --no-pager 2>/dev/null \
    | grep -icE "error|exception")

  echo "$now,$state,$pid,$restarts,$rss,$threads,$model,$conns,$heap,$backups,$save_age,$errors" >> "$CSV"

  # Events: only things worth a human's attention.
  [[ "$state" != "active" ]] && event "SERVICE $state (pid=$pid restarts=$restarts)"
  [[ -n "$last_restarts" && "$restarts" != "$last_restarts" ]] && event "RESTARTED: $last_restarts -> $restarts"
  [[ "$model" == "0" && "$state" == "active" ]] && event "NO MODEL THREAD (pid=$pid)"
  [[ "$model" =~ ^[0-9]+$ ]] && (( model > 1 )) && event "MULTIPLE MODEL THREADS: $model"
  [[ -n "$last_errors" && "$errors" =~ ^[0-9]+$ && "$last_errors" =~ ^[0-9]+$ ]] && (( errors > last_errors )) && \
    event "NEW LOG ERRORS: $last_errors -> $errors"
  [[ "$backups" =~ ^[0-9]+$ ]] && (( backups > 30 )) && event "BACKUPS NOT PRUNING: $backups"
  [[ -n "$save_age" ]] && (( save_age > 1800 )) && event "SAVE STALE: ${save_age}s old"

  last_restarts="$restarts"
  last_errors="$errors"
  sleep "$INTERVAL"
done
