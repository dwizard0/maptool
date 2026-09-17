/*
 * This software is copyrighted by the RPTools.net development team, and
 * is licensed under the Affero GPL version 3.0.
 */
package net.rptools.maptool.client;

import java.awt.EventQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Serial task dispatcher used by server-side message handling.
 *
 * <p>In a normal windowed client this delegates to the AWT event dispatch thread, preserving the
 * single-threaded ordering guarantees the existing code relies on. In a headless server there is no
 * EDT, so tasks run on a dedicated single-threaded executor instead: same ordering, no display
 * required.
 *
 * <p>This exists so that server-side code does not have to know which it is running under.
 */
public class EventDispatcher {
  private static final Logger log = LogManager.getLogger(EventDispatcher.class);

  private static ExecutorService headlessQueue;

  private static synchronized ExecutorService headlessQueue() {
    if (headlessQueue == null) {
      headlessQueue =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "maptool-headless-dispatch");
                t.setDaemon(true);
                return t;
              });
    }
    return headlessQueue;
  }

  /**
   * Schedule a task to run serially, off the calling thread.
   *
   * @param task the task to run
   */
  public static void invokeLater(Runnable task) {
    if (MapTool.isHeadless()) {
      // The EDT logs uncaught exceptions and keeps running. A bare execute() would let one
      // bad task kill the worker thread, so match the EDT and swallow-with-logging here.
      headlessQueue()
          .execute(
              () -> {
                try {
                  task.run();
                } catch (Throwable t) {
                  log.error("Uncaught exception in dispatched task", t);
                }
              });
    } else {
      EventQueue.invokeLater(task);
    }
  }

  private EventDispatcher() {}
}
