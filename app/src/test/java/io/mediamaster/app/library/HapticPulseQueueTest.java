package io.mediamaster.app.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class HapticPulseQueueTest {
  @Test
  public void slowPullPulsesImmediatelyAtEachOfFiveDistanceThresholds() {
    Fixture fixture = new Fixture();

    for (int step = 0; step < 5; step++) {
      fixture.scheduler.advanceTo(step * 100L);
      fixture.queue.advanceTo(step);
      assertEquals(step + 1, fixture.pulseTimes.size());
      assertEquals(Long.valueOf(step * 100L), fixture.pulseTimes.get(step));
      assertEquals(0, fixture.scheduler.pendingCount());
    }

    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 100L, 200L, 300L, 400L), fixture.pulseTimes);
  }

  @Test
  public void aSingleMoveAcrossAllThresholdsProducesFiveSeparatedPulses() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(4);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(1, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(HapticPulseQueue.INTERVAL_MS - 1);
    assertEquals(1, fixture.pulseTimes.size());
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void extendingPullWhilePulsesAreQueuedDoesNotDuplicateTheScheduledWork() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(1);
    fixture.scheduler.advanceTo(20);
    fixture.queue.advanceTo(4);
    fixture.queue.advanceTo(4);
    assertEquals(1, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
  }

  @Test
  public void retractingAndCrossingAlreadyVisitedThresholdsDoesNotPulseAgain() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(2);
    fixture.queue.advanceTo(1);
    fixture.queue.advanceTo(0);
    fixture.queue.advanceTo(2);
    fixture.scheduler.advanceTo(200);
    assertEquals(Arrays.asList(0L, 40L, 80L), fixture.pulseTimes);

    fixture.queue.advanceTo(1);
    fixture.queue.advanceTo(2);
    fixture.scheduler.advanceTo(400);
    assertEquals(3, fixture.pulseTimes.size());

    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L, 80L, 400L, 440L), fixture.pulseTimes);
  }

  @Test
  public void cancellingDropsPendingPulsesAndTheNextGestureStartsImmediately() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(10);
    fixture.queue.cancel();
    assertEquals(0, fixture.scheduler.pendingCount());
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);

    fixture.queue.advanceTo(1);
    assertEquals(Arrays.asList(0L, 10L), fixture.pulseTimes);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 10L, 50L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void cancellingWithoutAnotherGestureLeavesNoDelayedPulse() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(50);
    fixture.queue.cancel();
    fixture.queue.cancel();
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void aRejectedPulseDisablesRetriesForTheRestOfTheGesture() {
    Fixture fixture = new Fixture();
    fixture.acceptPulses.set(false);

    fixture.queue.advanceTo(1);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(200);
    fixture.acceptPulses.set(true);
    fixture.queue.advanceTo(1);
    fixture.queue.advanceTo(0);
    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(1000);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.queue.cancel();
    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 1000L, 1040L, 1080L, 1120L, 1160L), fixture.pulseTimes);
  }

  @Test
  public void rejectionOfADelayedPulseStopsTheRemainingQueue() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(4);
    fixture.acceptPulses.set(false);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.acceptPulses.set(true);
    fixture.queue.advanceTo(4);
    fixture.scheduler.advanceTo(3000);
    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
  }

  @Test
  public void inputsBeforeFirstThresholdAreIgnoredAndOvershootStopsAtFive() {
    Fixture fixture = new Fixture();

    fixture.queue.advanceTo(-1);
    fixture.queue.advanceTo(Integer.MIN_VALUE);
    fixture.scheduler.advanceTo(100);
    assertTrue(fixture.pulseTimes.isEmpty());
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.queue.advanceTo(99);
    fixture.queue.advanceTo(Integer.MAX_VALUE);
    fixture.queue.advanceTo(-1);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(100L, 140L, 180L, 220L, 260L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  private static final class Fixture {
    final VirtualScheduler scheduler = new VirtualScheduler();
    final List<Long> pulseTimes = new ArrayList<>();
    final AtomicBoolean acceptPulses = new AtomicBoolean(true);
    final HapticPulseQueue queue =
        new HapticPulseQueue(
            scheduler,
            () -> {
              pulseTimes.add(scheduler.nowMillis());
              return acceptPulses.get();
            });
  }

  private static final class VirtualScheduler implements HapticPulseQueue.Scheduler {
    private final PriorityQueue<Job> jobs =
        new PriorityQueue<>(
            Comparator.comparingLong((Job job) -> job.atMillis)
                .thenComparingLong(job -> job.order));
    private long now;
    private long nextOrder;

    @Override
    public long nowMillis() {
      return now;
    }

    @Override
    public void postDelayed(Runnable action, long delayMillis) {
      assertTrue("A scheduled delay cannot be negative", delayMillis >= 0);
      jobs.add(new Job(now + delayMillis, nextOrder++, action));
    }

    @Override
    public void remove(Runnable action) {
      jobs.removeIf(job -> job.action == action);
    }

    int pendingCount() {
      return jobs.size();
    }

    void advanceTo(long targetMillis) {
      assertTrue("Virtual time must move forwards", targetMillis >= now);
      int executed = 0;
      while (!jobs.isEmpty() && jobs.peek().atMillis <= targetMillis) {
        assertTrue("The queue must not reschedule indefinitely", ++executed <= 100);
        Job job = jobs.remove();
        now = job.atMillis;
        job.action.run();
      }
      now = targetMillis;
    }
  }

  private static final class Job {
    final long atMillis;
    final long order;
    final Runnable action;

    Job(long atMillis, long order, Runnable action) {
      this.atMillis = atMillis;
      this.order = order;
      this.action = action;
    }
  }
}
