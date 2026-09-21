/*
 * This software is copyrighted by the RPTools.net development team, and
 * is licensed under the Affero GPL version 3.0.
 */
package net.rptools.maptool.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.rptools.maptool.client.EventDispatcher;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.util.PersistenceUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically writes the server's campaign to disk, and once more on shutdown.
 *
 * <p>A GUI MapTool has a GM who can hit save; a headless server has nobody, so without this a crash
 * or restart loses the whole session. Saves are written to a temp file and then moved into place, so
 * an interrupted save cannot destroy the previous good copy.
 *
 * <p>The campaign model is not thread safe and is only modified on the model thread (the EDT in a
 * GUI, the dispatch thread when headless). This follows the same pattern as {@code
 * AutoSaveManager}: the campaign is copied on the model thread, and only the copy is serialized on
 * the autosave thread, so incoming changes cannot race the save.
 */
public class CampaignPersister {
  private static final Logger log = LogManager.getLogger(CampaignPersister.class);
  private static final long COPY_TIMEOUT_SECONDS = 60;
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

  private final MapToolServer server;
  private final File target;
  private final int intervalMinutes;
  private final boolean keepBackups;
  private ScheduledExecutorService scheduler;

  public CampaignPersister(
      MapToolServer server, File target, int intervalMinutes, boolean keepBackups) {
    this.server = server;
    this.target = target;
    this.intervalMinutes = intervalMinutes;
    this.keepBackups = keepBackups;
  }

  /** Begin periodic saves. No-op if the interval is zero or negative. */
  public void start() {
    if (intervalMinutes <= 0) {
      log.info("Autosave disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "maptool-autosave");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::saveQuietly, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    log.info("Autosaving campaign to {} every {} minute(s)", target, intervalMinutes);
  }

  /** Stop periodic saves and write one final copy. */
  public void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    log.info("Writing final campaign save");
    saveQuietly();
  }

  private void saveQuietly() {
    try {
      save();
    } catch (Exception e) {
      // Never let a failed save kill the scheduler or block shutdown.
      log.error("Campaign save failed", e);
    }
  }

  /** Write the campaign, atomically replacing any previous copy. */
  public synchronized void save() throws IOException {
    Campaign campaign = copyOnModelThread();
    if (campaign == null) {
      log.warn("No campaign to save");
      return;
    }

    Path dir = target.getAbsoluteFile().toPath().getParent();
    Files.createDirectories(dir);

    File tmp = File.createTempFile("campaign-", ".tmp", dir.toFile());
    long start = System.currentTimeMillis();
    try {
      PersistenceUtil.saveCampaign(campaign, tmp);

      if (keepBackups && target.exists()) {
        File backup =
            new File(
                dir.toFile(), target.getName() + "." + LocalDateTime.now().format(STAMP) + ".bak");
        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }

      Files.move(
          tmp.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      log.info(
          "Saved campaign to {} ({} ms, {} zones)",
          target,
          System.currentTimeMillis() - start,
          campaign.getZones().size());
    } finally {
      if (tmp.exists() && !tmp.delete()) {
        log.debug("Could not remove temp save file {}", tmp);
      }
    }
  }

  /**
   * Copy the server's campaign on the model thread and wait for the copy.
   *
   * @return a snapshot safe to serialize off the model thread, or null if there is no campaign
   */
  private Campaign copyOnModelThread() throws IOException {
    CompletableFuture<Campaign> copy = new CompletableFuture<>();
    EventDispatcher.invokeLater(
        () -> {
          try {
            Campaign live = server.getCampaign();
            copy.complete(live == null ? null : new Campaign(live));
          } catch (Throwable t) {
            copy.completeExceptionally(t);
          }
        });
    long start = System.currentTimeMillis();
    try {
      Campaign result = copy.get(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      log.debug("Copied campaign on model thread in {} ms", System.currentTimeMillis() - start);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for campaign copy", e);
    } catch (ExecutionException e) {
      throw new IOException("Campaign copy failed", e.getCause());
    } catch (TimeoutException e) {
      throw new IOException("Timed out waiting for the model thread to copy the campaign", e);
    }
  }
}
