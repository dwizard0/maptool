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
package net.rptools.lib;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs tasks serially, off the calling thread.
 *
 * <p>Much of MapTool schedules work with {@code EventQueue.invokeLater} not because the work is
 * graphical, but because the event dispatch thread is a convenient serial queue that guarantees
 * ordering. That is a reasonable thing to want from non-graphical code, but it ties that code to
 * AWT and makes it fail in a JVM started with {@code -Djava.awt.headless=true}, where there is no
 * event dispatch thread.
 *
 * <p>This class provides the ordering guarantee without the dependency. When a display is present
 * it delegates to the EDT, so behaviour in a normal client is unchanged. When the JVM is headless
 * it uses a single-threaded executor instead.
 *
 * <p>Exceptions are handled the way the EDT handles them: logged, with the queue continuing. A bare
 * {@code ExecutorService.execute} would instead let one failing task terminate the worker thread,
 * which would be a behaviour change for callers migrating from {@code invokeLater}.
 */
public class EventDispatch {
  private static final Logger log = LogManager.getLogger(EventDispatch.class);

  private static ExecutorService serialQueue;

  private static synchronized ExecutorService serialQueue() {
    if (serialQueue == null) {
      serialQueue =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "maptool-serial-dispatch");
                thread.setDaemon(true);
                return thread;
              });
    }
    return serialQueue;
  }

  /**
   * Schedule a task to run serially, off the calling thread.
   *
   * @param task the task to run
   */
  public static void invokeLater(Runnable task) {
    if (GraphicsEnvironment.isHeadless()) {
      serialQueue().execute(() -> runQuietly(task));
    } else {
      EventQueue.invokeLater(task);
    }
  }

  private static void runQuietly(Runnable task) {
    try {
      task.run();
    } catch (Throwable t) {
      log.error("Uncaught exception in dispatched task", t);
    }
  }

  private EventDispatch() {}
}
