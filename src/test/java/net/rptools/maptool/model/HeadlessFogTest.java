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
package net.rptools.maptool.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.util.Set;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.server.ServerPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fog of war as a server with no window records it.
 *
 * <p>These tests run without a {@code MapToolFrame}, as a server without a UI does. Per-token fog
 * used to reach through the frame to flush the view inside its loop over the selected tokens, so
 * with no window it threw on the first token and the rest were never recorded.
 *
 * <p>The last tests also run as a headless process. There, the per-token-or-shared decision must
 * follow the policy the server enforces, as it does for every connected client, and must not treat
 * the server as a single-user personal server.
 */
public class HeadlessFogTest {

  private static final Area AREA = new Area(new Rectangle(0, 0, 100, 100));

  private ServerPolicy originalPolicy;
  private Zone zone;
  private Token first;
  private Token second;

  @BeforeEach
  void setUp() {
    assertNull(MapTool.getFrame(), "these tests assume there is no window");
    originalPolicy = MapTool.getClient().getServerPolicy();
    zone = new Zone();
    first = tokenWithSight("First");
    second = tokenWithSight("Second");
  }

  @AfterEach
  void tearDown() {
    MapTool.setHeadless(false);
    MapTool.getClient().setServerPolicy(originalPolicy);
  }

  private Token tokenWithSight(String name) {
    Token token = new Token();
    token.setName(name);
    token.setHasSight(true);
    zone.putToken(token);
    return token;
  }

  private void useIndividualFog(boolean individual) {
    ServerPolicy policy = new ServerPolicy(originalPolicy);
    policy.setUseIndividualFOW(individual);
    MapTool.getClient().setServerPolicy(policy);
  }

  private boolean tokenSees(Token token) {
    return zone.getExposedAreaMetaData(token.getExposedAreaGUID())
        .getExposedAreaHistory()
        .contains(50, 50);
  }

  private Set<GUID> both() {
    return Set.of(first.getId(), second.getId());
  }

  @Test
  void settingFogForSelectedTokensRecordsEveryToken() {
    useIndividualFog(true);

    assertDoesNotThrow(() -> zone.setFogArea(AREA, both()));

    assertTrue(tokenSees(first));
    assertTrue(tokenSees(second));
  }

  @Test
  void exposingFogForSelectedTokensRecordsEveryToken() {
    useIndividualFog(true);

    assertDoesNotThrow(() -> zone.exposeArea(AREA, both()));

    assertTrue(tokenSees(first));
    assertTrue(tokenSees(second));
  }

  @Test
  void hidingFogForSelectedTokensUpdatesEveryToken() {
    useIndividualFog(true);
    zone.setFogArea(AREA, both());

    assertDoesNotThrow(() -> zone.hideArea(AREA, both()));

    assertFalse(tokenSees(first));
    assertFalse(tokenSees(second));
  }

  @Test
  void aHeadlessProcessIsNotAPersonalServer() {
    MapTool.setHeadless(true);

    assertFalse(MapTool.isPersonalServer());
  }

  /**
   * With individual fog off, a client exposing an area with tokens selected adds it to the shared
   * exposed area. A headless server must record the same thing, or its saved campaign and anyone
   * who joins later get different fog from the players who were there.
   */
  @Test
  void aHeadlessServerWithoutIndividualFogExposesTheSharedArea() {
    MapTool.setHeadless(true);
    useIndividualFog(false);

    zone.exposeArea(AREA, both());

    assertTrue(zone.getExposedArea().contains(50, 50));
    assertFalse(tokenSees(first));
    assertFalse(tokenSees(second));
  }
}
