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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Starting a headless server from the command line, with its settings in a JSON file.
 *
 * <p>{@code MapTool -headless:server-settings.json} starts a dedicated server instead of the
 * client. The file holds the settings the server otherwise takes as {@code -Dmaptool.*} system
 * properties:
 *
 * <pre>{@code
 * {
 *   "port": 51234,
 *   "gmPassword": "...",
 *   "playerPassword": "...",
 *   "campaign": "campaigns/my-game.cmpgn",
 *   "autosaveMinutes": 5,
 *   "policy": { "useIndividualViews": true }
 * }
 * }</pre>
 *
 * <p>Each key is a system property without its {@code maptool.} prefix, and {@code policy} holds
 * the {@code maptool.policy.*} ones. A key this server does not know stops it from starting, so a
 * misspelt setting is reported rather than quietly ignored. Relative {@code campaign} and {@code
 * save} paths are relative to the settings file. A property also given with {@code -D} on the
 * command line keeps the command line's value.
 *
 * <p>The data directory is not a setting here: it is chosen before any of this runs, with {@code
 * -DMAPTOOL_DATADIR} as for the client.
 */
public final class HeadlessSettings {
  /** The command-line argument: {@code -headless} alone, or {@code -headless:<settings file>}. */
  public static final String ARGUMENT = "-headless";

  private static final String PREFIX = "maptool.";
  private static final String POLICY = "policy";

  /** Every setting the file may hold, apart from {@code policy}. */
  static final Set<String> KEYS =
      Set.of(
          "port",
          "name",
          "register",
          "gmPassword",
          "playerPassword",
          "passwordFile",
          "campaign",
          "save",
          "autosaveMinutes",
          "idleSaveMinutes",
          "backups",
          "backupsToKeep",
          "dailyBackups",
          "version");

  /** Settings whose values are paths, resolved against the settings file's directory. */
  private static final Set<String> PATHS = Set.of("campaign", "save");

  /** What the last {@link #apply} did, for the server to log once logging is running. */
  private static final List<String> notes = new ArrayList<>();

  private HeadlessSettings() {}

  /**
   * Whether the command line asks for a headless server.
   *
   * @param args the program arguments
   * @return empty if it does not; otherwise the settings file named, or an empty string for {@code
   *     -headless} without one
   */
  public static Optional<String> headlessArgument(String[] args) {
    for (String arg : args) {
      if (arg.equals(ARGUMENT) || arg.equals("-" + ARGUMENT)) {
        return Optional.of("");
      }
      for (String prefix : List.of(ARGUMENT + ":", ARGUMENT + "=", "-" + ARGUMENT + "=")) {
        if (arg.startsWith(prefix)) {
          return Optional.of(stripQuotes(arg.substring(prefix.length())));
        }
      }
    }
    return Optional.empty();
  }

  static String stripQuotes(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /**
   * Applies the settings file named on the command line, if there is one, to the system properties.
   *
   * @param args the program arguments
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the file is not valid JSON or holds an unknown setting
   */
  static void applyFromArguments(String[] args) throws IOException {
    Optional<String> file = headlessArgument(args);
    if (file.isPresent() && !file.get().isBlank()) {
      apply(Path.of(file.get()), System.getProperties());
    }
  }

  /**
   * Reads a settings file into properties.
   *
   * @param file the settings file
   * @param target where the {@code maptool.*} properties go; ones already present are kept
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the file is not valid JSON or holds an unknown setting
   */
  static void apply(Path file, Properties target) throws IOException {
    Path absolute = file.toAbsolutePath().normalize();
    if (!Files.isRegularFile(absolute)) {
      throw new IOException("No headless settings file at " + absolute);
    }
    JsonObject json;
    try {
      JsonElement root = JsonParser.parseString(Files.readString(absolute, StandardCharsets.UTF_8));
      if (!root.isJsonObject()) {
        throw new IllegalArgumentException(absolute + " must hold a JSON object");
      }
      json = root.getAsJsonObject();
    } catch (JsonParseException e) {
      throw new IllegalArgumentException(absolute + " is not valid JSON: " + e.getMessage(), e);
    }

    Map<String, String> settings = new LinkedHashMap<>();
    for (var entry : json.entrySet()) {
      String key = entry.getKey();
      JsonElement value = entry.getValue();
      if (key.equals(POLICY)) {
        if (!value.isJsonObject()) {
          throw new IllegalArgumentException("\"policy\" must be an object in " + absolute);
        }
        for (var policy : value.getAsJsonObject().entrySet()) {
          settings.put(PREFIX + "policy." + policy.getKey(), scalar(absolute, policy));
        }
      } else if (KEYS.contains(key)) {
        String text = scalar(absolute, entry);
        if (PATHS.contains(key) && !text.isBlank()) {
          text = absolute.getParent().resolve(text).normalize().toString();
        }
        settings.put(PREFIX + key, text);
      } else {
        throw new IllegalArgumentException(
            "Unknown setting \"" + key + "\" in " + absolute + "; known settings: " + known());
      }
    }

    notes.clear();
    List<String> applied = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    for (var setting : settings.entrySet()) {
      String name = setting.getKey().substring(PREFIX.length());
      if (target.getProperty(setting.getKey()) != null) {
        kept.add(name);
      } else {
        target.setProperty(setting.getKey(), setting.getValue());
        applied.add(name);
      }
    }
    notes.add("Settings from " + absolute + ": " + (applied.isEmpty() ? "none" : applied));
    if (!kept.isEmpty()) {
      notes.add("Given on the command line instead, so not taken from the file: " + kept);
    }
  }

  private static String scalar(Path file, Map.Entry<String, JsonElement> entry) {
    JsonElement value = entry.getValue();
    if (!value.isJsonPrimitive()) {
      throw new IllegalArgumentException(
          "Setting \"" + entry.getKey() + "\" in " + file + " must be a string, number or boolean");
    }
    return value.getAsString();
  }

  private static String known() {
    var names = new ArrayList<>(KEYS);
    names.add(POLICY);
    names.sort(null);
    return String.join(", ", names);
  }

  /**
   * What reading the settings file did, for the log. Empty if no file was read.
   *
   * @return lines to log
   */
  static List<String> notes() {
    return List.copyOf(notes);
  }
}
