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
      fixture.pull.moveTo(16 + step * 20);
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

    fixture.pull.moveTo(96);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(1, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(HapticPulseQueue.INTERVAL_MS - 1);
    assertEquals(1, fixture.pulseTimes.size());
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void exactTriggerDistanceIncludesTheFifthPulseAtFractionalScreenDensities() {
    for (float density : new float[] {.75f, 1.3f, 1.5f, 2.1f, 2.625f, 3.5f}) {
      Fixture fixture = new Fixture();
      PullHapticSteps scaled =
          new PullHapticSteps(fixture.queue, 16 * density, 96 * density, 2 * density);

      scaled.moveTo(96 * density);
      fixture.scheduler.advanceTo(2000);

      assertEquals(4, scaled.lastReachedStep());
      assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
    }
  }

  @Test
  public void extendingPullWhilePulsesAreQueuedDoesNotDuplicateTheScheduledWork() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(36);
    fixture.scheduler.advanceTo(20);
    fixture.pull.moveTo(96);
    fixture.pull.moveTo(96);
    assertEquals(1, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
  }

  @Test
  public void threeStepsRetractedWithoutReleaseCanAllPulseAgain() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(56);
    fixture.scheduler.advanceTo(200);
    assertEquals(Arrays.asList(0L, 40L, 80L), fixture.pulseTimes);

    fixture.pull.moveTo(0);
    fixture.scheduler.advanceTo(400);
    assertEquals(3, fixture.pulseTimes.size());

    fixture.pull.moveTo(56);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L, 80L, 400L, 440L, 480L), fixture.pulseTimes);
  }

  @Test
  public void fiveStepsRetractedWithoutReleaseCanAllPulseAgain() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(200);
    assertEquals(5, fixture.pulseTimes.size());

    fixture.pull.moveTo(0);
    fixture.scheduler.advanceTo(400);
    assertEquals(5, fixture.pulseTimes.size());

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(410);
    fixture.pull.moveTo(95);
    fixture.scheduler.advanceTo(2000);
    assertEquals(
        Arrays.asList(0L, 40L, 80L, 120L, 160L, 400L, 440L, 480L, 520L, 560L), fixture.pulseTimes);
  }

  @Test
  public void partialRetractionRearmsOnlyTheMarksThatTheFingerPassed() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(200);
    fixture.pull.moveTo(50);
    assertEquals(1, fixture.pull.lastReachedStep());
    assertEquals(5, fixture.pulseTimes.size());

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L, 200L, 240L, 280L), fixture.pulseTimes);
  }

  @Test
  public void twoDpHysteresisPreventsRepeatedTicksFromThresholdJitter() {
    for (int step = 0; step < 5; step++) {
      Fixture fixture = new Fixture();
      float threshold = 16 + step * 20;
      fixture.pull.moveTo(threshold);
      fixture.scheduler.advanceTo(200);
      assertEquals(step + 1, fixture.pulseTimes.size());

      for (int bounce = 0; bounce < 4; bounce++) {
        fixture.pull.moveTo(threshold - 1.9f);
        fixture.pull.moveTo(threshold);
      }
      fixture.scheduler.advanceTo(400);
      assertEquals("Small motion must not re-arm the mark", step + 1, fixture.pulseTimes.size());

      fixture.pull.moveTo(threshold - 2);
      assertEquals(step - 1, fixture.pull.lastReachedStep());
      fixture.pull.moveTo(threshold);
      fixture.scheduler.advanceTo(600);
      assertEquals(
          "Returning below the dead band re-arms just this mark",
          step + 2,
          fixture.pulseTimes.size());
    }
  }

  @Test
  public void retractionDiscardsOldPulsesInsteadOfVibratingWhileTheArcCloses() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(10);
    fixture.pull.moveTo(94);
    assertEquals(0, fixture.scheduler.pendingCount());
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
  }

  @Test
  public void oneDpJitterAfterAFastFullPullKeepsAllFiveEarnedPulses() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(10);
    fixture.pull.moveTo(95);
    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L), fixture.pulseTimes);
  }

  @Test
  public void severalSmallRetractionsPastTwoDpDiscardTheOldQueue() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(10);
    for (float distance : new float[] {95.4f, 95.6f, 94.9f, 95.1f, 94.3f, 94.5f}) {
      fixture.pull.moveTo(distance);
      assertEquals(1, fixture.scheduler.pendingCount());
    }
    fixture.pull.moveTo(93.9f);
    assertEquals(0, fixture.scheduler.pendingCount());
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
  }

  @Test
  public void reversingAndRecrossingQuicklyPreservesTheMinimumPulseInterval() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(10);
    fixture.pull.moveTo(0);
    fixture.scheduler.advanceTo(20);
    fixture.pull.moveTo(96);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(1, fixture.scheduler.pendingCount());
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L, 80L, 120L, 160L, 200L), fixture.pulseTimes);
  }

  @Test
  public void cancellingDropsPendingPulsesAndTheNextGestureStartsImmediately() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(10);
    fixture.pull.reset();
    assertEquals(0, fixture.scheduler.pendingCount());
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);

    fixture.pull.moveTo(36);
    assertEquals(Arrays.asList(0L, 10L), fixture.pulseTimes);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 10L, 50L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void cancellingWithoutAnotherGestureLeavesNoDelayedPulse() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(50);
    fixture.pull.reset();
    fixture.pull.reset();
    fixture.scheduler.advanceTo(2000);

    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());
  }

  @Test
  public void aRejectedPulseDisablesRetriesForTheRestOfTheGesture() {
    Fixture fixture = new Fixture();
    fixture.acceptPulses.set(false);

    fixture.pull.moveTo(36);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.scheduler.advanceTo(200);
    fixture.acceptPulses.set(true);
    fixture.pull.moveTo(0);
    fixture.pull.moveTo(36);
    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(1000);
    assertEquals(Arrays.asList(0L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.pull.reset();
    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 1000L, 1040L, 1080L, 1120L, 1160L), fixture.pulseTimes);
  }

  @Test
  public void rejectionOfADelayedPulseStopsTheRemainingQueue() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(96);
    fixture.acceptPulses.set(false);
    fixture.scheduler.advanceTo(2000);
    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.acceptPulses.set(true);
    fixture.pull.moveTo(0);
    fixture.pull.moveTo(96);
    fixture.scheduler.advanceTo(3000);
    assertEquals(Arrays.asList(0L, 40L), fixture.pulseTimes);
  }

  @Test
  public void inputsBeforeFirstThresholdAreIgnoredAndOvershootStopsAtFive() {
    Fixture fixture = new Fixture();

    fixture.pull.moveTo(-1);
    fixture.pull.moveTo(15.99f);
    fixture.pull.moveTo(Float.NaN);
    fixture.pull.moveTo(Float.POSITIVE_INFINITY);
    fixture.scheduler.advanceTo(100);
    assertTrue(fixture.pulseTimes.isEmpty());
    assertEquals(0, fixture.scheduler.pendingCount());

    fixture.pull.moveTo(99);
    fixture.pull.moveTo(Float.MAX_VALUE);
    fixture.pull.moveTo(Float.POSITIVE_INFINITY);
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
    final PullHapticSteps pull = new PullHapticSteps(queue, 16, 96, 2);
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
