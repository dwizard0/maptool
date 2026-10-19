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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A headless MapTool server running in its own process, the way the launcher script runs it.
 *
 * <p>Everything the server writes goes under one directory: {@code user.home} and the MapTool data
 * directory both point there, so the test never touches the real user's preferences, logs or
 * campaigns. The server's console output is kept in a file next to them.
 */
final class HeadlessServerProcess {

  static final String GM_PASSWORD = "e2e-gm-password";
  static final String PLAYER_PASSWORD = "e2e-player-password";
  static final String VERSION = "E2E-TEST";

  /** The same module openings the launcher passes; saving a campaign needs them. */
  private static final List<String> OPENS =
      List.of(
          "java.base/java.util",
          "java.base/java.lang",
          "java.desktop/java.awt",
          "java.desktop/java.awt.geom",
          "java.desktop/sun.awt.geom",
          "java.desktop/sun.awt",
          "java.desktop/sun.java2d",
          "java.desktop/javax.swing");

  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

  private final Process process;
  private final Path output;
  private final int port;

  private HeadlessServerProcess(Process process, Path output, int port) {
    this.process = process;
    this.output = output;
    this.port = port;
  }

  /**
   * Start a server and wait until it accepts connections.
   *
   * @param home the directory the server may write to
   * @param name a name for this run, used for the console output file
   * @param properties extra {@code maptool.*} system properties, without the {@code -D}
   * @return the running server
   */
  static HeadlessServerProcess start(Path home, String name, Map<String, String> properties)
      throws IOException, InterruptedException {
    var server = launch(home, name, properties);
    server.awaitListening();
    return server;
  }

  /**
   * Start a server that is expected not to start, and wait for it to exit.
   *
   * @return the exit code and everything the server wrote, or a failed test if it is still running
   *     after the start timeout
   */
  static Exited startExpectingFailure(Path home, String name, Map<String, String> properties)
      throws IOException, InterruptedException {
    return startExpectingFailure(home, name, properties, List.of());
  }

  /**
   * Start a server that is expected not to start, with program arguments.
   *
   * @return the exit code and everything the server wrote
   */
  static Exited startExpectingFailure(
      Path home, String name, Map<String, String> properties, List<String> args)
      throws IOException, InterruptedException {
    var server =
        launch(home, name, properties, freePort(), true, HeadlessServer.class.getName(), args);
    if (!server.process.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      server.process.destroyForcibly();
      fail(
          "The server neither started nor exited within "
              + START_TIMEOUT
              + ":\n"
              + server.output());
    }
    return new Exited(server.process.exitValue(), server.output());
  }

  /** How a server that did not start ended. */
  record Exited(int exitCode, String output) {}

  /**
   * Start a server the way a user would from an installed MapTool: through the normal launcher,
   * with {@code -headless:<file>} and every setting -- port and passwords included -- in that file
   * rather than on the command line.
   *
   * @param home the directory the server may write to; the settings file is written here
   * @param name a name for this run, used for the console output file
   * @param settings further settings for the file, as JSON members (e.g. {@code "\"name\": \"x\""})
   * @return the running server
   */
  static HeadlessServerProcess startFromSettingsFile(Path home, String name, String settings)
      throws IOException, InterruptedException {
    int port = freePort();
    Path file = home.resolve(name + "-settings.json");
    String json =
        "{\"port\": "
            + port
            + ", \"gmPassword\": \""
            + GM_PASSWORD
            + "\", \"playerPassword\": \""
            + PLAYER_PASSWORD
            + "\", \"version\": \""
            + VERSION
            + "\""
            + (settings.isBlank() ? "" : ", " + settings)
            + "}";
    Files.writeString(file, json, StandardCharsets.UTF_8);
    var server =
        launch(
            home,
            name,
            Map.of(),
            port,
            false,
            "net.rptools.maptool.client.LaunchInstructions",
            List.of("-headless:" + file.toAbsolutePath()));
    server.awaitListening();
    return server;
  }

