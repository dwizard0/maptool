#!/bin/bash
# Launch a headless MapTool server.
#
# The JVM needs more than a classpath: build.gradle's `modulesToOpen` list is
# fed to applicationDefaultJvmArgs and to jpackage, and without those --add-opens
# flags XStream cannot serialize a campaign (saves fail with "No converter
# available ... Collections$SynchronizedMap"). Keep this list in sync with
# build.gradle. The javafx.web entries are omitted -- a headless server has no
# WebView.
#
# Configuration comes from the environment; see the defaults below.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_FILE="${MAPTOOL_CP_FILE:-$HERE/build/headless-classpath.txt}"

JAVA_BIN="${JAVA_BIN:-java}"
PORT="${MAPTOOL_PORT:-51234}"
GM_PASSWORD="${MAPTOOL_GM_PASSWORD:-gm}"
PLAYER_PASSWORD="${MAPTOOL_PLAYER_PASSWORD:-player}"
PASSWORD_FILE="${MAPTOOL_PASSWORD_FILE:-false}"
CAMPAIGN="${MAPTOOL_CAMPAIGN:-}"
SAVE="${MAPTOOL_SAVE:-}"
AUTOSAVE_MINUTES="${MAPTOOL_AUTOSAVE_MINUTES:-5}"
BACKUPS="${MAPTOOL_BACKUPS:-true}"
BACKUPS_TO_KEEP="${MAPTOOL_BACKUPS_TO_KEEP:-24}"
DAILY_BACKUPS="${MAPTOOL_DAILY_BACKUPS:-14}"
IDLE_SAVE_MINUTES="${MAPTOOL_IDLE_SAVE_MINUTES:-60}"
REGISTER="${MAPTOOL_REGISTER:-false}"
SERVER_NAME="${MAPTOOL_NAME:-MapTool Headless}"
HEAP="${MAPTOOL_HEAP:-2G}"

if [[ ! -f "$CP_FILE" ]]; then
  echo "No classpath file at $CP_FILE" >&2
  echo "Run: ./gradlew headlessClasspath" >&2
  exit 1
fi

OPENS=(
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.desktop/java.awt=ALL-UNNAMED
  --add-opens=java.desktop/java.awt.geom=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt.geom=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.java2d=ALL-UNNAMED
  --add-opens=java.desktop/javax.swing=ALL-UNNAMED
)

PROPS=(
  -Djava.awt.headless=true
  "-Dmaptool.port=$PORT"
  "-Dmaptool.gmPassword=$GM_PASSWORD"
  "-Dmaptool.playerPassword=$PLAYER_PASSWORD"
  "-Dmaptool.passwordFile=$PASSWORD_FILE"
  "-Dmaptool.autosaveMinutes=$AUTOSAVE_MINUTES"
  "-Dmaptool.backups=$BACKUPS"
  "-Dmaptool.backupsToKeep=$BACKUPS_TO_KEEP"
  "-Dmaptool.dailyBackups=$DAILY_BACKUPS"
  "-Dmaptool.idleSaveMinutes=$IDLE_SAVE_MINUTES"
  "-Dmaptool.register=$REGISTER"
  "-Dmaptool.name=$SERVER_NAME"
)

[[ -n "$CAMPAIGN" ]] && PROPS+=("-Dmaptool.campaign=$CAMPAIGN")

# Server policy. A GUI server gets these from the start-server dialog; set them
# here instead. Anything left unset keeps MapTool's own default.
#   MAPTOOL_POLICY_playersReceiveCampaignMacros=true
#   MAPTOOL_POLICY_useIndividualFOW=true
#   MAPTOOL_POLICY_movementMetric=ONE_TWO_ONE
while IFS='=' read -r name value; do
  [[ -z "$name" ]] && continue
  PROPS+=("-Dmaptool.policy.${name#MAPTOOL_POLICY_}=$value")
done < <(env | grep '^MAPTOOL_POLICY_' | sort)

[[ -n "$SAVE" ]] && PROPS+=("-Dmaptool.save=$SAVE")

# A 1.18.6 client cannot connect to a develop build without this: ServerHandshake
# rejects any version string that is not an exact match unless isDevelopment().
[[ "${MAPTOOL_SKIP_VERSION_CHECK:-false}" == "true" ]] && PROPS+=(-DRUN_FROM_IDE=1)

# Run from a snapshot of this project's own build output rather than from the
# build directory itself. The JVM loads classes lazily, so rebuilding while a
# server is running swaps the code out from under it: the server keeps going on
# whatever it has already loaded and then dies on the first class it has not
# needed yet, typically when somebody connects. Copying costs a second at
# startup and makes a build safe to run at any time. Entries outside this
# project are immutable artifacts from the Gradle cache, so they are used where
# they lie. Set MAPTOOL_SNAPSHOT_CLASSPATH=false to run straight from build/.
RUNTIME_LIB="${MAPTOOL_RUNTIME_LIB:-$HERE/run/lib}"
# $HERE can be a path through a symlink while the classpath file records where
# the files really are, so both spellings have to be recognised as local.
HERE_REAL="$(cd "$HERE" && pwd -P)"
if [[ "${MAPTOOL_SNAPSHOT_CLASSPATH:-true}" == "true" ]]; then
  rm -rf "$RUNTIME_LIB"
  mkdir -p "$RUNTIME_LIB"
  CP=""
  n=0
  while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    if [[ "$entry" == "$HERE"/* || "$entry" == "$HERE_REAL"/* ]]; then
      n=$((n + 1))
      dest="$RUNTIME_LIB/$(printf '%03d' "$n")-$(basename "$entry")"
      cp -a "$entry" "$dest"
      entry="$dest"
    fi
    CP="${CP:+$CP:}$entry"
  done < <(tr ':' '\n' < "$CP_FILE")
  echo "Running from a classpath snapshot in $RUNTIME_LIB ($n local entries)" >&2
else
  CP="$(cat "$CP_FILE")"
fi

cd "$HERE"
exec "$JAVA_BIN" \
  -Xss8M "-Xmx$HEAP" ${MAPTOOL_JVM_OPTS:-} \
  "${OPENS[@]}" \
  "${PROPS[@]}" \
  -cp "$CP" \
  net.rptools.maptool.server.HeadlessServer
