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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.client.ui.token.ColorDotTokenOverlay;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignProperties;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.MacroButtonProperties;
import net.rptools.maptool.model.ShapeType;
import net.rptools.maptool.model.SightType;
import net.rptools.maptool.model.Sights;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.AbstractTemplate.Quadrant;
import net.rptools.maptool.model.gamedata.proto.DataStoreDto;
import net.rptools.maptool.model.gamedata.proto.GameDataDto;
import net.rptools.maptool.model.gamedata.proto.GameDataValueDto;
import net.rptools.maptool.server.proto.ExposeFowMsg;
import net.rptools.maptool.server.proto.GetAssetMsg;
import net.rptools.maptool.server.proto.HandshakeResponseCodeMsg;
import net.rptools.maptool.server.proto.Message;
import net.rptools.maptool.server.proto.Message.MessageTypeCase;
import net.rptools.maptool.server.proto.PutAssetMsg;
import net.rptools.maptool.server.proto.PutTokenMsg;
import net.rptools.maptool.server.proto.RoleDto;
import net.rptools.maptool.server.proto.SetVisionTypeMsg;
import net.rptools.maptool.server.proto.UpdateCampaignMacrosMsg;
import net.rptools.maptool.server.proto.UpdateCampaignMsg;
import net.rptools.maptool.server.proto.UpdateDataMsg;
import net.rptools.maptool.server.proto.UpdateDataNamespaceMsg;
import net.rptools.maptool.server.proto.ZoneDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

/**
 * The headless server as its players meet it: a real server process, real sockets, the real
 * handshake, and the messages a MapTool client sends.
 *
 * <p>The unit tests check the server's parts one at a time. This test checks that they add up: a
 * campaign a GM builds over the wire reaches the players who are connected, reaches a player who
 * joins later, is written to disk when the server stops, and is the same campaign after a restart.
 * What it builds are the kinds of change that have gone missing on a server with no window before:
 * campaign properties such as token states, campaign macros, the game data store, shared fog, and a
 * token with its image.
 *
 * <p>It starts server processes, so it is not part of the ordinary test run. Run it with {@code
 * ./gradlew headlessEndToEndTest}.
 */
@Tag("headless-e2e")
class HeadlessServerEndToEndTest {

