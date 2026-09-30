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
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import net.rptools.lib.net.RPTURLStreamHandlerFactory;
import net.rptools.maptool.client.AppSetup;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetURLStreamHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
import net.rptools.maptool.model.library.LibraryManager;
import net.rptools.maptool.model.library.url.LibraryURLStreamHandler;
import net.rptools.maptool.model.player.DefaultPlayerDatabase;
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

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    // Must happen before any Logger is created, or log4j2.xml resolves ${sys:MAPTOOL_LOGDIR}
    // to a directory literally named that.
    AppUtil.initLogging();
    Logger log = LogManager.getLogger(HeadlessServer.class);
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

    ServerPolicy policy = new ServerPolicy();
    ServerConfig config =
        new ServerConfig("gm", gmPassword, playerPassword, port, serverName, null, false, false);
    DefaultPlayerDatabase playerDb = new DefaultPlayerDatabase(playerPassword, gmPassword);

    MapToolServer server =
        new MapToolServer(null, new Campaign(campaign), config, false, policy, playerDb);

    // Model code resolves tokens and libraries through MapTool.getCampaign(), which reads the
    // static client, not the server. Without this, LibraryToken.findLibrary() and any macro
    // touching a lib:token search the empty personal-server campaign instead. Point the client
    // at the server's own instance so both see the same state.
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
            Integer.getInteger("maptool.backupsToKeep", 24));
    persister.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Stopping headless server");
                  persister.shutdown();
                  server.stop();
                }));

    server.start();
    log.info("Headless MapTool server listening on port {}", port);
    log.info("Reporting version: {}", MapTool.getVersion());
    log.info("Campaign has {} zone(s)", campaign.getZones().size());

    new CountDownLatch(1).await();
  }

  private HeadlessServer() {}
}
