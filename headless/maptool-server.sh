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
CAMPAIGN="${MAPTOOL_CAMPAIGN:-}"
SAVE="${MAPTOOL_SAVE:-}"
AUTOSAVE_MINUTES="${MAPTOOL_AUTOSAVE_MINUTES:-5}"
BACKUPS="${MAPTOOL_BACKUPS:-true}"
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
  "-Dmaptool.autosaveMinutes=$AUTOSAVE_MINUTES"
  "-Dmaptool.backups=$BACKUPS"
  "-Dmaptool.register=$REGISTER"
  "-Dmaptool.name=$SERVER_NAME"
)

[[ -n "$CAMPAIGN" ]] && PROPS+=("-Dmaptool.campaign=$CAMPAIGN")
[[ -n "$SAVE" ]] && PROPS+=("-Dmaptool.save=$SAVE")

# A 1.18.6 client cannot connect to a develop build without this: ServerHandshake
# rejects any version string that is not an exact match unless isDevelopment().
[[ "${MAPTOOL_SKIP_VERSION_CHECK:-false}" == "true" ]] && PROPS+=(-DRUN_FROM_IDE=1)

cd "$HERE"
exec "$JAVA_BIN" \
  -Xss8M "-Xmx$HEAP" \
  "${OPENS[@]}" \
  "${PROPS[@]}" \
  -cp "$(cat "$CP_FILE")" \
  net.rptools.maptool.server.HeadlessServer
