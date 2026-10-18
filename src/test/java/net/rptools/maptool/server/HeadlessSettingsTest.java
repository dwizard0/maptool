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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a headless server's settings from the command line and a JSON file. */
class HeadlessSettingsTest {
  @TempDir Path dir;

  private Path write(String json) throws IOException {
    Path file = dir.resolve("server-settings.json");
    Files.writeString(file, json);
    return file;
  }

  @Test
  void recognisesTheHeadlessArgumentInItsForms() {
    assertEquals(Optional.empty(), HeadlessSettings.headlessArgument(new String[] {"-foo"}));
    assertEquals(Optional.of(""), HeadlessSettings.headlessArgument(new String[] {"-headless"}));
    assertEquals(
        Optional.of("./server-settings.json"),
        HeadlessSettings.headlessArgument(new String[] {"-headless:./server-settings.json"}));
    // The quotes survive when the argument comes from somewhere that does not strip them.
    assertEquals(
        Optional.of("./server-settings.json"),
        HeadlessSettings.headlessArgument(new String[] {"-headless:\"./server-settings.json\""}));
    assertEquals(
        Optional.of("s.json"),
        HeadlessSettings.headlessArgument(new String[] {"--headless=s.json"}));
  }

  @Test
  void settingsBecomeMaptoolProperties() throws IOException {
    var props = new Properties();
    HeadlessSettings.apply(
        write(
            """
            {"port": 51240, "register": true, "name": "Test", "gmPassword": "g",
             "autosaveMinutes": 0, "policy": {"useIndividualViews": true}}
            """),
        props);
    assertEquals("51240", props.getProperty("maptool.port"));
    assertEquals("true", props.getProperty("maptool.register"));
    assertEquals("Test", props.getProperty("maptool.name"));
    assertEquals("g", props.getProperty("maptool.gmPassword"));
    assertEquals("0", props.getProperty("maptool.autosaveMinutes"));
    assertEquals("true", props.getProperty("maptool.policy.useIndividualViews"));
  }

  @Test
  void relativeCampaignAndSavePathsAreRelativeToTheSettingsFile() throws IOException {
    var props = new Properties();
    HeadlessSettings.apply(
        write("{\"campaign\": \"campaigns/game.cmpgn\", \"save\": \"/abs/save.cmpgn\"}"), props);
    assertEquals(
        dir.resolve("campaigns/game.cmpgn").toAbsolutePath().normalize().toString(),
        props.getProperty("maptool.campaign"));
    assertEquals(Path.of("/abs/save.cmpgn").toString(), props.getProperty("maptool.save"));
  }

  @Test
  void theCommandLineWinsOverTheFile() throws IOException {
    var props = new Properties();
    props.setProperty("maptool.port", "1111");
    HeadlessSettings.apply(write("{\"port\": 2222, \"name\": \"N\"}"), props);
    assertEquals("1111", props.getProperty("maptool.port"));
    assertEquals("N", props.getProperty("maptool.name"));
    assertTrue(
        HeadlessSettings.notes().stream().anyMatch(n -> n.contains("[port]")),
        String.valueOf(HeadlessSettings.notes()));
  }

  @Test
  void anUnknownSettingStopsTheStartAndNamesTheKnownOnes() throws IOException {
    Path file = write("{\"prot\": 51234}");
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> HeadlessSettings.apply(file, new Properties()));
    assertTrue(e.getMessage().contains("\"prot\""), e.getMessage());
    assertTrue(e.getMessage().contains("port"), e.getMessage());
  }

  @Test
  void invalidJsonAndNonScalarValuesAreRejected() throws IOException {
    Path broken = write("{\"port\": ");
    assertThrows(
        IllegalArgumentException.class, () -> HeadlessSettings.apply(broken, new Properties()));
    Path nested = write("{\"port\": {\"value\": 1}}");
    assertThrows(
        IllegalArgumentException.class, () -> HeadlessSettings.apply(nested, new Properties()));
  }

  @Test
  void aMissingFileIsAnError() {
    var props = new Properties();
    assertThrows(
        IOException.class, () -> HeadlessSettings.apply(dir.resolve("missing.json"), props));
    assertNull(props.getProperty("maptool.port"));
  }
}
