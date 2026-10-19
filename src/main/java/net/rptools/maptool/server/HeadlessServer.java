/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <https://www.gnu.org/licenses/> and specifically the Affero license
 * text at <https://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.server;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.rptools.lib.net.RPTURLStreamHandlerFactory;
import net.rptools.maptool.client.AppSetup;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetURLStreamHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.walker.WalkerMetric;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
import net.rptools.maptool.model.library.LibraryManager;
import net.rptools.maptool.model.library.url.LibraryURLStreamHandler;
import net.rptools.maptool.model.player.DefaultPlayerDatabase;
import net.rptools.maptool.model.player.PasswordFilePlayerDatabase;
import net.rptools.maptool.model.player.Player;
import net.rptools.maptool.model.player.PlayerDatabaseFactory;
import net.rptools.maptool.model.player.ServerSidePlayerDatabase;
import net.rptools.maptool.util.PersistenceUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Minimal headless entry point for a MapTool server.
 *
 * <p>Starts a {@link MapToolServer} with no Swing frame, no renderer and no event dispatch thread.
 * Configuration comes from system properties so that no command-line parser is needed yet:
 *
 * <ul>
 *   <li>{@code maptool.port} (default 51234)
 *   <li>{@code maptool.name} (default "MapTool Headless")
 *   <li>{@code maptool.gmPassword} (default "gm")
 *   <li>{@code maptool.playerPassword} (default "player")
 *   <li>{@code maptool.campaign} path to a .cmpgn file; a basic campaign is used if absent
 * </ul>
 */
public class HeadlessServer {
  private static final String POLICY_PREFIX = "maptool.policy.";

  public static void main(String[] args) {
    try {
      run(args);
    } catch (Throwable t) {
      // A failed start must end the process. Threads started on the way keep the JVM alive after
      // main() throws, and a server process that is running but not listening looks healthy to
      // the service manager, which would then neither restart it nor report it failed.
      LogManager.getLogger(HeadlessServer.class).fatal("The headless server could not start", t);
      LogManager.shutdown();
      System.exit(1);
    }
  }

