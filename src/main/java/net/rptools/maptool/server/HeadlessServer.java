/*
 * This software is copyrighted by the RPTools.net development team, and
 * is licensed under the Affero GPL version 3.0.
 */
package net.rptools.maptool.server;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignFactory;
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
        new ServerConfig(
            "gm", gmPassword, playerPassword, port, serverName, null, false, false);
    DefaultPlayerDatabase playerDb = new DefaultPlayerDatabase(playerPassword, gmPassword);

    MapToolServer server =
        new MapToolServer(null, new Campaign(campaign), config, false, policy, playerDb);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Shutting down headless server");
                  server.stop();
                }));

    server.start();
    log.info("Headless MapTool server listening on port {}", port);
    log.info("Campaign has {} zone(s)", campaign.getZones().size());

    new CountDownLatch(1).await();
  }

  private HeadlessServer() {}
}
