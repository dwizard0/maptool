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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * These assertions hold whether or not a display is available, so the test is meaningful both on a
 * developer desktop and on a headless CI runner.
 */
public class ModelThreadTest {

  @Test
  void tasksRunInSubmissionOrder() throws InterruptedException {
    final int taskCount = 200;
    List<Integer> executed = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(taskCount);

    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      ModelThread.invokeLater(
          () -> {
            executed.add(index);
            done.countDown();
          });
    }

    assertTrue(done.await(10, TimeUnit.SECONDS), "tasks did not complete");
    for (int i = 0; i < taskCount; i++) {
      assertEquals(i, executed.get(i), "task " + i + " ran out of order");
    }
  }

  @Test
  void allTasksRunOnOneThreadThatIsTheModelThread() throws InterruptedException {
    final int taskCount = 50;
    List<Thread> threads = new CopyOnWriteArrayList<>();
    List<Boolean> onModelThread = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(taskCount);

    for (int i = 0; i < taskCount; i++) {
      ModelThread.invokeLater(
          () -> {
            threads.add(Thread.currentThread());
            onModelThread.add(ModelThread.isModelThread());
            done.countDown();
          });
    }

    assertTrue(done.await(10, TimeUnit.SECONDS), "tasks did not complete");
    assertFalse(ModelThread.isModelThread(), "test thread must not be the model thread");
    for (int i = 0; i < taskCount; i++) {
      assertSame(threads.get(0), threads.get(i), "task " + i + " ran on a different thread");
      assertTrue(onModelThread.get(i), "task " + i + " did not see itself on the model thread");
    }
  }

  @Test
  void aFailingTaskDoesNotStopTheQueue() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    ModelThread.invokeLater(
        () -> {
          throw new IllegalStateException("expected by test");
        });
    ModelThread.invokeLater(done::countDown);
    assertTrue(done.await(10, TimeUnit.SECONDS), "queue stopped after a failing task");
  }

  @Test
  void invokeAndWaitReturnsResultAndPropagatesFailure() throws Exception {
    assertEquals(42, ModelThread.invokeAndWait(() -> 42));
    assertTrue(ModelThread.invokeAndWait(ModelThread::isModelThread));

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () ->
                ModelThread.invokeAndWait(
                    () -> {
                      throw new IllegalStateException("expected by test");
                    }));
    assertTrue(e.getCause() instanceof IllegalStateException);
  }

  @Test
  void invokeAndWaitFromTheModelThreadDoesNotDeadlock() throws Exception {
    int result = ModelThread.invokeAndWait(() -> ModelThread.invokeAndWait(() -> 7));
    assertEquals(7, result);
  }
}
