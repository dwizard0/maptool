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
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single thread that owns the campaign model.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The campaign model is not thread safe, and making it so is not on the table: it is a large,
 * old object graph whose parts are reached directly from everywhere. What keeps it correct instead
 * is confinement -- every read and write happens on one thread, so no two threads can ever be in
 * the model at once. Work that arrives on other threads, notably messages handled by {@code
 * ServerMessageHandler} on socket threads, is handed to that thread rather than touching the model
 * itself.
 *
 * <p>In the Swing client that one thread is the AWT event dispatch thread. That is historical
 * rather than essential: when this code was written the EDT was the ready-made serial queue every
 * Java program already had. The model is confined there because it needs to be confined somewhere,
 * not because model work is graphical. It does, however, become graphical along the way, because
 * mutating the model posts to {@code MapToolEventBus}, which dispatches synchronously to
 * subscribers that include live Swing components. So the confinement is load-bearing in both
 * directions and must not be relaxed.
 *
 * <p>This class names that role rather than assuming the EDT fills it. When a display is available
 * it delegates to {@link EventQueue}, so the client keeps the exact same single thread and nothing
 * about its behaviour changes. In a JVM started with {@code -Djava.awt.headless=true} there is no
 * event dispatch thread to delegate to, and the model thread is instead a single dedicated thread
 * owned by this class. That is what allows model-owning code to run with no UI at all, such as in a
 * dedicated server.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Confinement holds for exactly the code that routes through this class, exactly as it holds for
 * {@code EventQueue} today. Code that calls {@code EventQueue} or {@code SwingUtilities} directly,
 * or asks {@code isEventDispatchThread()}, is not covered: headless it will either fail outright or
 * silently answer for the wrong thread. Such code has to be migrated to this class deliberately,
 * one call site at a time.
 *
 * <h2>If you are editing this class</h2>
 *
 * <ul>
 *   <li>There must only ever be one model thread. Do not grow the executor beyond a single thread,
 *       and do not add a second queue.
 *   <li>Keep the two paths equivalent. Anything added here has to behave the same whether it is
 *       running on the EDT or on the dedicated thread, or the client and a headless server will
 *       quietly diverge.
 *   <li>Exceptions from {@link #invokeLater} are logged and the thread keeps running, matching the
 *       EDT. A bare {@code ExecutorService.execute} would instead let one failing task kill the
 *       worker, which for callers moving over from {@code invokeLater} would be a behaviour change.
 *   <li>{@link #invokeAndWait} runs inline when the caller is already on the model thread, so it
 *       cannot deadlock against itself. Calling it from a task that is itself waiting on the model
 *       thread still can, as with {@code EventQueue.invokeAndWait}.
 * </ul>
 */
public final class ModelThread {
  private static final Logger log = LogManager.getLogger(ModelThread.class);
  private static final String THREAD_NAME = "maptool-model";

  private static ExecutorService executor;
  private static volatile Thread thread;

  private ModelThread() {}

  private static boolean useEventQueue() {
    return !GraphicsEnvironment.isHeadless();
  }

  private static synchronized ExecutorService executor() {
    if (executor == null) {
      executor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread t = new Thread(runnable, THREAD_NAME);
                t.setDaemon(true);
                thread = t;
                return t;
              });
    }
    return executor;
  }

  /**
   * @return true if the caller is running on the model thread
   */
  public static boolean isModelThread() {
    if (useEventQueue()) {
      return EventQueue.isDispatchThread();
    }
    return Thread.currentThread() == thread;
  }

  /**
   * Schedule a task to run on the model thread. Tasks run in submission order.
   *
   * @param task the task to run
   */
  public static void invokeLater(Runnable task) {
    if (useEventQueue()) {
      EventQueue.invokeLater(task);
    } else {
      executor().execute(() -> runLogged(task));
    }
  }

  /**
   * Run a task on the model thread and wait for its result. If the caller is already on the model
   * thread the task runs immediately, so this cannot deadlock against itself.
   *
   * @param task the task to run
   * @return the task's result
   * @throws ExecutionException if the task throws
   * @throws InterruptedException if the caller is interrupted while waiting
   */
  public static <T> T invokeAndWait(Callable<T> task)
      throws ExecutionException, InterruptedException {
    FutureTask<T> future = new FutureTask<>(task);
    if (isModelThread()) {
      future.run();
    } else if (useEventQueue()) {
      try {
        EventQueue.invokeAndWait(future);
      } catch (InvocationTargetException e) {
        throw new ExecutionException(e.getCause());
      }
    } else {
      executor().execute(future);
    }
    return future.get();
  }

  private static void runLogged(Runnable task) {
    try {
      task.run();
    } catch (Throwable t) {
      log.error("Uncaught exception on model thread", t);
    }
  }
}