  private static final String TOKEN_NAME = "E2E Scout";
  private static final String SIGHT_NAME = "E2E Sight";
  private static final String STATE_NAME = "E2E Stunned";
  private static final String MACRO_LABEL = "E2E Macro";
  private static final String MACRO_COMMAND = "[r: 1 + 1]";
  private static final String DATA_TYPE = "e2e";
  private static final String DATA_NAMESPACE = "test";
  private static final Area EXPOSED = new Area(new Rectangle(0, 0, 500, 500));
  private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);

  @TempDir Path home;

  private final List<HeadlessServerProcess> servers = new ArrayList<>();
  private final List<ProtocolClient> clients = new ArrayList<>();
  private int joiners;

  /**
   * Stop anything still running, and keep the servers' console output and log files in {@code
   * build/headless-e2e/<test>} for a look after a failure; the temporary directory is deleted.
   */
  @AfterEach
  void cleanUp(TestInfo test) throws IOException {
    clients.forEach(ProtocolClient::close);
    servers.forEach(HeadlessServerProcess::kill);

    Path kept = Path.of("build", "headless-e2e", test.getTestMethod().orElseThrow().getName());
    Files.createDirectories(kept);
    try (Stream<Path> files = Files.walk(home)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (Files.isRegularFile(file) && (name.endsWith(".out") || name.endsWith(".log"))) {
          Files.copy(file, kept.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  @Test
  void loginsAreCheckedAndThePasswordDecidesTheRole() throws Exception {
    var server = start("logins", Map.of("maptool.autosaveMinutes", "0"));
    int port = server.port();

    var gm = login(port, "E2E GM", HeadlessServerProcess.GM_PASSWORD);
    assertEquals(RoleDto.GM, gm.accepted().getRoleDto(), "GM password");
    var player = login(port, "E2E Player", HeadlessServerProcess.PLAYER_PASSWORD);
    assertEquals(RoleDto.PLAYER, player.accepted().getRoleDto(), "player password");

    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Intruder", "not-the-password", HeadlessServerProcess.VERSION));
    assertRefused(
        HandshakeResponseCodeMsg.WRONG_VERSION,
        attempt(port, "E2E Old Client", HeadlessServerProcess.PLAYER_PASSWORD, "1.0.0"));
    assertRefused(
        HandshakeResponseCodeMsg.PLAYER_ALREADY_CONNECTED,
        attempt(
            port,
            "E2E Player",
            HeadlessServerProcess.PLAYER_PASSWORD,
            HeadlessServerProcess.VERSION));

    // Refusals must not have hurt the server.
    var late = login(port, "E2E Late Player", HeadlessServerProcess.PLAYER_PASSWORD);
    assertEquals(RoleDto.PLAYER, late.accepted().getRoleDto());
    late.next(MessageTypeCase.SET_CAMPAIGN_MSG);

    stop(server);
  }

  @Test
  void aCampaignBuiltOverTheWireReachesEveryoneAndSurvivesARestart() throws Exception {
    Path save = home.resolve("saves").resolve("campaign.cmpgn");
    Files.createDirectories(save.getParent());
    var first =
        start("first-run", Map.of("maptool.save", save.toString(), "maptool.autosaveMinutes", "0"));
    int port = first.port();

    var gm = login(port, "E2E GM", HeadlessServerProcess.GM_PASSWORD);
    Campaign campaign = campaignFrom(gm);
    Zone zone = campaign.getZones().getFirst();
    GUID zoneId = zone.getId();

    // A player who is connected while the GM works, to check that changes are passed on live.
    var watcher = login(port, "E2E Watcher", HeadlessServerProcess.PLAYER_PASSWORD);
    watcher.next(MessageTypeCase.SET_CAMPAIGN_MSG);

    gm.send(visionDay(zoneId));
    gm.send(campaignProperties(campaign));
    Asset image = tokenImage();
    gm.send(putAsset(image));
    Token token = scout(image);
    gm.send(putToken(zoneId, token));
    gm.send(campaignMacros());
    gm.send(dataNamespace());
    gm.send(dataValue());
    gm.send(exposeFog(zoneId, token));

    // Messages from one client reach the others in the order they were sent.
    watcher.next(MessageTypeCase.SET_VISION_TYPE_MSG);
    var update = watcher.next(MessageTypeCase.UPDATE_CAMPAIGN_MSG).getUpdateCampaignMsg();
    assertTrue(
        CampaignProperties.fromDto(update.getProperties())
            .getTokenStatesMap()
            .containsKey(STATE_NAME),
        "the connected player gets the new token state without reconnecting");
    assertEquals(
        TOKEN_NAME,
        watcher.next(MessageTypeCase.PUT_TOKEN_MSG).getPutTokenMsg().getToken().getName());
    watcher.next(MessageTypeCase.UPDATE_CAMPAIGN_MACROS_MSG);
    watcher.next(MessageTypeCase.UPDATE_DATA_NAMESPACE_MSG);
    watcher.next(MessageTypeCase.UPDATE_DATA_MSG);
    watcher.next(MessageTypeCase.EXPOSE_FOW_MSG);

    // The server applies changes on its own threads; a late joiner sees them once they land.
    awaitLateJoinerSeesEverything(port, "a player who joins later");
    assertServerSendsTheImage(port, image, "a player who joins later");

    String firstOutput = stop(first);
    assertTrue(firstOutput.contains("Writing final campaign save"), firstOutput);
    assertTrue(firstOutput.contains("Headless server stopped"), "logging lasts until the end");
    assertTrue(Files.size(save) > 0, "the campaign was saved on stop");
    assertNoErrorsLogged("the first run");

    var second =
        start(
            "second-run",
            Map.of(
                "maptool.campaign", save.toString(),
                "maptool.save", save.toString(),
                "maptool.autosaveMinutes", "0"));
    assertTrue(
        second.output().contains("Loaded campaign from " + save),
        "the restart loads the saved campaign");
    assertTrue(
        filesEndingWith(save.getParent(), ".startup") == 1,
        "the restart copies the campaign aside before writing anything");

    awaitLateJoinerSeesEverything(second.port(), "a player who joins after a restart");
    assertServerSendsTheImage(second.port(), image, "a player who joins after a restart");

    stop(second);
    assertTrue(
        filesEndingWith(save.getParent(), ".bak") >= 1,
        "the second save kept the first as a backup");
    assertNoErrorsLogged("the second run");
  }

  /**
   * With no campaign to load, the server makes a basic one, whose map has the default grass
   * texture. That texture used to be found only through the image index that is built in the
   * background when the default resources are first installed; when the index did not have it, the
   * server asked itself for the image and waited forever. An empty asset cache with the resources
   * already installed -- the second start below -- has no index at all, so it hung every time.
   */
  @Test
  void aServerWithNoCampaignStartsWhenItsAssetCacheIsEmpty() throws Exception {
    Map<String, String> properties =
        Map.of("maptool.autosaveMinutes", "0", "maptool.backups", "false");
    stop(start("installs-resources", properties));

    Path cache = home.resolve("data").resolve("assetcache");
    try (Stream<Path> files = Files.walk(cache)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }

    var server = start("empty-asset-cache", properties);
    var player = login(server.port(), "E2E Player", HeadlessServerProcess.PLAYER_PASSWORD);
    Campaign campaign = campaignFrom(player);
    assertEquals(1, campaign.getZones().size(), "the basic campaign has one map");
    stop(server);
  }

  // ---- What the GM sends -------------------------------------------------------------------

  private static Message visionDay(GUID zoneId) {
    var msg =
        SetVisionTypeMsg.newBuilder()
            .setZoneGuid(zoneId.toString())
            .setVision(ZoneDto.VisionTypeDto.DAY);
    return Message.newBuilder().setSetVisionTypeMsg(msg).build();
  }

  /** The campaign's own properties plus a sight type and a token state, as the GM edits them. */
  private static Message campaignProperties(Campaign campaign) {
    var properties = new CampaignProperties(campaign.getCampaignProperties());
    Sights sights = properties.getSightTypes();
    sights.add(new SightType(SIGHT_NAME, 20, 1.0, ShapeType.CIRCLE, 0, 0, 0, false, null));
    properties.setSightTypes(sights);
    properties
        .getTokenStatesMap()
        .put(STATE_NAME, new ColorDotTokenOverlay(STATE_NAME, Color.RED, Quadrant.SOUTH_EAST));
    var msg = UpdateCampaignMsg.newBuilder().setProperties(properties.toDto());
    return Message.newBuilder().setUpdateCampaignMsg(msg).build();
  }

  /** A token's image, which the GM's client uploads when the token is first placed. */
  private static Asset tokenImage() throws IOException {
    var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(3, 3, Color.RED.getRGB());
    var png = new ByteArrayOutputStream();
    ImageIO.write(image, "png", png);
    return Asset.createImageAsset(TOKEN_NAME, png.toByteArray());
  }

  private static Message putAsset(Asset asset) {
    var msg = PutAssetMsg.newBuilder().setAsset(asset.toDto());
    return Message.newBuilder().setPutAssetMsg(msg).build();
  }

  private static Token scout(Asset image) {
    Token token = new Token(TOKEN_NAME, image.getMD5Key());
    token.setHasSight(true);
    token.setSightType(SIGHT_NAME);
    token.setX(100);
    token.setY(100);
    return token;
  }

  private static Message putToken(GUID zoneId, Token token) {
    var msg = PutTokenMsg.newBuilder().setZoneGuid(zoneId.toString()).setToken(token.toDto());
    return Message.newBuilder().setPutTokenMsg(msg).build();
  }

  private static Message campaignMacros() {
    var macro = new MacroButtonProperties(0);
    macro.setSaveLocation("CampaignPanel");
    macro.setLabel(MACRO_LABEL);
    macro.setCommand(MACRO_COMMAND);
    var msg = UpdateCampaignMacrosMsg.newBuilder().addMacros(macro.toDto());
    return Message.newBuilder().setUpdateCampaignMacrosMsg(msg).build();
  }

  private static Message dataNamespace() {
    var data =
        GameDataDto.newBuilder()
            .setType(DATA_TYPE)
            .setNamespace(DATA_NAMESPACE)
            .addValues(GameDataValueDto.newBuilder().setName("greeting").setStringValue("hello"));
    var msg = UpdateDataNamespaceMsg.newBuilder().setData(data);
    return Message.newBuilder().setUpdateDataNamespaceMsg(msg).build();
  }

  private static Message dataValue() {
    var msg =
        UpdateDataMsg.newBuilder()
            .setType(DATA_TYPE)
            .setNamespace(DATA_NAMESPACE)
            .setValue(GameDataValueDto.newBuilder().setName("counter").setLongValue(42));
    return Message.newBuilder().setUpdateDataMsg(msg).build();
  }

  /** The fog tool with the token selected; individual fog is off, so the area is shared. */
  private static Message exposeFog(GUID zoneId, Token token) {
    var msg =
        ExposeFowMsg.newBuilder()
            .setZoneGuid(zoneId.toString())
            .setArea(Mapper.map(EXPOSED))
            .addTokenGuid(token.getId().toString());
    return Message.newBuilder().setExposeFowMsg(msg).build();
  }

  // ---- What a joining player must see ------------------------------------------------------

  /**
   * Log in new players until one sees everything the GM built, and fail with what is still missing
   * if none does in time. Each attempt is a new player, as the server keeps a name taken until it
   * notices the previous connection has gone.
   */
  private void awaitLateJoinerSeesEverything(int port, String who) throws Exception {
    long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
    List<String> missing;
    do {
      var joiner = login(port, "E2E Joiner " + ++joiners, HeadlessServerProcess.PLAYER_PASSWORD);
      Campaign campaign = campaignFrom(joiner);
      missing = missing(campaign, joiner.accepted().getGameDataDto());
      joiner.close();
      if (missing.isEmpty()) {
        return;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    fail(who + " is missing: " + String.join("; ", missing));
  }

  /**
   * A joining client asks the server for every image it does not have. The server answers with the
   * image if it has it, and with an empty placeholder if it does not.
   */
  private void assertServerSendsTheImage(int port, Asset image, String who) throws Exception {
    var joiner = login(port, "E2E Joiner " + ++joiners, HeadlessServerProcess.PLAYER_PASSWORD);
    joiner.next(MessageTypeCase.SET_CAMPAIGN_MSG);
    var request = GetAssetMsg.newBuilder().setAssetId(image.getMD5Key().toString());
    joiner.send(Message.newBuilder().setGetAssetMsg(request).build());
    var reply =
        joiner.next(MessageTypeCase.START_ASSET_TRANSFER_MSG, MessageTypeCase.PUT_ASSET_MSG);
    assertEquals(
        MessageTypeCase.START_ASSET_TRANSFER_MSG,
        reply.getMessageTypeCase(),
        who + " gets the token's image, not a placeholder");
    var header = reply.getStartAssetTransferMsg().getHeader();
    assertEquals(image.getMD5Key().toString(), header.getId());

    // Receive the whole image; leaving mid-transfer would only make the server log an error.
    var received = new ByteArrayOutputStream();
    while (received.size() < header.getSize()) {
      var chunk = joiner.next(MessageTypeCase.UPDATE_ASSET_TRANSFER_MSG);
      chunk.getUpdateAssetTransferMsg().getChunk().getData().writeTo(received);
    }
    assertEquals(
        image.getMD5Key(),
        new MD5Key(received.toByteArray()),
        who + " gets the token's image intact");
    joiner.close();
  }

  private static List<String> missing(Campaign campaign, DataStoreDto data) {
    List<String> missing = new ArrayList<>();
    CampaignProperties properties = campaign.getCampaignProperties();
    if (properties.getSightTypes().get(SIGHT_NAME).isEmpty()) {
      missing.add("sight type " + SIGHT_NAME);
    }
    if (!properties.getTokenStatesMap().containsKey(STATE_NAME)) {
      missing.add("token state " + STATE_NAME);
    }
    if (campaign.getMacroButtonPropertiesArray().stream()
        .noneMatch(m -> MACRO_LABEL.equals(m.getLabel()) && MACRO_COMMAND.equals(m.getCommand()))) {
      missing.add("campaign macro " + MACRO_LABEL);
    }

    Zone zone = campaign.getZones().getFirst();
    if (zone.getVisionType() != Zone.VisionType.DAY) {
      missing.add("vision type DAY (is " + zone.getVisionType() + ")");
    }
    Token token = zone.getTokenByName(TOKEN_NAME);
    if (token == null) {
      missing.add("token " + TOKEN_NAME);
    } else {
      if (!token.getHasSight() || !SIGHT_NAME.equals(token.getSightType())) {
        missing.add(
            "the token's sight (" + token.getHasSight() + ", " + token.getSightType() + ")");
      }
      if (!zone.getExposedAreaMetaData(token.getExposedAreaGUID())
          .getExposedAreaHistory()
          .isEmpty()) {
        missing.add("fog in the shared area, not the token's own (individual fog is off)");
      }
    }
    if (!zone.getExposedArea().contains(250, 250)) {
      missing.add("the exposed fog");
    }

    GameDataDto namespace =
        data.getDataList().stream()
            .filter(d -> DATA_TYPE.equals(d.getType()) && DATA_NAMESPACE.equals(d.getNamespace()))
            .findFirst()
            .orElse(null);
    if (namespace == null) {
      missing.add("game data namespace " + DATA_TYPE + ":" + DATA_NAMESPACE);
    } else {
      if (namespace.getValuesList().stream()
          .noneMatch(v -> "greeting".equals(v.getName()) && "hello".equals(v.getStringValue()))) {
        missing.add("game data value greeting=hello");
      }
      if (namespace.getValuesList().stream()
          .noneMatch(v -> "counter".equals(v.getName()) && v.getLongValue() == 42)) {
        missing.add("game data value counter=42");
      }
    }
    return missing;
  }

  // ---- Plumbing ----------------------------------------------------------------------------

  private HeadlessServerProcess start(String name, Map<String, String> properties)
      throws Exception {
    var server = HeadlessServerProcess.start(home, name, properties);
    servers.add(server);
    return server;
  }

  private String stop(HeadlessServerProcess server) throws Exception {
    clients.forEach(ProtocolClient::close);
    clients.clear();
    return server.stop();
  }

  private ProtocolClient login(int port, String name, String password) throws Exception {
    var client = ProtocolClient.login(port, name, password);
    clients.add(client);
    return client;
  }

  private ProtocolClient attempt(int port, String name, String password, String version)
      throws Exception {
    var client = ProtocolClient.attempt(port, name, password, version);
    clients.add(client);
    return client;
  }

  private static void assertRefused(HandshakeResponseCodeMsg expected, ProtocolClient client)
      throws Exception {
    assertEquals(expected, client.outcome().rejected(), "login refused with " + expected);
  }

  /** The campaign the server sends every client right after it logs in. */
  private static Campaign campaignFrom(ProtocolClient client) throws Exception {
    var msg = client.next(MessageTypeCase.SET_CAMPAIGN_MSG);
    Campaign campaign = Campaign.fromDto(msg.getSetCampaignMsg().getCampaign());
    assertNotNull(campaign.getZones().getFirst(), "the campaign has a map");
    return campaign;
  }

  private static long filesEndingWith(Path dir, String suffix) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().endsWith(suffix)).count();
    }
  }

  /**
   * Check the server's own log file, which is rolled over at each start, for errors.
   *
   * <p>Every MapTool server and client, including one with a window, logs an error from its socket
   * connection when the other side goes away: {@code Stream closed} when reading, {@code Socket
   * closed} when a message was still being sent. This test connects and drops players all the time,
   * and players leaving is not what it looks for, so those are let through.
   */
  private void assertNoErrorsLogged(String run) throws IOException {
    Path log = home.resolve("data").resolve("logs").resolve("maptool.log");
    List<String> errors =
        Files.readAllLines(log).stream()
            .filter(l -> l.matches("\\S+ \\S+ (ERROR|FATAL) .*"))
            .filter(
                l -> !l.contains(" net.rptools.clientserver.simple.connection.SocketConnection "))
            .toList();
    assertTrue(errors.isEmpty(), run + " logged errors:\n" + String.join("\n", errors));
  }
}
