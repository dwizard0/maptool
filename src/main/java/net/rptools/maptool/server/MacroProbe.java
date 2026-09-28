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

import java.net.URL;
import javax.imageio.ImageIO;
import net.rptools.lib.net.RPTURLStreamHandlerFactory;
import net.rptools.maptool.client.AppSetup;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetURLStreamHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.library.LibraryManager;
import net.rptools.maptool.model.library.url.LibraryURLStreamHandler;

/**
 * Diagnostic: can MTScript be evaluated with no client frame?
 *
 * <p>Runs a series of macro expressions of increasing dependence on campaign state, reporting which
 * succeed and which throw. Not part of the server.
 */
public class MacroProbe {

  private static final String[] EXPRESSIONS = {
    "[1d20]",
    "[r: 2 + 3]",
    "[r: max(4, 7)]",
    "[h: x = 5][r: x * 2]",
    "[r: 3d6 + 2]",
    "[r: json.get('{\"a\":1}', 'a')]",
    "[r: getMacroName()]",
    "[r: table('nonexistent', 1)]",
  };

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
    LibraryManager.init();

    System.out.println("=== headless macro evaluation ===");
    for (String expression : EXPRESSIONS) {
      try {
        String result = MapTool.getParser().parseLine(expression);
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
    System.exit(0);
  }

  private MacroProbe() {}
}
