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
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.server;

import java.io.File;
import java.net.URL;
import javax.imageio.ImageIO;
import net.rptools.lib.net.RPTURLStreamHandlerFactory;
import net.rptools.maptool.client.AppSetup;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetURLStreamHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.library.LibraryManager;
import net.rptools.maptool.model.library.url.LibraryURLStreamHandler;
import net.rptools.maptool.util.PersistenceUtil;

/**
 * Diagnostic: evaluate macros headless with a loaded campaign, both with a token in context and
 * against a lib:token. Not part of the server.
 */
public class MacroProbe2 {

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    AppUtil.initLogging();
    MapTool.setHeadless(true);
    ImageIO.setUseCache(false);

    RPTURLStreamHandlerFactory factory = new RPTURLStreamHandlerFactory();
    factory.registerProtocol("asset", new AssetURLStreamHandler());
    factory.registerProtocol("lib", new LibraryURLStreamHandler());
    URL.setURLStreamHandlerFactory(factory);
    AppSetup.install();

    Campaign campaign = PersistenceUtil.loadCampaign(new File(args[0])).campaign;
    MapTool.getClient().setCampaign(campaign);
    LibraryManager.init();
    Thread.sleep(3000); // let the deferred library scan finish

    System.out.println("=== library registration ===");
    var libs =
        new LibraryManager().getLibraries(net.rptools.maptool.model.library.LibraryType.TOKEN);
    System.out.println("registered libraries: " + libs.size());
    libs.forEach(
        l -> {
          try {
            System.out.println("  " + l.namespace());
          } catch (Exception e) {
            System.out.println("  <error: " + e.getMessage() + ">");
          }
        });

    // Find a token to use as context.
    Token context = null;
    Zone contextZone = null;
    for (Zone zone : campaign.getZones()) {
      for (Token t : zone.getAllTokens()) {
        if (!t.getName().toLowerCase().startsWith("lib:")) {
          context = t;
          contextZone = zone;
          break;
        }
      }
      if (context != null) {
        break;
      }
    }
    System.out.println();
    System.out.println(
        "context token: "
            + (context == null ? "NONE" : context.getName() + " on " + contextZone.getName()));

    String[] withContext = {
      "[r: getName()]",
      "[r: getStrProp(getPropertyNames(), 'x')]",
      "[r: json.length(getPropertyNames())]"
    };
    System.out.println();
    System.out.println("=== with token in context ===");
    for (String expression : withContext) {
      run(expression, context);
    }

    System.out.println();
    System.out.println("=== lib:token resolution ===");
    run("[r: getLibProperty('probe.nonexistent', 'lib:sta')]", context);
    run("[r: getLibProperty('probe.nonexistent', 'lib:doesnotexist')]", context);

    System.exit(0);
  }

  private static void run(String expression, Token context) {
    try {
      String result = MapTool.getParser().parseLine(context, expression);
      System.out.println("OK    " + expression + "  ->  " + result);
    } catch (Throwable t) {
      System.out.println(
          "FAIL  "
              + expression
              + "  ->  "
              + t.getClass().getSimpleName()
              + ": "
              + String.valueOf(t.getMessage()).replace('\n', ' '));
    }
  }

  private MacroProbe2() {}
}
