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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;

/**
 * Stopping a headless server that was started from a settings file, with {@code
 * -headless-stop:<settings file>}.
 *
 * <p>Ctrl+C, a signal or a service manager stop a server cleanly, with a final save. A server
 * started through MapTool's own launcher on Windows has none of these: the launcher is a windowed
 * program without a console, and the server ignores Windows close requests, so the only way left to
 * stop it was to kill it and lose everything since the last autosave.
 *
 * <p>So a server started from a settings file can also be asked to stop through two files beside
 * that settings file:
 *
 * <ul>
 *   <li>{@code <settings file>.lock} is locked by the server for as long as it runs. The stop
 *       command uses it to tell whether a server is running, and when it has finished.
 *   <li>{@code <settings file>.stop} is written by the stop command. The server looks for it twice
 *       a second, deletes it and exits through its normal shutdown, final save included.
 * </ul>
 *
 * <p>Holding the lock also stops a second server from being started from the same settings file.
 * The operating system releases the lock however the server ends, so a crashed server never leaves
 * a stale one behind.
 */
public final class HeadlessStop {
  /** The command-line argument: {@code -headless-stop:<settings file>}. */
  public static final String ARGUMENT = "-headless-stop";

  private static final Duration POLL = Duration.ofMillis(500);

  /** How long the stop command waits by default; a large campaign's final save takes a while. */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

  private HeadlessStop() {}

  /**
   * Whether the command line asks to stop a server.
   *
   * @param args the program arguments
   * @return empty if it does not; otherwise the settings file named, or an empty string if none is
   */
  public static Optional<String> stopArgument(String[] args) {
    for (String arg : args) {
      if (arg.equals(ARGUMENT) || arg.equals("-" + ARGUMENT)) {
        return Optional.of("");
      }
      for (String prefix : List.of(ARGUMENT + ":", ARGUMENT + "=", "-" + ARGUMENT + "=")) {
        if (arg.startsWith(prefix)) {
          return Optional.of(HeadlessSettings.stripQuotes(arg.substring(prefix.length())));
        }
      }
    }
    return Optional.empty();
  }

  static Path lockFile(Path settings) {
    return settings.resolveSibling(settings.getFileName() + ".lock");
  }

  static Path stopFile(Path settings) {
    return settings.resolveSibling(settings.getFileName() + ".stop");
  }

  /**
   * Claims a settings file for a server about to start: locks {@code <settings file>.lock} for the
   * life of the process and clears any stop request left from before.
   *
   * @param settings the settings file the server is started from
   * @return the claim; keep it referenced for as long as the server runs
   * @throws IllegalStateException if a server started from this settings file is already running
   * @throws IOException if the lock file cannot be created
   */
  static Claim claim(Path settings) throws IOException {
    Path lockPath = lockFile(settings);
    FileChannel channel =
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      lock = null;
    } catch (IOException e) {
      channel.close();
      throw e;
    }
    if (lock == null) {
      channel.close();
      throw new IllegalStateException(
          "A server started from "
              + settings
              + " is already running ("
              + lockPath
              + " is locked). Stop it first with "
              + ARGUMENT
              + ":"
              + settings);
    }
    Files.deleteIfExists(stopFile(settings));
    return new Claim(settings, channel);
  }

  /** A running server's hold on its settings file. */
  static final class Claim implements AutoCloseable {
    private final Path settings;
    private final FileChannel channel;

    private Claim(Path settings, FileChannel channel) {
      this.settings = settings;
      this.channel = channel;
    }

    /**
     * Starts watching for a stop request.
     *
     * @param onStop what to do when one arrives, called once on the watching thread
     */
    void watch(Runnable onStop) {
      Path stop = stopFile(settings);
      Thread watcher =
          new Thread(
              () -> {
                try {
                  while (!Files.exists(stop)) {
                    Thread.sleep(POLL.toMillis());
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  Files.deleteIfExists(stop);
                } catch (IOException e) {
                  LogManager.getLogger(HeadlessStop.class).warn("Could not remove {}", stop, e);
                }
                onStop.run();
              },
              "headless-stop-watcher");
      watcher.setDaemon(true);
      watcher.start();
    }

    /** Releases the lock. A server never needs to: the operating system does it on exit. */
    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  /**
   * The stop command: asks the server started from the named settings file to stop, waits until it
   * has, and exits with 0 if it stopped, 1 if no server was running or it did not stop in time, and
   * 2 if the command itself was wrong. It writes no log file, so it never rotates the server's.
   *
   * @param args the program arguments, holding {@code -headless-stop:<settings file>}
   */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    String name = stopArgument(args).orElse("");
    if (name.isBlank()) {
      err.println("Name the server's settings file: " + ARGUMENT + ":<settings file>");
      return 2;
    }
    Duration timeout =
        Duration.ofSeconds(Long.getLong("maptool.stopTimeoutSeconds", DEFAULT_TIMEOUT.toSeconds()));
    try {
      return stop(Path.of(name).toAbsolutePath(), timeout, out, err);
    } catch (IOException e) {
      err.println("Could not ask the server to stop: " + e);
      return 2;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      err.println("Interrupted while waiting for the server to stop");
      return 1;
    }
  }

  static int stop(Path settings, Duration timeout, PrintStream out, PrintStream err)
      throws IOException, InterruptedException {
    Path lockPath = lockFile(settings);
    if (isFree(lockPath)) {
      err.println("No server is running from " + settings);
      return 1;
    }
    Path stop = stopFile(settings);
    Files.writeString(stop, "Stop requested " + Instant.now() + System.lineSeparator());
    out.println("Asked the server running from " + settings + " to stop; waiting for it to save");
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Thread.sleep(250);
      if (isFree(lockPath)) {
        Files.deleteIfExists(stop);
        out.println("The server has stopped");
        return 0;
      }
    }
    err.println(
        "The server is still running after "
            + timeout.toSeconds()
            + " s. It may still be saving; its log says what it is doing.");
    return 1;
  }

  /** Whether nothing holds the lock file, that is, no server is running from its settings file. */
  private static boolean isFree(Path lockPath) throws IOException {
    try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE)) {
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        return false;
      }
      if (lock == null) {
        return false;
      }
      lock.release();
      return true;
    } catch (NoSuchFileException e) {
      return true;
    }
  }
}