  /**
   * Run MapTool's normal launcher with these arguments and wait for it to exit, as a user would run
   * {@code MapTool -headless-stop:<file>}.
   *
   * @return the exit code and everything it wrote
   */
  static Exited runLauncher(Path home, String name, List<String> args)
      throws IOException, InterruptedException {
    var run =
        launch(
            home,
            name,
            Map.of(),
            freePort(),
            false,
            "net.rptools.maptool.client.LaunchInstructions",
            args);
    if (!run.process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      run.process.destroyForcibly();
      fail("The launcher did not exit within " + STOP_TIMEOUT + ":\n" + run.output());
    }
    return new Exited(run.process.exitValue(), run.output());
  }

  private static HeadlessServerProcess launch(
      Path home, String name, Map<String, String> properties) throws IOException {
    return launch(
        home, name, properties, freePort(), true, HeadlessServer.class.getName(), List.of());
  }

  private static HeadlessServerProcess launch(
      Path home,
      String name,
      Map<String, String> properties,
      int port,
      boolean settingsOnCommandLine,
      String mainClass,
      List<String> args)
      throws IOException {
    String classpath = System.getProperty("maptool.e2e.classpath");
    if (classpath == null || classpath.isBlank()) {
      fail("maptool.e2e.classpath is not set; run this test with ./gradlew headlessEndToEndTest");
    }
    Path output = home.resolve(name + ".out");

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xss8M");
    command.add("-Xmx1g");
    for (String open : OPENS) {
      command.add("--add-opens=" + open + "=ALL-UNNAMED");
    }
    command.add("-Djava.awt.headless=true");
    command.add("-Duser.home=" + home.toAbsolutePath());
    command.add("-DMAPTOOL_DATADIR=" + home.resolve("data").toAbsolutePath());
    if (settingsOnCommandLine) {
      command.add("-Dmaptool.port=" + port);
      command.add("-Dmaptool.gmPassword=" + GM_PASSWORD);
      command.add("-Dmaptool.playerPassword=" + PLAYER_PASSWORD);
      command.add("-Dmaptool.version=" + VERSION);
    }
    properties.forEach((key, value) -> command.add("-D" + key + "=" + value));
    command.add("-cp");
    command.add(classpath);
    command.add(mainClass);
    command.addAll(args);

    Process process =
        new ProcessBuilder(command)
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    return new HeadlessServerProcess(process, output, port);
  }

  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private void awaitListening() throws InterruptedException {
    long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
    String expected = "listening on port " + port;
    while (System.nanoTime() < deadline) {
      if (output().contains(expected)) {
        return;
      }
      if (!process.isAlive()) {
        fail("The server exited with " + process.exitValue() + " before listening:\n" + output());
      }
      Thread.sleep(200);
    }
    process.destroyForcibly();
    fail("The server did not start listening within " + START_TIMEOUT + ":\n" + output());
  }

  int port() {
    return port;
  }

  /** Everything the server has written to its console so far. */
  String output() {
    try {
      return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "(could not read " + output + ": " + e + ")";
    }
  }

  /**
   * Stop the server the way the service manager does, with SIGTERM, and wait for it to exit. The
   * server's shutdown hook writes the final save before the process ends.
   *
   * @return the server's console output
   */
  String stop() throws InterruptedException {
    process.destroy();
    if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("The server did not stop within " + STOP_TIMEOUT + ":\n" + output());
    }
    return output();
  }

  /**
   * Wait for the server to exit by itself, without signalling it.
   *
   * @return its exit code and console output
   */
  Exited awaitExit() throws InterruptedException {
    if (!process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("The server did not exit within " + STOP_TIMEOUT + ":\n" + output());
    }
    return new Exited(process.exitValue(), output());
  }

  /** Kill the server if a test failed before stopping it. */
  void kill() {
    if (process.isAlive()) {
      process.destroyForcibly();
    }
  }
}
