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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** When the dedicated server saves, and which backups it keeps. */
public class CampaignPersisterTest {

  private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
  private static final String NAME = "campaign.cmpgn";
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

  private static String bak(String stamp) {
    return NAME + "." + stamp + ".bak";
  }

  // --- when to save -------------------------------------------------------------------------

  @Test
  void savesWhenSomethingChanged() {
    assertTrue(CampaignPersister.isSaveDue(5, 4, 5 * MINUTE, 60));
  }

  @Test
  void skipsWhenNothingChanged() {
    assertFalse(CampaignPersister.isSaveDue(4, 4, 5 * MINUTE, 60));
  }

  @Test
  void savesAnywayOnceTheIdleLimitIsReached() {
    assertTrue(CampaignPersister.isSaveDue(4, 4, 60 * MINUTE, 60));
  }

  @Test
  void beforeTheFirstSaveAnUntouchedCampaignIsNotSaved() {
    assertFalse(CampaignPersister.isSaveDue(0, -1, 5 * MINUTE, 60));
  }

  @Test
  void beforeTheFirstSaveAChangedCampaignIsSaved() {
    assertTrue(CampaignPersister.isSaveDue(1, -1, 5 * MINUTE, 60));
  }

  @Test
  void anIdleLimitOfZeroSavesEveryInterval() {
    assertTrue(CampaignPersister.isSaveDue(4, 4, 5 * MINUTE, 0));
  }

  // --- which backups to keep ----------------------------------------------------------------

  @Test
  void keepsTheNewestAndNothingElseWithoutDailyBackups() {
    var names =
        List.of(bak("2026-10-13_10-00-00"), bak("2026-10-13_11-00-00"), bak("2026-10-13_12-00-00"));

    assertEquals(
        List.of(bak("2026-10-13_10-00-00")),
        CampaignPersister.namesToDelete(names, NAME, ".bak", 2, 0, TODAY));
  }

  @Test
  void keepsTheNewestBackupOfEachRecentDay() {
    var names =
        List.of(
            bak("2026-10-11_09-00-00"),
            bak("2026-10-11_21-00-00"), // newest of the 11th: kept
            bak("2026-10-12_08-00-00"),
            bak("2026-10-12_22-00-00"), // newest of the 12th: kept
            bak("2026-10-13_10-00-00"),
            bak("2026-10-13_11-00-00")); // newest overall: kept as recent and as the 13th

    assertEquals(
        List.of(bak("2026-10-11_09-00-00"), bak("2026-10-12_08-00-00"), bak("2026-10-13_10-00-00")),
        CampaignPersister.namesToDelete(names, NAME, ".bak", 1, 14, TODAY));
  }

  @Test
  void dropsDailyBackupsOlderThanTheWindow() {
    var names =
        List.of(
            bak("2026-09-28_12-00-00"), // 16 days ago: outside a 14-day window
            bak("2026-09-30_12-00-00"), // 14th day back, counting today: kept
            bak("2026-10-13_12-00-00"));

    assertEquals(
        List.of(bak("2026-09-28_12-00-00")),
        CampaignPersister.namesToDelete(names, NAME, ".bak", 1, 14, TODAY));
  }

  @Test
  void anIdleServerKeepsAWeekOfHistory() {
    // One save an hour for a week, as an idle server now makes: 24 recent plus one a day.
    List<String> names = new ArrayList<>();
    for (int day = 7; day <= 13; day++) {
      for (int hour = 0; hour < 24; hour++) {
        names.add(bak(String.format("2026-10-%02d_%02d-00-00", day, hour)));
      }
    }

    var deleted = CampaignPersister.namesToDelete(names, NAME, ".bak", 24, 14, TODAY);
    var kept = new ArrayList<>(names);
    kept.removeAll(deleted);

    assertEquals(24 + 6, kept.size(), "24 recent (all of today) plus one for each earlier day");
    assertTrue(kept.contains(bak("2026-10-07_23-00-00")));
  }

  @Test
  void leavesOtherFilesAlone() {
    var names =
        List.of(
            bak("2026-10-13_10-00-00"),
            "campaign.cmpgn",
            "other.cmpgn.2026-10-01_00-00-00.bak",
            NAME + ".not-a-stamp.bak",
            NAME + ".2026-10-01_00-00-00.startup");

    assertEquals(List.of(), CampaignPersister.namesToDelete(names, NAME, ".bak", 1, 0, TODAY));
  }

  @Test
  void keepingZeroOrLessKeepsEverything() {
    var names = List.of(bak("2026-01-01_00-00-00"), bak("2026-10-13_10-00-00"));

    assertEquals(List.of(), CampaignPersister.namesToDelete(names, NAME, ".bak", 0, 0, TODAY));
  }

  @Test
  void startupCopiesArePrunedSeparately() {
    var names =
        List.of(
            NAME + ".2026-10-10_00-00-00.startup",
            NAME + ".2026-10-11_00-00-00.startup",
            NAME + ".2026-10-12_00-00-00.startup",
            bak("2026-01-01_00-00-00"));

    assertEquals(
        List.of(NAME + ".2026-10-10_00-00-00.startup"),
        CampaignPersister.namesToDelete(names, NAME, ".startup", 2, 0, TODAY));
  }
}
