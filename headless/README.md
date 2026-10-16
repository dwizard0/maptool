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

## Test

```
./gradlew headlessEndToEndTest
```

Starts real server processes, each in its own temporary home and data
directory (nothing touches your own MapTool data), and talks to them the way
MapTool clients do: logins and refusals, a campaign built over the wire reaching
connected and late-joining players, the final save on stop, and a restart from
that save. It takes about 15 seconds and is not part of `./gradlew test`. Each
test's server output and logs are kept in `build/headless-e2e/` for a look after
a failure.

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
| `MAPTOOL_PASSWORD_FILE` | false | per-player passwords instead of the two shared ones; see below |
| `MAPTOOL_CAMPAIGN` | (none) | campaign to load; a basic campaign if unset |
| `MAPTOOL_SAVE` | `<apphome>/server/campaign.cmpgn` | where autosave writes |
| `MAPTOOL_AUTOSAVE_MINUTES` | 5 | how often to check for changes and save; 0 disables |
| `MAPTOOL_IDLE_SAVE_MINUTES` | 60 | save at least this often even when nothing changed; 0 saves every interval |
| `MAPTOOL_BACKUPS` | true | keep timestamped backups beside the save, and a copy taken at startup |
| `MAPTOOL_BACKUPS_TO_KEEP` | 24 | how many of the newest backups to retain; 0 or less keeps all |
| `MAPTOOL_DAILY_BACKUPS` | 14 | also keep the newest backup from each of this many days |
| `MAPTOOL_REGISTER` | false | publish to the public registry |
| `MAPTOOL_HEAP` | 2G | JVM max heap |
| `MAPTOOL_JVM_OPTS` | (none) | extra JVM flags, e.g. `-Dmaptool.version=...` |
| `MAPTOOL_SKIP_VERSION_CHECK` | false | accept any client version (sets `RUN_FROM_IDE`) |

**Memory.** The server holds the whole campaign, including every add-on's
game data, and builds a copy of it for each client that joins. 2G is plenty
for most frameworks, but large ones need more: the Pathfinder 2e framework
(`ca.pz2e`) keeps its compendium as game data and used about 950MB on its
own, so with a few players joining at once it ran a 1G server out of memory.
Give a server running a framework that size `MAPTOOL_HEAP=4G`. If the log
shows `OutOfMemoryError`, raise it and restart.

**Saves and backups.** Every five minutes the server saves the campaign, but
only if a client has changed something since the last save; an idle server
still saves once an hour as a safety net, and always on shutdown. Each save
keeps the previous file as `<save>.<timestamp>.bak`. Retention keeps the 24
newest of those plus the newest from each of the last 14 days, so a mistake
noticed days later can still be undone. On startup the campaign file is also
copied to `<save>.<timestamp>.startup` (the newest 5 are kept).

Backups are capped on purpose: before the cap existed, a server saving every
five minutes accumulated 3,800 backups totalling 50GB.

To restore one, stop the server, copy the backup over the save file, and start
it again.

**Changing the campaign.** MapTool only offers *Open Campaign* to a client that
hosts the game or plays alone, so a GM connected to this server cannot load a
campaign from their own computer. Switch campaigns on the server instead:

1. Stop the server. It writes a final save on the way down.
2. Keep a copy of that save under another name if you want the current
   campaign back later; nothing else keeps its final state.
3. Copy the campaign you want over the save file (`MAPTOOL_SAVE`, which the
   service also loads through `MAPTOOL_CAMPAIGN`).
4. Start the server. Its startup copy is now of the new campaign.

*New Campaign* is different: any GM can use it while connected, and it replaces
the campaign on the server with a blank one. Recover from that with a backup as
above.

### Per-player passwords

By default everyone logs in with one of two shared passwords, GM or player, and
players are told apart only by the name they type, so anyone with the player
password can join under another player's name. Set `MAPTOOL_PASSWORD_FILE=true`
to give each player a password of their own instead. This is the same
per-player password file MapTool's own servers use when *individual passwords*
is ticked in the start-server dialog; the shared passwords are then not used.

The file is `config/passwords.json` in the data directory. A GUI host edits it
in its Player Database dialog; here, put new or changed players in
`config/passwords_add.json` beside it, with plain-text passwords:

```json
{"passwords": [
  {"username": "Dustin", "password": "choose-one", "role": "GM"},
  {"username": "Alice",  "password": "another-one", "role": "PLAYER"},
  {"username": "Mallory", "password": "x", "role": "PLAYER", "blocked": "Banned for cheating"}
]}
```

At startup the server merges that file into `passwords.json`, stores each
password hashed with its own salt, and deletes `passwords_add.json`, so no
plain-text password stays on disk. A name in the additions replaces the same
name in `passwords.json`. `blocked` refuses that player with the reason shown.
Changes take effect at the next restart.

The username is what the player types into *Connect to Server*, and the role
comes from the file: a player cannot become GM by knowing the GM's password.
If the option is set and neither file exists, the server refuses to start.

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
  `MAPTOOL_BACKUPS_TO_KEEP` and `MAPTOOL_DAILY_BACKUPS`. The campaign is copied on the model thread and the
  copy is written off it, matching what `AutoSaveManager` does in the client.
- **A clean startup logs no stack traces.** If one appears, something regressed.

## Add-on libraries

The server holds the campaign's add-on libraries: it hands them to each
connecting client during the handshake and writes them into every save, so a
framework imported once by the GM survives reconnects and restarts and reaches
players without them importing anything.

Upstream assumes the GM hosts the game in their own client, so a GM connected
to a dedicated server published nothing and the server's library list stayed
empty. Both halves of that are fixed on this branch; a self-hosted GUI game is
unaffected.

The server does **not** run an add-on's lifecycle events (first-time setup and
init). Those are client work -- they call things like `broadcast()` that need a
chat panel. The "needs initialization" flag is left alone, so the first real
client still runs first-time setup.

## Game data

Macro and JS game data -- what `data.setData` writes and what an add-on's
`library.setData` keeps -- lives in a process-wide store rather than in the
campaign, exactly like the add-on libraries above, and had the same problem:
the server relayed the updates to the other clients but never applied them to
its own store. Live play looked right while every save dropped the data and
every client joining later received an empty store.

The server now applies those updates before relaying them. Two details are
worth knowing if you are reading that code:

- An asset-valued property is sent to the server as an asset reference, and
  resolving one waits indefinitely for an asset the server may never have been
  given. The sending client now pushes the asset first, and the server applies
  game data on its own thread (`server-game-data`) so a value whose asset never
  arrives cannot stall message handling.
- Setting a property needs its namespace to exist. Clients create add-on
  namespaces while initializing the add-on, which this server deliberately does
  not do, so it creates a missing namespace itself.

## Known limitations

- **Macros run on clients, not here.** The server stores macro definitions and
  relays the results; it has no "run this" handler.
- **Chat identity and impersonation** still live in a Swing panel upstream, so
  anything server-side that would need them is not available. See
  `notes/session-state-design.md`.
- **No admin interface.** Everything is set at startup; there is no way to
  change policy or kick a player while it runs.
