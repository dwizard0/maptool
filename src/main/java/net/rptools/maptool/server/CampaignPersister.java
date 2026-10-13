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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.rptools.lib.ModelThread;
import net.rptools.maptool.model.Campaign;
import net.rptools.maptool.util.PersistenceUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically writes the server's campaign to disk, and once more on shutdown.
 *
 * <p>A GUI MapTool has a GM who can hit save; a headless server has nobody, so without this a crash
 * or restart loses the whole session. Saves are written to a temp file and then moved into place,
 * so an interrupted save cannot destroy the previous good copy.
 *
 * <p>The campaign model is not thread safe and is only modified on the model thread (the EDT in a
 * GUI, the dispatch thread when headless). This follows the same pattern as {@code
 * AutoSaveManager}: the campaign is copied on the model thread, and only the copy is serialized on
 * the autosave thread, so incoming changes cannot race the save.
 *
 * <p><b>When it saves.</b> A timed save is skipped if no client message has changed the campaign
 * since the last one (see {@link MapToolServer#noteCampaignChange()}). An idle server would
 * otherwise write an identical campaign every interval, and each save pushes an older backup out.
 * As a safety net against a change that was not counted, the campaign is still saved at least once
 * every {@code idleSaveMinutes}, changed or not. The save on shutdown always happens.
 *
 * <p><b>Backups.</b> Before each save replaces the campaign file, the previous file is kept as
 * {@code <name>.<timestamp>.bak}. Retention keeps the newest {@code backupsToKeep} of those, plus
 * the newest one from each of the last {@code dailyBackups} days, so a mistake noticed days later
 * can still be undone. When the server starts, the campaign file is also copied to {@code
 * <name>.<timestamp>.startup} before anything is written; the newest {@value
 * #STARTUP_BACKUPS_TO_KEEP} of those are kept.
 */
public class CampaignPersister {
  private static final Logger log = LogManager.getLogger(CampaignPersister.class);
  private static final long COPY_TIMEOUT_SECONDS = 60;
  private static final int DEFAULT_BACKUPS_TO_KEEP = 24;
  private static final int DEFAULT_DAILY_BACKUPS = 14;
  private static final int DEFAULT_IDLE_SAVE_MINUTES = 60;
  private static final int STARTUP_BACKUPS_TO_KEEP = 5;
  private static final String STAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss";
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern(STAMP_PATTERN);

  private final MapToolServer server;
  private final File target;
  private final int intervalMinutes;
  private final boolean keepBackups;
  private final int backupsToKeep;
  private final int dailyBackups;
  private final int idleSaveMinutes;
  private ScheduledExecutorService scheduler;

  /** The server's change count when the last save started; -1 before the first save. */
  private long changesAtLastSave = -1;

  /** When the last save finished, in milliseconds; the time of start-up before the first. */
  private long lastSaveMillis = System.currentTimeMillis();

  public CampaignPersister(
      MapToolServer server, File target, int intervalMinutes, boolean keepBackups) {
    this(server, target, intervalMinutes, keepBackups, DEFAULT_BACKUPS_TO_KEEP);
  }

  public CampaignPersister(
      MapToolServer server,
      File target,
      int intervalMinutes,
      boolean keepBackups,
      int backupsToKeep) {
    this(
        server,
        target,
        intervalMinutes,
        keepBackups,
        backupsToKeep,
        DEFAULT_DAILY_BACKUPS,
        DEFAULT_IDLE_SAVE_MINUTES);
  }

  /**
   * @param backupsToKeep how many of the newest timestamped backups to retain. Zero or less keeps
   *     every backup, which grows without bound.
   * @param dailyBackups additionally keep the newest backup from each of this many recent days,
   *     today included. Zero or less keeps no extra daily backups.
   * @param idleSaveMinutes save at least this often even when nothing has changed. Zero or less
   *     saves every interval, changed or not, as before.
   */
  public CampaignPersister(
      MapToolServer server,
      File target,
      int intervalMinutes,
      boolean keepBackups,
      int backupsToKeep,
      int dailyBackups,
      int idleSaveMinutes) {
    this.server = server;
    this.target = target;
    this.intervalMinutes = intervalMinutes;
    this.keepBackups = keepBackups;
    this.backupsToKeep = backupsToKeep;
    this.dailyBackups = dailyBackups;
    this.idleSaveMinutes = idleSaveMinutes;
  }

  /**
   * Copy the campaign file aside before the server writes anything, then begin periodic saves.
   * Periodic saves are not started if the interval is zero or negative.
   */
  public void start() {
    if (keepBackups) {
      backUpAtStartup();
    }
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
        this::saveIfDue, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    log.info(
        "Autosaving campaign to {} every {} minute(s) when it has changed, and at least every {}"
            + " minute(s); keeping {} recent backup(s) and one a day for {} day(s)",
        target,
        intervalMinutes,
        idleSaveMinutes,
        backupsToKeep,
        dailyBackups);
  }

  /** Stop periodic saves and write one final copy, whether or not anything changed. */
  public void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    log.info("Writing final campaign save");
    try {
      save();
    } catch (Exception e) {
      // Never block shutdown on a failed save.
      log.error("Campaign save failed", e);
    }
  }

  /** The timed save: skipped when nothing has changed, unless the idle limit has been reached. */
  private void saveIfDue() {
    try {
      if (!isSaveDue(
          server.getCampaignChangeCount(),
          changesAtLastSave,
          System.currentTimeMillis() - lastSaveMillis,
          idleSaveMinutes)) {
        log.debug("Campaign unchanged since the last save; not saving");
        return;
      }
      save();
    } catch (Exception e) {
      // Never let a failed save kill the scheduler.
      log.error("Campaign save failed", e);
    }
  }

  /**
   * Decides whether a timed save should write the campaign.
   *
   * @param changes the server's change count now
   * @param changesAtLastSave the change count when the last save started, or -1 if there has been
   *     none
   * @param millisSinceLastSave how long since the last save finished (or since start-up)
   * @param idleSaveMinutes save at least this often even when unchanged; zero or less means always
   * @return whether to save
   */
  static boolean isSaveDue(
      long changes, long changesAtLastSave, long millisSinceLastSave, int idleSaveMinutes) {
    if (idleSaveMinutes <= 0) {
      return true;
    }
    if (changes != changesAtLastSave && changes > 0) {
      return true;
    }
    return millisSinceLastSave >= TimeUnit.MINUTES.toMillis(idleSaveMinutes);
  }

  /** Write the campaign, atomically replacing any previous copy. */
  public synchronized void save() throws IOException {
    // Read the count before copying: a change counted after this point may or may not make it into
    // this copy, and must be saved again next time.
    long changes = server.getCampaignChangeCount();
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
        pruneBackups(dir.toFile());
      }
      Files.move(
          tmp.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      changesAtLastSave = changes;
      lastSaveMillis = System.currentTimeMillis();
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
   * Copy the campaign file aside before the server writes anything.
   *
   * <p>The ordinary backups are only taken when a save replaces the file, and retention eventually
   * removes them. This keeps the state the server started from, so that a bad load or a crash
   * during the first save can always be undone.
   */
  private void backUpAtStartup() {
    if (!target.exists()) {
      return;
    }
    File dir = target.getAbsoluteFile().getParentFile();
    File copy =
        new File(dir, target.getName() + "." + LocalDateTime.now().format(STAMP) + ".startup");
    try {
      Files.copy(target.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
      log.info("Copied the campaign to {} before starting", copy.getName());
    } catch (IOException e) {
      log.error("Could not copy the campaign aside before starting", e);
      return;
    }
    List<String> startups = listNames(dir, ".startup");
    for (String name :
        namesToDelete(
            startups, target.getName(), ".startup", STARTUP_BACKUPS_TO_KEEP, 0, LocalDate.now())) {
      deleteQuietly(new File(dir, name));
    }
  }

  /**
   * Copy the server's campaign on the model thread and wait for the copy.
   *
   * @return a snapshot safe to serialize off the model thread, or null if there is no campaign
   */
  private Campaign copyOnModelThread() throws IOException {
    CompletableFuture<Campaign> copy = new CompletableFuture<>();
    ModelThread.invokeLater(
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

  /**
   * Delete the backups that retention no longer keeps.
   *
   * <p>A server saves on a timer and nobody is watching it, so without this the backups accumulate
   * for as long as the server runs.
   *
   * @param dir the directory holding the backups
   */
  private void pruneBackups(File dir) {
    List<String> toDelete =
        namesToDelete(
            listNames(dir, ".bak"),
            target.getName(),
            ".bak",
            backupsToKeep,
            dailyBackups,
            LocalDate.now());
    for (String name : toDelete) {
      deleteQuietly(new File(dir, name));
    }
    if (!toDelete.isEmpty()) {
      log.info(
          "Pruned {} old campaign backup(s), keeping {} recent and one a day for {} day(s)",
          toDelete.size(),
          backupsToKeep,
          dailyBackups);
    }
  }

  private List<String> listNames(File dir, String suffix) {
    String[] found =
        dir.list((d, name) -> name.startsWith(target.getName() + ".") && name.endsWith(suffix));
    return found == null ? List.of() : List.of(found);
  }

  private static void deleteQuietly(File file) {
    if (file.delete()) {
      log.debug("Removed old campaign backup {}", file.getName());
    } else {
      log.warn("Could not remove old campaign backup {}", file);
    }
  }

  /**
   * Works out which backups retention removes.
   *
   * <p>Kept are the newest {@code keepRecent} backups, and the newest backup from each of the last
   * {@code keepDays} days, today included. Everything else with the right name is returned. A file
   * whose timestamp cannot be read is never deleted.
   *
   * @param names file names in the backup directory
   * @param campaignName the campaign file's name; backups are {@code
   *     <campaignName>.<stamp><suffix>}
   * @param suffix {@code .bak} or {@code .startup}
   * @param keepRecent how many of the newest to keep; zero or less keeps everything
   * @param keepDays keep the newest backup from each of this many recent days
   * @param today the current date
   * @return the names to delete, oldest first
   */
  static List<String> namesToDelete(
      List<String> names,
      String campaignName,
      String suffix,
      int keepRecent,
      int keepDays,
      LocalDate today) {
    if (keepRecent <= 0) {
      return List.of();
    }
    String prefix = campaignName + ".";
    List<String> dated = new ArrayList<>();
    for (String name : names) {
      if (name.startsWith(prefix)
          && name.endsWith(suffix)
          && stampOf(name, prefix, suffix) != null) {
        dated.add(name);
      }
    }
    // The stamps sort chronologically as text, so this orders oldest first without touching the
    // filesystem's modification times, which a copy or a restore can rewrite.
    dated.sort(null);

    Set<String> keep = new HashSet<>();
    for (int i = Math.max(0, dated.size() - keepRecent); i < dated.size(); i++) {
      keep.add(dated.get(i));
    }
    if (keepDays > 0) {
      LocalDate oldestDay = today.minusDays(keepDays - 1L);
      Set<LocalDate> daysCovered = new HashSet<>();
      // Newest first, so the first backup seen for a day is that day's newest.
      for (int i = dated.size() - 1; i >= 0; i--) {
        LocalDate day = stampOf(dated.get(i), prefix, suffix).toLocalDate();
        if (!day.isBefore(oldestDay) && daysCovered.add(day)) {
          keep.add(dated.get(i));
        }
      }
    }

    List<String> delete = new ArrayList<>();
    for (String name : dated) {
      if (!keep.contains(name)) {
        delete.add(name);
      }
    }
    return delete;
  }

  private static LocalDateTime stampOf(String name, String prefix, String suffix) {
    if (name.length() != prefix.length() + STAMP_PATTERN.length() + suffix.length()) {
      return null;
    }
    try {
      return LocalDateTime.parse(
          name.substring(prefix.length(), name.length() - suffix.length()), STAMP);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