  private static void run(String[] args) throws Exception {
    // Before anything reads a maptool.* property: a settings file named with -headless:<file>
    // supplies them. A bad file is reported only once logging is set up: a server started from
    // MapTool's own launcher on Windows has no console, so the log is the only place the
    // error can be seen.
    Exception settingsError = null;
    try {
      HeadlessSettings.applyFromArguments(args);
    } catch (IOException | IllegalArgumentException e) {
      settingsError = e;
    }
    System.setProperty("java.awt.headless", "true");
    // Log4j normally stops itself from a shutdown hook of its own. Shutdown hooks run in no set
    // order, so that could happen before the hook below had written the final save, and the save's
    // messages -- including any error from it -- were lost. The hook below stops Log4j instead,
    // once the save is done. Like the logging setup, this must happen before the first Logger.
    System.setProperty("log4j2.shutdownHookEnabled", "false");
    // Must happen before any Logger is created, or log4j2.xml resolves ${sys:MAPTOOL_LOGDIR}
    // to a directory literally named that.
    AppUtil.initLogging();
    Logger log = LogManager.getLogger(HeadlessServer.class);
    if (settingsError != null) {
      throw settingsError;
    }
    for (String note : HeadlessSettings.notes()) {
      log.info(note);
    }
    MapTool.setHeadless(true);

    // MapTool.main() reads the version from the jar manifest; a headless entry point never runs
    // it, and running from build/classes has no manifest at all. ServerHandshake compares version
    // strings exactly, so without this the server reports DEVELOPMENT and rejects every client.
    String manifestVersion = MapTool.class.getPackage().getImplementationVersion();
    String versionOverride = System.getProperty("maptool.version");
    if (versionOverride != null) {
      MapTool.setVersion(versionOverride);
    } else if (manifestVersion != null) {
      MapTool.setVersion(manifestVersion.trim());
    }

    // Same startup steps MapTool.initialize() does, minus everything that builds a UI.
    ImageIO.setUseCache(false);

    // asset:// and lib:// are needed by built-in libraries and stat sheets. MapTool.main()
    // registers these before initialize(); nothing else does.
    RPTURLStreamHandlerFactory factory = new RPTURLStreamHandlerFactory();
    factory.registerProtocol("asset", new AssetURLStreamHandler());
    factory.registerProtocol("lib", new LibraryURLStreamHandler());
    URL.setURLStreamHandlerFactory(factory);

    AppSetup.install();

    int port = Integer.getInteger("maptool.port", ServerConfig.DEFAULT_PORT);
    // A non-null serverName sets ServerConfig.isServerRegistered(), which publishes this server
    // to the public RPTools registry. Make that opt-in.
    boolean register = Boolean.getBoolean("maptool.register");
    String serverName = register ? System.getProperty("maptool.name", "MapTool Headless") : null;
    String gmPassword = System.getProperty("maptool.gmPassword", "gm");
    String playerPassword = System.getProperty("maptool.playerPassword", "player");
    String campaignPath = System.getProperty("maptool.campaign", "");

    Campaign campaign;
    if (campaignPath.isEmpty()) {
      campaign = CampaignFactory.createBasicCampaign();
      log.info("No campaign specified, starting with a basic campaign");
    } else {
      campaign = PersistenceUtil.loadCampaign(new File(campaignPath)).campaign;
      log.info("Loaded campaign from {}", campaignPath);
    }

    ServerPolicy policy = buildPolicy();
    // Model code -- per-token fog, the movement metric -- reads the policy through
    // MapTool.getServerPolicy(), which asks the background client. Give that client the policy this
    // server enforces, not the default one it was created with.
    MapTool.getClient().setServerPolicy(policy);
    ServerConfig config =
        new ServerConfig("gm", gmPassword, playerPassword, port, serverName, null, false, false);
    ServerSidePlayerDatabase playerDb =
        Boolean.getBoolean("maptool.passwordFile")
            ? passwordFileDatabase(log)
            : new DefaultPlayerDatabase(playerPassword, gmPassword);

    // The copy below reaches back through MapTool.getCampaign() while it runs: a Zone's
    // InitiativeList holds its zone in a transient field, so after loading from disk it resolves
    // the zone by id against the *client's* campaign. Point the client at the loaded campaign
    // first, or copying a campaign whose maps have initiative entries dies with a
    // NullPointerException from InitiativeList.getZone().
    MapTool.getClient().setCampaign(campaign);

    MapToolServer server =
        new MapToolServer(null, new Campaign(campaign), config, false, policy, playerDb);

    // Model code resolves tokens and libraries through MapTool.getCampaign(), which reads the
    // static client, not the server. The server took a copy above, so re-point the client at that
    // copy: otherwise LibraryToken.findLibrary() and any macro touching a lib:token would look at
    // the pre-copy campaign and see tokens the server does not have.
    MapTool.getClient().setCampaign(server.getCampaign());

    // Deferred until the campaign is in place: LibraryTokenManager.init() scans
    // MapTool.getCampaign() for lib: tokens and only then subscribes to token events. Called
    // earlier, load-time events fired against an empty campaign and every lib:token failed.
    LibraryManager.init();

    File saveTarget =
        new File(
            System.getProperty(
                "maptool.save",
                new File(AppUtil.getAppHome("server"), "campaign.cmpgn").getPath()));
    CampaignPersister persister =
        new CampaignPersister(
            server,
            saveTarget,
            Integer.getInteger("maptool.autosaveMinutes", 5),
            !"false".equals(System.getProperty("maptool.backups")),
            Integer.getInteger("maptool.backupsToKeep", 24),
            Integer.getInteger("maptool.dailyBackups", 14),
            Integer.getInteger("maptool.idleSaveMinutes", 60));
    persister.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Stopping headless server");
                  persister.shutdown();
                  server.stop();
                  log.info("Headless server stopped");
                  LogManager.shutdown();
                }));

    server.start();
    log.info("Headless MapTool server listening on port {}", port);
    log.info("Reporting version: {}", MapTool.getVersion());
    log.info("Campaign has {} zone(s)", campaign.getZones().size());

    new CountDownLatch(1).await();
  }

  private HeadlessServer() {}

  /**
   * The per-player password database MapTool's own servers use when the start-server dialog's
   * individual passwords option is ticked: {@code passwords.json} in the {@code config} folder of
   * the data directory, one entry per player with their own password and role. The shared GM and
   * player passwords are then not used at all.
   *
   * <p>A GUI host manages that file through its Player Database dialog. Here it is edited by hand,
   * or new entries are put in {@code passwords_add.json} beside it: at startup MapTool merges that
   * file in, hashes any plain-text password, rewrites {@code passwords.json} and deletes the
   * additions file.
   *
   * @param log where to report what was loaded
   * @return the database, read and ready
   * @throws IllegalStateException if neither file exists, since then nobody could log in
   */
  private static ServerSidePlayerDatabase passwordFileDatabase(Logger log) throws Exception {
    Path config = AppUtil.getAppHome("config").toPath();
    Path passwords = config.resolve("passwords.json");
    Path additions = config.resolve("passwords_add.json");
    if (!Files.exists(passwords) && !Files.exists(additions)) {
      throw new IllegalStateException(
          "maptool.passwordFile is set but neither "
              + passwords
              + " nor "
              + additions
              + " exists, so nobody could log in");
    }
    PasswordFilePlayerDatabase db = PlayerDatabaseFactory.getPasswordFilePlayerDatabase();
    db.initialize();
    Set<Player> players = db.getAllPlayers();
    log.info(
        "Using per-player passwords from {}: {} player(s), {} of them GM",
        passwords,
        players.size(),
        players.stream().filter(Player::isGM).count());
    return db;
  }

  /**
   * Build the server policy from system properties.
   *
   * <p>A GUI server is configured through the start-server dialog, which a dedicated server does
   * not have, so every policy flag was left at its default and could never be changed. Each setting
   * is read from {@code -Dmaptool.policy.<name>}, and anything not given keeps the default that the
   * dialog would have offered.
   *
   * @return the policy to run with
   */
  private static ServerPolicy buildPolicy() {
    // The logger is created here rather than held in a static field: AppUtil.initLogging() must run
    // before the first Logger exists, or log4j2.xml cannot resolve ${sys:MAPTOOL_LOGDIR}.
    Logger log = LogManager.getLogger(HeadlessServer.class);
    ServerPolicy policy = new ServerPolicy();
    Set<String> known = new TreeSet<>();

    policyFlag(log, known, "useStrictTokenManagement", policy::setUseStrictTokenManagement);
    policyFlag(log, known, "movementLocked", policy::setIsMovementLocked);
    policyFlag(log, known, "tokenEditorLocked", policy::setIsTokenEditorLocked);
    policyFlag(log, known, "tokenContextLocked", policy::setIsTokenContextLocked);
    policyFlag(log, known, "playersCanRevealVision", policy::setPlayersCanRevealVision);
    policyFlag(
        log, known, "gmRevealsVisionForUnownedTokens", policy::setGmRevealsVisionForUnownedTokens);
    policyFlag(log, known, "autoRevealOnMovement", policy::setAutoRevealOnMovement);
    policyFlag(log, known, "useIndividualViews", policy::setUseIndividualViews);
    policyFlag(log, known, "restrictedImpersonation", policy::setRestrictedImpersonation);
    policyFlag(log, known, "playersReceiveCampaignMacros", policy::setPlayersReceiveCampaignMacros);
    policyFlag(log, known, "hiddenMapSelectUI", policy::setHiddenMapSelectUI);
    policyFlag(
        log, known, "useToolTipsForDefaultRollFormat", policy::setUseToolTipsForDefaultRollFormat);
    policyFlag(log, known, "useIndividualFOW", policy::setUseIndividualFOW);
    policyFlag(log, known, "includeOwnedNPCs", policy::setIncludeOwnedNPCs);
    policyFlag(log, known, "usingAstarPathfinding", policy::setUsingAstarPathfinding);
    policyFlag(log, known, "vblBlocksMove", policy::setVblBlocksMove);
    policyFlag(log, known, "disablePlayerAssetPanel", policy::setDisablePlayerAssetPanel);

    known.add("movementMetric");
    String metric = System.getProperty("maptool.policy.movementMetric");
    if (metric != null) {
      try {
        policy.setMovementMetric(WalkerMetric.valueOf(metric.trim().toUpperCase(Locale.ROOT)));
        log.info("Policy movementMetric = {}", metric.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        log.error(
            "Ignoring -Dmaptool.policy.movementMetric={}; expected one of {}",
            metric,
            Arrays.stream(WalkerMetric.values()).map(Enum::name).toList());
      }
    }

    // An operator who mistypes a setting should hear about it rather than wonder why it had no
    // effect.
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith(POLICY_PREFIX)
          && !known.contains(name.substring(POLICY_PREFIX.length()))) {
        log.warn("Unknown policy setting {}; known settings are {}", name, known);
      }
    }

    return policy;
  }

  /**
   * Apply one boolean policy property, if it was given.
   *
   * @param log where to record what was applied
   * @param known collects every setting name this server understands
   * @param name the part after {@code maptool.policy.}
   * @param setter the policy setter to call
   */
  private static void policyFlag(
      Logger log, Set<String> known, String name, Consumer<Boolean> setter) {
    known.add(name);
    String value = System.getProperty(POLICY_PREFIX + name);
    if (value == null) {
      return;
    }
    boolean flag = Boolean.parseBoolean(value.trim());
    setter.accept(flag);
    log.info("Policy {} = {}", name, flag);
  }
}
