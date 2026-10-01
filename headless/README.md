# Headless MapTool server

Runs a MapTool server with no window, no renderer and no AWT event thread, so
it can live on a box with no display and stay up between sessions.

Everything here is on the `headless` branch of this fork. The upstream-shaped
pieces live on their own branches; see the repository's `CLAUDE.md`.

## Requirements

- **Java 21.** Java 25 does not compile this tree. On this machine:
  `export JAVA_HOME=/usr/lib/jvm/bellsoft-java21-full-amd64`
- The client and server compare version strings **exactly**. Either rebuild
  both from the same commit, or pin the server with `-Dmaptool.version=...`.

## Build

```
export JAVA_HOME=/usr/lib/jvm/bellsoft-java21-full-amd64
./gradlew jar :clientserver:jar :common:jar :dicelib:jar :messages:jar headlessClasspath
```

`headlessClasspath` writes `build/headless-classpath.txt`, which the launcher
reads. The subproject jars must exist or startup fails with
`NoClassDefFoundError: net/rptools/clientserver/simple/connection/Connection`.

The launcher also passes `--add-opens` flags that XStream needs. Without
`java.base/java.util`, saving a campaign fails with "No converter available ...
Collections$SynchronizedMap". Keep that list in sync with `build.gradle`'s
`modulesToOpen`.

## Run

```
headless/maptool-server.sh
```

Configuration is entirely environmental.

| Variable | Default | Meaning |
|---|---|---|
| `MAPTOOL_PORT` | 51234 | listening port |
| `MAPTOOL_NAME` | MapTool Headless | server name |
| `MAPTOOL_GM_PASSWORD` | gm | GM password |
| `MAPTOOL_PLAYER_PASSWORD` | player | player password |
| `MAPTOOL_CAMPAIGN` | (none) | campaign to load; a basic campaign if unset |
| `MAPTOOL_SAVE` | `<apphome>/server/campaign.cmpgn` | where autosave writes |
| `MAPTOOL_AUTOSAVE_MINUTES` | 5 | autosave interval; 0 disables |
| `MAPTOOL_BACKUPS` | true | keep timestamped backups beside the save |
| `MAPTOOL_BACKUPS_TO_KEEP` | 24 | how many backups to retain; 0 or less keeps all |
| `MAPTOOL_REGISTER` | false | publish to the public registry |
| `MAPTOOL_HEAP` | 2G | JVM max heap |
| `MAPTOOL_JVM_OPTS` | (none) | extra JVM flags, e.g. `-Dmaptool.version=...` |
| `MAPTOOL_SKIP_VERSION_CHECK` | false | accept any client version (sets `RUN_FROM_IDE`) |

**Backups are capped.** Before that cap existed, a server saving every five
minutes accumulated 3,800 backups totalling 50GB. 24 is two hours of history at
the default interval.

### Server policy

A GUI server is configured through the start-server dialog, which this does not
have. Set any policy flag with `MAPTOOL_POLICY_<name>`; anything unset keeps
MapTool's own default.

```
MAPTOOL_POLICY_playersReceiveCampaignMacros=true
MAPTOOL_POLICY_useIndividualFOW=true
MAPTOOL_POLICY_movementMetric=ONE_TWO_ONE
```

Recognised names: `useStrictTokenManagement`, `movementLocked`,
`tokenEditorLocked`, `tokenContextLocked`, `playersCanRevealVision`,
`gmRevealsVisionForUnownedTokens`, `autoRevealOnMovement`, `useIndividualViews`,
`restrictedImpersonation`, `playersReceiveCampaignMacros`, `hiddenMapSelectUI`,
`useToolTipsForDefaultRollFormat`, `useIndividualFOW`, `includeOwnedNPCs`,
`usingAstarPathfinding`, `vblBlocksMove`, `disablePlayerAssetPanel`,
`movementMetric`.

A mistyped name logs a warning listing the valid ones; a bad `movementMetric`
logs an error listing `NO_DIAGONALS`, `MANHATTAN`, `ONE_TWO_ONE`,
`ONE_ONE_ONE`.

**Players do not see campaign macros** unless
`MAPTOOL_POLICY_playersReceiveCampaignMacros=true`. That is MapTool's default,
not a bug.

## Running under systemd

`headless/maptool-headless.service` is the unit. Install it with:

```
sudo cp headless/maptool-headless.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now maptool-headless
```

Passwords and policy settings belong in `/etc/maptool-headless.env`, mode 0600,
which the unit reads through `EnvironmentFile=`. They must not go in the unit
file, which lives in git.

### Applying a new build without root

The unit uses `Restart=always`, so:

```
./gradlew jar :clientserver:jar :common:jar :dicelib:jar :messages:jar headlessClasspath
kill $(systemctl show maptool-headless -p MainPID --value)
```

systemd restarts it about ten seconds later on the new jar. No sudo needed.

The flip side: a genuinely broken build restarts forever. Watch
`journalctl -u maptool-headless -f` after a restart, and `sudo systemctl stop`
if it loops.

### Other unit details worth knowing

- `SuccessExitStatus=143` -- the JVM exits 128+15 on SIGTERM, which would
  otherwise be recorded as a failure.
- `TimeoutStopSec=180` -- the final campaign save runs in a shutdown hook and a
  large campaign takes a while.
- `WorkingDirectory=/home/freeman/maptool-headless` -- the service runs from
  **this** working copy's `build/libs`. Building another branch here replaces
  the jar under the running JVM: classes load lazily, so the connection thread
  dies with `NoClassDefFoundError: ServerHandshake`, and a later restart fails
  with `Could not find or load main class ...HeadlessServer`. Build other
  branches in a git worktree.

## Operating notes

- **Logs**: `journalctl -u maptool-headless`. log4j2 also writes to
  `~/.maptool-rptools/logs`, rotated and zipped; it stays under a megabyte.
- **Saves**: `MAPTOOL_SAVE`, plus `.bak` copies capped by
  `MAPTOOL_BACKUPS_TO_KEEP`. The campaign is copied on the model thread and the
  copy is written off it, matching what `AutoSaveManager` does in the client.
- **A clean startup logs no stack traces.** If one appears, something regressed.
- `getTokenInitiative: index = -1` lines at ERROR level during startup are
  upstream noise: that is how its own code asks "is there a current token?".

## Known limitations

- **Macros run on clients, not here.** The server stores macro definitions and
  relays the results; it has no "run this" handler.
- **Chat identity and impersonation** still live in a Swing panel upstream, so
  anything server-side that would need them is not available. See
  `notes/session-state-design.md`.
- **No admin interface.** Everything is set at startup; there is no way to
  change policy or kick a player while it runs.
