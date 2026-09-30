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
import javax.imageio.ImageIO;
import net.rptools.lib.net.RPTURLStreamHandlerFactory;
import net.rptools.maptool.client.AppSetup;
import net.rptools.maptool.client.AppUtil;
import net.rptools.maptool.client.AssetURLStreamHandler;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.DrawableTexturePaint;
import net.rptools.maptool.model.library.LibraryManager;
import net.rptools.maptool.model.library.url.LibraryURLStreamHandler;
import net.rptools.maptool.server.proto.ZoneDto;
import net.rptools.maptool.util.PersistenceUtil;

/**
 * Diagnostic: load a campaign headless and report, per zone, whether the background paint and map
 * asset survive the Zone -> ZoneDto -> Zone round trip that SET_CAMPAIGN_MSG performs.
 *
 * <p>Not part of the server. Run with the same classpath and --add-opens as HeadlessServer.
 */
public class ZoneDtoDump {

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

    Campaign campaign = PersistenceUtil.loadCampaign(new File(args[0])).campaign;
    System.out.println("=== loaded " + campaign.getZones().size() + " zones ===");

    for (Zone zone : campaign.getZones()) {
      System.out.println();
      System.out.println("ZONE: " + zone.getName());

      // What the in-memory model holds after load.
      var paint = zone.getBackgroundPaint();
      System.out.println("  model.backgroundPaint = " + describe(paint));
      System.out.println("  model.mapAssetId      = " + zone.getMapAssetId());

      // What actually goes on the wire.
      ZoneDto dto = zone.toDto();
      System.out.println("  dto.hasBackgroundPaint = " + dto.hasBackgroundPaint());
      if (dto.hasBackgroundPaint()) {
        var bp = dto.getBackgroundPaint();
        System.out.println("    paintTypeCase = " + bp.getPaintTypeCase());
        if (bp.hasTexturePaint()) {
          System.out.println("    texture.assetId = " + bp.getTexturePaint().getAssetId());
        }
      }
      System.out.println("  dto.hasMapAsset = " + dto.hasMapAsset());
      if (dto.hasMapAsset()) {
        System.out.println("    mapAsset = " + dto.getMapAsset().getValue());
      }

      // And what the client would rebuild from it.
      Zone rebuilt = Zone.fromDto(dto);
      System.out.println("  rebuilt.backgroundPaint = " + describe(rebuilt.getBackgroundPaint()));
      System.out.println("  rebuilt.mapAssetId      = " + rebuilt.getMapAssetId());
    }
    System.exit(0);
  }

  private static String describe(Object paint) {
    if (paint == null) {
      return "null";
    }
    if (paint instanceof DrawableTexturePaint tp) {
      return "DrawableTexturePaint(assetId=" + tp.getAssetId() + ")";
    }
    return paint.getClass().getSimpleName();
  }
}
