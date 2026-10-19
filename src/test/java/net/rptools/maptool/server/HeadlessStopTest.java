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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Asking a headless server started from a settings file to stop. */
class HeadlessStopTest {
  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private Path settings() {
    return dir.resolve("server-settings.json");
  }

  private int stop(Duration timeout) throws Exception {
    return HeadlessStop.stop(
        settings(),
        timeout,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void theArgumentNamesTheSettingsFile() {
    assertEquals(
        Optional.of("a b.json"),
        HeadlessStop.stopArgument(new String[] {"-headless-stop:\"a b.json\""}));
    assertEquals(
        Optional.of("x.json"), HeadlessStop.stopArgument(new String[] {"--headless-stop=x.json"}));
    assertEquals(Optional.of(""), HeadlessStop.stopArgument(new String[] {"-headless-stop"}));
    assertEquals(Optional.empty(), HeadlessStop.stopArgument(new String[] {"-headless:x.json"}));
    assertEquals(
        Optional.empty(), HeadlessSettings.headlessArgument(new String[] {"-headless-stop:x"}));
  }

  @Test
  void withoutASettingsFileTheCommandSaysWhatIsMissing() {
    int code =
        HeadlessStop.run(
            new String[] {"-headless-stop"},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    assertEquals(2, code);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("settings file"));
  }

  @Test
  void stoppingWhenNoServerIsRunningSaysSo() throws Exception {
    assertEquals(1, stop(Duration.ofSeconds(5)));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("No server is running"));
    assertFalse(Files.exists(HeadlessStop.stopFile(settings())), "no request left behind");
  }

  @Test
  void aRunningServerIsAskedToStopAndTheCommandWaitsUntilItHas() throws Exception {
    var claim = HeadlessStop.claim(settings());
    var stopped = new AtomicBoolean();
    claim.watch(
        () -> {
          // Stand-in for the server's shutdown: the lock goes when the process ends.
          stopped.set(true);
          try {
            claim.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });

    assertEquals(0, stop(Duration.ofSeconds(30)), err.toString(StandardCharsets.UTF_8));
    assertTrue(stopped.get());
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("The server has stopped"));
    assertFalse(Files.exists(HeadlessStop.stopFile(settings())));
  }

  @Test
  void aServerThatDoesNotStopInTimeIsReported() throws Exception {
    try (var claim = HeadlessStop.claim(settings())) {
      assertEquals(1, stop(Duration.ofSeconds(1)));
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("still running"));
    }
  }

  @Test
  void aSecondServerCannotStartFromTheSameSettingsFile() throws Exception {
    try (var claim = HeadlessStop.claim(settings())) {
      var e = assertThrows(IllegalStateException.class, () -> HeadlessStop.claim(settings()));
      assertTrue(e.getMessage().contains("already running"), e.getMessage());
    }
    // Once the first has gone, the file can be used again.
    HeadlessStop.claim(settings()).close();
  }

  @Test
  void aStopRequestLeftFromBeforeIsClearedWhenAServerStarts() throws Exception {
    Files.writeString(HeadlessStop.stopFile(settings()), "old");
    try (var claim = HeadlessStop.claim(settings())) {
      assertFalse(Files.exists(HeadlessStop.stopFile(settings())));
    }
  }
}
