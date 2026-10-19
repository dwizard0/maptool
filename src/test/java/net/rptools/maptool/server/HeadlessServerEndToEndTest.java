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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.client.ui.token.ColorDotTokenOverlay;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.model.CampaignProperties;
import net.rptools.maptool.model.GUID;
import net.rptools.maptool.model.Grid;
import net.rptools.maptool.model.GridFactory;
import net.rptools.maptool.model.MacroButtonProperties;
import net.rptools.maptool.model.ShapeType;
import net.rptools.maptool.model.SightType;
import net.rptools.maptool.model.Sights;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.Zone;
import net.rptools.maptool.model.drawing.AbstractTemplate.Quadrant;
import net.rptools.maptool.model.drawing.DrawableColorPaint;
import net.rptools.maptool.model.gamedata.proto.DataStoreDto;
import net.rptools.maptool.model.gamedata.proto.GameDataDto;
import net.rptools.maptool.model.gamedata.proto.GameDataValueDto;
import net.rptools.maptool.model.library.proto.AddOnLibraryListDto;
import net.rptools.maptool.server.proto.AddAddOnLibraryMsg;
import net.rptools.maptool.server.proto.ConnectionSuccessfulMsg;
import net.rptools.maptool.server.proto.ExposeFowMsg;
import net.rptools.maptool.server.proto.GetAssetMsg;
import net.rptools.maptool.server.proto.HandshakeResponseCodeMsg;
import net.rptools.maptool.server.proto.InitiativeListDto;
import net.rptools.maptool.server.proto.Message;
import net.rptools.maptool.server.proto.Message.MessageTypeCase;
import net.rptools.maptool.server.proto.PutAssetMsg;
import net.rptools.maptool.server.proto.PutTokenMsg;
import net.rptools.maptool.server.proto.RemoveAllAddOnLibrariesMsg;
import net.rptools.maptool.server.proto.RemoveDataStoreMsg;
import net.rptools.maptool.server.proto.RoleDto;
import net.rptools.maptool.server.proto.SetCampaignMsg;
import net.rptools.maptool.server.proto.SetVisionTypeMsg;
import net.rptools.maptool.server.proto.TokenInitiativeDto;
import net.rptools.maptool.server.proto.TransferableAddOnLibraryDto;
import net.rptools.maptool.server.proto.UpdateCampaignMacrosMsg;
import net.rptools.maptool.server.proto.UpdateCampaignMsg;
import net.rptools.maptool.server.proto.UpdateDataMsg;
import net.rptools.maptool.server.proto.UpdateDataNamespaceMsg;
import net.rptools.maptool.server.proto.UpdateInitiativeMsg;
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

  // The campaign a GM loads onto the running server, and what the running one had before it.
  private static final String RUNNING_LIBRARY = "net.e2e.running";
  private static final String RUNNING_NAMESPACE = "running";
  private static final String LOADED_LIBRARY = "net.e2e.loaded";
  private static final String LOADED_NAMESPACE = "loaded";
  private static final String LOADED_MAP = "E2E Loaded Map";
  private static final String HERO_NAME = "E2E Hero";
  private static final String LOADED_STATE = "E2E Loaded State";
  private static final String LOADED_MACRO = "E2E Loaded Macro";

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

  /**
   * A GM opens a campaign file on their own computer while connected to the server. Their client
   * sends what it always sends for that, in this order: the images their client had not seen
   * before, then it clears the game data and the add-on libraries, sends the campaign's own game
   * data and libraries, and finally the campaign itself. Everyone, including players who join later
   * or after a restart, must then have the loaded campaign and nothing of the one it replaced.
   */
  @Test
  void aCampaignAGmLoadsReplacesTheRunningOneForEveryone() throws Exception {
    Path save = home.resolve("saves").resolve("campaign.cmpgn");
    Files.createDirectories(save.getParent());
    Map<String, String> properties =
        Map.of("maptool.save", save.toString(), "maptool.autosaveMinutes", "0");
    var first = start("before-and-after-load", properties);
    int port = first.port();

    var gm = login(port, "E2E GM", HeadlessServerProcess.GM_PASSWORD);
    Campaign running = campaignFrom(gm);
    var watcher = login(port, "E2E Watcher", HeadlessServerProcess.PLAYER_PASSWORD);
    watcher.next(MessageTypeCase.SET_CAMPAIGN_MSG);

    // The running campaign has game data and an add-on library of its own.
    Asset runningLibrary = addOnLibrary(RUNNING_LIBRARY);
    gm.send(putAsset(runningLibrary));
    gm.send(addAddOnLibrary(RUNNING_LIBRARY, runningLibrary));
    gm.send(namespace(RUNNING_NAMESPACE));
    gm.send(value(RUNNING_NAMESPACE, "greeting", "running"));
    awaitJoinerSees(
        port,
        "a player who joins before the load",
        joined -> {
          List<String> missing = new ArrayList<>();
          if (!hasLibrary(joined, RUNNING_LIBRARY)) {
            missing.add("add-on library " + RUNNING_LIBRARY);
          }
          if (dataNamespace(joined, RUNNING_NAMESPACE) == null) {
            missing.add("game data namespace " + RUNNING_NAMESPACE);
          }
          return missing;
        });

    // The GM opens a campaign file.
    Asset image = tokenImage();
    Asset loadedLibrary = addOnLibrary(LOADED_LIBRARY);
    Campaign loaded = loadedCampaign(image);
    Zone map = loaded.getZones().getFirst();
    Token hero = map.getTokenByName(HERO_NAME);
    gm.send(putAsset(image));
    gm.send(Message.newBuilder().setRemoveDataStoreMsg(RemoveDataStoreMsg.newBuilder()).build());
    gm.send(
        Message.newBuilder()
            .setRemoveAllAddOnLibrariesMsg(RemoveAllAddOnLibrariesMsg.newBuilder())
            .build());
    gm.send(namespace(LOADED_NAMESPACE));
    gm.send(value(LOADED_NAMESPACE, "greeting", "loaded"));
    // The library comes out of the campaign file into the GM's client, which sends the file just
    // before announcing the library: the server cannot ask for it.
    gm.send(putAsset(loadedLibrary));
    gm.send(addAddOnLibrary(LOADED_LIBRARY, loadedLibrary));
    gm.send(
        Message.newBuilder()
            .setSetCampaignMsg(SetCampaignMsg.newBuilder().setCampaign(loaded.toDto()))
            .build());

    // Play goes on in the loaded campaign: the GM puts the hero into the initiative order.
    var initiative =
        InitiativeListDto.newBuilder()
            .setZoneId(map.getId().toString())
            .setCurrent(-1)
            .addTokens(TokenInitiativeDto.newBuilder().setTokenId(hero.getId().toString()));
    gm.send(
        Message.newBuilder()
            .setUpdateInitiativeMsg(UpdateInitiativeMsg.newBuilder().setList(initiative))
            .build());

    var relayed = watcher.next(MessageTypeCase.SET_CAMPAIGN_MSG).getSetCampaignMsg();
    assertEquals(
        LOADED_MAP,
        Campaign.fromDto(relayed.getCampaign()).getZones().getFirst().getName(),
        "the connected player is sent the loaded campaign");

    Function<Joined, List<String>> loadedMissing =
        joined -> missingFromLoaded(joined, running, map, hero);
    awaitJoinerSees(port, "a player who joins after the load", loadedMissing);
    assertServerSendsTheImage(port, image, "a player who joins after the load");

    stop(first);
    assertNoErrorsLogged("the run with the load");

    var second =
        start(
            "after-restart",
            Map.of(
                "maptool.campaign", save.toString(),
                "maptool.save", save.toString(),
                "maptool.autosaveMinutes", "0"));
    awaitJoinerSees(second.port(), "a player who joins after a restart", loadedMissing);
    assertServerSendsTheImage(second.port(), image, "a player who joins after a restart");
    stop(second);
    assertNoErrorsLogged("the run after the restart");
  }

  /** The campaign in the file the GM opens: one map with a token on it, and its own settings. */
  private static Campaign loadedCampaign(Asset image) {
    Campaign campaign = new Campaign();
    campaign.setName("E2E Loaded Campaign");
    Zone map = new Zone();
    map.setName(LOADED_MAP);
    map.setGrid(GridFactory.createGrid(Grid.GridType.Square));
    map.setBackgroundPaint(new DrawableColorPaint(Color.DARK_GRAY));
    map.setFogPaint(new DrawableColorPaint(Color.BLACK));
    Token hero = new Token(HERO_NAME, image.getMD5Key());
    hero.setX(200);
    hero.setY(200);
    map.putToken(hero);
    campaign.putZone(map);
    // getCampaignProperties() hands out a copy.
    CampaignProperties properties = campaign.getCampaignProperties();
    properties
        .getTokenStatesMap()
        .put(LOADED_STATE, new ColorDotTokenOverlay(LOADED_STATE, Color.BLUE, Quadrant.NORTH_WEST));
    campaign.replaceCampaignProperties(properties);
    var macro = new MacroButtonProperties(0);
    macro.setSaveLocation("CampaignPanel");
    macro.setLabel(LOADED_MACRO);
    macro.setCommand(MACRO_COMMAND);
    campaign.setMacroButtonPropertiesArray(new ArrayList<>(List.of(macro)));
    return campaign;
  }

  private static List<String> missingFromLoaded(
      Joined joined, Campaign running, Zone map, Token hero) {
    List<String> missing = new ArrayList<>();
    Campaign campaign = joined.campaign();
    for (Zone zone : running.getZones()) {
      if (campaign.getZone(zone.getId()) != null) {
        missing.add("the running campaign's map " + zone.getName() + " to be gone");
      }
    }
    Zone joinedMap = campaign.getZone(map.getId());
    if (joinedMap == null) {
      missing.add("map " + LOADED_MAP);
    } else {
      if (joinedMap.getToken(hero.getId()) == null) {
        missing.add("token " + HERO_NAME);
      }
      var order = joinedMap.getInitiativeList();
      if (order.getSize() != 1 || !hero.getId().equals(order.getTokenInitiative(0).getId())) {
        missing.add("the hero in the initiative order (" + order.getSize() + " entries)");
      }
    }
    if (!campaign.getCampaignProperties().getTokenStatesMap().containsKey(LOADED_STATE)) {
      missing.add("token state " + LOADED_STATE);
    }
    if (campaign.getMacroButtonPropertiesArray().stream()
        .noneMatch(m -> LOADED_MACRO.equals(m.getLabel()))) {
      missing.add("campaign macro " + LOADED_MACRO);
    }
    if (dataNamespace(joined, RUNNING_NAMESPACE) != null) {
      missing.add("the running campaign's game data to be gone");
    }
    GameDataDto data = dataNamespace(joined, LOADED_NAMESPACE);
    if (data == null
        || data.getValuesList().stream()
            .noneMatch(
                v -> "greeting".equals(v.getName()) && "loaded".equals(v.getStringValue()))) {
      missing.add("game data " + LOADED_NAMESPACE + " greeting=loaded");
    }
    if (hasLibrary(joined, RUNNING_LIBRARY)) {
      missing.add("the running campaign's add-on library to be gone");
    }
    if (!hasLibrary(joined, LOADED_LIBRARY)) {
      missing.add("add-on library " + LOADED_LIBRARY);
    }
    return missing;
  }

  private static boolean hasLibrary(Joined joined, String namespace) {
    return joined.accepted().getAddOnLibraryListDto().getLibrariesList().stream()
        .map(AddOnLibraryListDto.AddOnLibraryEntryDto::getDetails)
        .anyMatch(d -> namespace.equals(d.getNamespace()));
  }

  private static GameDataDto dataNamespace(Joined joined, String namespace) {
    return joined.accepted().getGameDataDto().getDataList().stream()
        .filter(d -> DATA_TYPE.equals(d.getType()) && namespace.equals(d.getNamespace()))
        .findFirst()
        .orElse(null);
  }

  /** A minimal add-on library file, as the GM would import or a campaign file would carry. */
  private static Asset addOnLibrary(String namespace) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("library.json"));
      String info =
          "{\"name\": \"%s\", \"version\": \"1.0.0\", \"namespace\": \"%s\", \"description\":"
              + " \"End-to-end test library\"}";
      zip.write(info.formatted(namespace, namespace).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("library/public/readme.txt"));
      zip.write("End-to-end test".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return Asset.Type.MTLIB.getFactory().apply(namespace, bytes.toByteArray());
  }

  private static Message addAddOnLibrary(String namespace, Asset library) {
    var lib =
        TransferableAddOnLibraryDto.newBuilder()
            .setNamespace(namespace)
            .setVersion("1.0.0")
            .setAssetKey(library.getMD5Key().toString());
    return Message.newBuilder()
        .setAddAddOnLibraryMsg(AddAddOnLibraryMsg.newBuilder().addAddOns(lib))
        .build();
  }

  /** A client creating a namespace sends it empty, then each value on its own. */
  private static Message namespace(String namespace) {
    var data = GameDataDto.newBuilder().setType(DATA_TYPE).setNamespace(namespace);
    return Message.newBuilder()
        .setUpdateDataNamespaceMsg(UpdateDataNamespaceMsg.newBuilder().setData(data))
        .build();
  }

  private static Message value(String namespace, String name, String value) {
    var msg =
        UpdateDataMsg.newBuilder()
            .setType(DATA_TYPE)
            .setNamespace(namespace)
            .setValue(GameDataValueDto.newBuilder().setName(name).setStringValue(value));
    return Message.newBuilder().setUpdateDataMsg(msg).build();
  }

  /**
   * Several players at once, each moving their own token and updating their own game data as fast
   * as they can, at the same time. Every client must get every other client's changes, in the order
   * they were made and without losing or repeating any, and never its own back; a player who joins
   * afterwards, and one who joins after a restart, must see where everything ended up.
   */
  @Test
  void manyPlayersChangingThingsAtOnceAllSeeTheSameResult() throws Exception {
    final int players = 4;
    final int moves = 100;
    final int dataUpdates = moves / 2;
    Path save = home.resolve("saves").resolve("campaign.cmpgn");
    Files.createDirectories(save.getParent());
    Map<String, String> properties =
        Map.of("maptool.save", save.toString(), "maptool.autosaveMinutes", "0");
    var first = start("crew", properties);
    int port = first.port();

    // The GM gives each player a token of their own.
    var gm = login(port, "E2E GM", HeadlessServerProcess.GM_PASSWORD);
    GUID zoneId = campaignFrom(gm).getZones().getFirst().getId();
    Asset image = tokenImage();
    gm.send(putAsset(image));
    for (int p = 1; p <= players; p++) {
      Token token = new Token(crewToken(p), image.getMD5Key());
      token.addOwner(crewName(p));
      token.setX(0);
      token.setY(p * 100);
      gm.send(putToken(zoneId, token));
    }
    awaitJoinerSees(
        port,
        "a player who joins after the tokens are placed",
        joined -> {
          List<String> missing = new ArrayList<>();
          Zone zone = joined.campaign().getZone(zoneId);
          for (int p = 1; p <= players; p++) {
            if (zone.getTokenByName(crewToken(p)) == null) {
              missing.add(crewToken(p));
            }
          }
          return missing;
        });

    List<ProtocolClient> crew = new ArrayList<>();
    List<Token> tokens = new ArrayList<>();
    for (int p = 1; p <= players; p++) {
      var player = login(port, crewName(p), HeadlessServerProcess.PLAYER_PASSWORD);
      Campaign campaign = campaignFrom(player);
      crew.add(player);
      tokens.add(campaign.getZone(zoneId).getTokenByName(crewToken(p)));
    }

    // Everyone at once.
    var startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(players);
    try {
      List<Future<?>> running = new ArrayList<>();
      for (int p = 0; p < players; p++) {
        var player = crew.get(p);
        var token = tokens.get(p);
        String namespace = crewNamespace(p + 1);
        running.add(
            pool.submit(
                () -> {
                  startLine.await();
                  for (int i = 1; i <= moves; i++) {
                    token.setX(i * 50);
                    player.send(putToken(zoneId, token));
                    if (i % 2 == 0) {
                      player.send(counter(namespace, i / 2));
                    }
                  }
                  return null;
                }));
      }
      startLine.countDown();
      for (var done : running) {
        done.get();
      }
    } finally {
      pool.shutdown();
    }

    // What each client received from the others.
    for (int r = 0; r <= players; r++) {
      ProtocolClient receiver = r == 0 ? gm : crew.get(r - 1);
      String who = r == 0 ? "the GM" : crewName(r);
      int senders = r == 0 ? players : players - 1;
      var received =
          receiver.collect(
              senders * (moves + dataUpdates),
              MessageTypeCase.PUT_TOKEN_MSG,
              MessageTypeCase.UPDATE_DATA_MSG);
      var extra =
          receiver.extra(
              Duration.ofMillis(500),
              MessageTypeCase.PUT_TOKEN_MSG,
              MessageTypeCase.UPDATE_DATA_MSG);
      assertTrue(extra.isEmpty(), who + " got " + extra.size() + " messages too many");

      Map<String, List<Integer>> moved =
          received.stream()
              .filter(m -> m.hasPutTokenMsg())
              .collect(
                  Collectors.groupingBy(
                      m -> m.getPutTokenMsg().getToken().getName(),
                      Collectors.mapping(
                          m -> m.getPutTokenMsg().getToken().getX(), Collectors.toList())));
      Map<String, List<Long>> counted =
          received.stream()
              .filter(m -> m.hasUpdateDataMsg())
              .collect(
                  Collectors.groupingBy(
                      m -> m.getUpdateDataMsg().getNamespace(),
                      Collectors.mapping(
                          m -> m.getUpdateDataMsg().getValue().getLongValue(),
                          Collectors.toList())));
      for (int p = 1; p <= players; p++) {
        if (p == r) {
          assertTrue(!moved.containsKey(crewToken(p)), who + " was sent their own moves back");
          assertTrue(!counted.containsKey(crewNamespace(p)), who + " was sent their own data back");
          continue;
        }
        List<Integer> xs = new ArrayList<>();
        for (int i = 1; i <= moves; i++) {
          xs.add(i * 50);
        }
        assertEquals(xs, moved.get(crewToken(p)), who + " sees " + crewName(p) + "'s moves");
        List<Long> values = new ArrayList<>();
        for (long i = 1; i <= dataUpdates; i++) {
          values.add(i);
        }
        assertEquals(
            values, counted.get(crewNamespace(p)), who + " sees " + crewName(p) + "'s data");
      }
    }

    Function<Joined, List<String>> endState =
        joined -> {
          List<String> missing = new ArrayList<>();
          Zone zone = joined.campaign().getZone(zoneId);
          for (int p = 1; p <= players; p++) {
            Token token = zone.getTokenByName(crewToken(p));
            if (token == null || token.getX() != moves * 50) {
              missing.add(
                  crewToken(p)
                      + " at x="
                      + (moves * 50)
                      + " (is "
                      + (token == null ? "gone" : token.getX())
                      + ")");
            }
            GameDataDto data = dataNamespace(joined, crewNamespace(p));
            if (data == null
                || data.getValuesList().stream()
                    .noneMatch(
                        v -> "counter".equals(v.getName()) && v.getLongValue() == dataUpdates)) {
              missing.add(crewNamespace(p) + " counter=" + dataUpdates);
            }
          }
          return missing;
        };
    awaitJoinerSees(port, "a player who joins afterwards", endState);
    stop(first);
    assertNoErrorsLogged("the run with many players");

    var second =
        start(
            "crew-after-restart",
            Map.of(
                "maptool.campaign", save.toString(),
                "maptool.save", save.toString(),
                "maptool.autosaveMinutes", "0"));
    awaitJoinerSees(second.port(), "a player who joins after a restart", endState);
    stop(second);
    assertNoErrorsLogged("the run after the restart");
  }

  private static String crewName(int p) {
    return "E2E Crew " + p;
  }

  private static String crewToken(int p) {
    return "E2E Crew Token " + p;
  }

  private static String crewNamespace(int p) {
    return "crew-" + p;
  }

  private static Message counter(String namespace, long value) {
    var msg =
        UpdateDataMsg.newBuilder()
            .setType(DATA_TYPE)
            .setNamespace(namespace)
            .setValue(GameDataValueDto.newBuilder().setName("counter").setLongValue(value));
    return Message.newBuilder().setUpdateDataMsg(msg).build();
  }

  /**
   * Per-player passwords, as MapTool's own servers offer them: each player logs in with their own
   * password from {@code passwords.json}, and the shared GM and player passwords are not used. New
   * players are added through {@code passwords_add.json}, which the server merges in at startup,
   * hashing the plain-text passwords and deleting the file.
   */
  @Test
  void eachPlayerLogsInWithTheirOwnPassword() throws Exception {
    Path config = home.resolve("data").resolve("config");
    Files.createDirectories(config);
    Path additions = config.resolve("passwords_add.json");
    Files.writeString(
        additions,
        """
        {"passwords": [
          {"username": "E2E Keeper", "password": "keeper-secret", "role": "GM"},
          {"username": "E2E Alice", "password": "alice-secret", "role": "PLAYER"},
          {"username": "E2E Bob", "password": "bob-secret", "role": "PLAYER"},
          {"username": "E2E Mallory", "password": "mallory-secret", "role": "PLAYER",
           "blocked": "E2E test ban"}
        ]}
        """);
    Map<String, String> properties =
        Map.of("maptool.passwordFile", "true", "maptool.autosaveMinutes", "0");

    var first = start("passwords", properties);
    assertPerPlayerLogins(first.port());
    stop(first);

    assertFalse(Files.exists(additions), "the additions file is merged in and deleted");
    for (String file : List.of("passwords.json", "passwords.json.backup")) {
      Path stored = config.resolve(file);
      if (Files.exists(stored)) {
        String text = Files.readString(stored);
        for (String secret :
            List.of("keeper-secret", "alice-secret", "bob-secret", "mallory-secret")) {
          assertFalse(text.contains(secret), file + " holds a plain-text password");
        }
      }
    }
    assertNoErrorsLogged("the first run with per-player passwords");

    // After a restart the hashed file alone must do.
    var second = start("passwords-after-restart", properties);
    assertPerPlayerLogins(second.port());
    stop(second);
    assertNoErrorsLogged("the run after the restart");
  }

  /**
   * A server that cannot start must exit and say why, rather than keep running without listening.
   * Per-player passwords with no password file is one such case: nobody could ever log in.
   */
  @Test
  void aServerThatCannotStartExitsAndSaysWhy() throws Exception {
    var exited =
        HeadlessServerProcess.startExpectingFailure(
            home, "no-password-file", Map.of("maptool.passwordFile", "true"));
    assertEquals(1, exited.exitCode(), exited.output());
    assertTrue(
        exited.output().contains("The headless server could not start")
            && exited.output().contains("passwords_add.json"),
        exited.output());
  }

  @Test
  void theNormalLauncherStartsAServerFromASettingsFile() throws Exception {
    // Everything -- port and passwords included -- comes from the file; nothing is given with -D.
    // The save path is relative, so it must end up beside the settings file.
    Files.createDirectories(home.resolve("saves"));
    var server =
        HeadlessServerProcess.startFromSettingsFile(
            home, "settings-file", "\"autosaveMinutes\": 0, \"save\": \"saves/game.cmpgn\"");
    servers.add(server);
    int port = server.port();

    var gm = login(port, "E2E GM", HeadlessServerProcess.GM_PASSWORD);
    assertEquals(RoleDto.GM, gm.accepted().getRoleDto(), "GM password from the file");
    var player = login(port, "E2E Player", HeadlessServerProcess.PLAYER_PASSWORD);
    assertEquals(RoleDto.PLAYER, player.accepted().getRoleDto(), "player password from the file");
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Intruder", "not-the-password", HeadlessServerProcess.VERSION));

    String output = stop(server);
    assertTrue(output.contains("Settings from "), output);
    assertTrue(
        Files.isRegularFile(home.resolve("saves/game.cmpgn")),
        "the final save went to the path the file named, relative to the file:\n" + output);
  }

  @Test
  void aMisspeltSettingStopsTheServerAndSaysWhich() throws Exception {
    Path file = home.resolve("typo-settings.json");
    Files.writeString(file, "{\"prot\": 51234}");
    var exited =
        HeadlessServerProcess.startExpectingFailure(
            home, "typo", Map.of(), List.of("-headless:" + file.toAbsolutePath()));
    assertEquals(1, exited.exitCode(), exited.output());
    assertTrue(exited.output().contains("Unknown setting \"prot\""), exited.output());
    // MapTool's own launcher on Windows has no console, so the error must reach the log too.
    String log = Files.readString(home.resolve("data").resolve("logs").resolve("maptool.log"));
    assertTrue(log.contains("Unknown setting \"prot\""), log);
  }

  private void assertPerPlayerLogins(int port) throws Exception {
    String version = HeadlessServerProcess.VERSION;
    // A password only opens its own player's login.
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Alice", "bob-secret", version));
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Alice", "keeper-secret", version));
    // The shared passwords no longer open anything.
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Alice", HeadlessServerProcess.PLAYER_PASSWORD, version));
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Keeper", HeadlessServerProcess.GM_PASSWORD, version));
    // Nobody gets in under a name that isn't listed.
    assertRefused(
        HandshakeResponseCodeMsg.INVALID_PASSWORD,
        attempt(port, "E2E Stranger", "alice-secret", version));
    // A blocked player is told so, even with the right password.
    assertEquals(
        "E2E test ban",
        attempt(port, "E2E Mallory", "mallory-secret", version).outcome().blocked(),
        "the blocked player is refused with the reason");

    // The role comes from the file, not from which password was used.
    var keeper = login(port, "E2E Keeper", "keeper-secret");
    assertEquals(RoleDto.GM, keeper.accepted().getRoleDto(), "E2E Keeper is a GM");
    var alice = login(port, "E2E Alice", "alice-secret");
    assertEquals(RoleDto.PLAYER, alice.accepted().getRoleDto(), "E2E Alice is a player");
    var bob = login(port, "E2E Bob", "bob-secret");
    assertEquals(RoleDto.PLAYER, bob.accepted().getRoleDto(), "E2E Bob is a player");
    bob.next(MessageTypeCase.SET_CAMPAIGN_MSG);
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
    awaitJoinerSees(
        port, who, joined -> missing(joined.campaign(), joined.accepted().getGameDataDto()));
  }

  /** What a player gets on joining: the handshake's game data and libraries, and the campaign. */
  private record Joined(ConnectionSuccessfulMsg accepted, Campaign campaign) {}

  /**
   * Log in new players until one sees what {@code missing} asks for, and fail with what is still
   * missing if none does in time.
   */
  private void awaitJoinerSees(int port, String who, Function<Joined, List<String>> missing)
      throws Exception {
    long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
    List<String> stillMissing;
    do {
      var joiner = login(port, "E2E Joiner " + ++joiners, HeadlessServerProcess.PLAYER_PASSWORD);
      stillMissing = missing.apply(new Joined(joiner.accepted(), campaignFrom(joiner)));
      joiner.close();
      if (stillMissing.isEmpty()) {
        return;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    fail(who + " is missing: " + String.join("; ", stillMissing));
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
