package io.mediamaster.app.player;

import static org.junit.Assert.*;

import org.junit.Test;

public final class CommittedSeekHealthTest {
  private final CommittedSeekHealth health = new CommittedSeekHealth();

  private boolean settled(long start, long delay) {
    health.begin(start);
    for (long at = start + 100; at < start + delay; at += 100) health.probe(at);
    return health.acknowledged(start + delay);
  }

  @Test
  public void threeSlowSettledSeeksAreNeededAndAHealthySeekBreaksTheStreak() {
    assertFalse(settled(0, 650));
    assertFalse(settled(1000, 900));
    assertFalse(settled(2500, 649));
    assertFalse(settled(4000, 650));
    assertFalse(settled(5000, 650));
    assertTrue(settled(6000, 650));
  }

  @Test
  public void delayedUiAndSupersededTargetsCannotCountAsSlowDecoderResponses() {
    assertFalse(settled(0, 700));
    health.begin(1000);
    assertFalse(health.acknowledged(1900));
    health.begin(2000);
    health.probe(2200);
    health.discardSample();
    assertFalse(health.acknowledged(2900));
    assertFalse(settled(3000, 700));
    assertTrue(settled(4000, 700));
  }

  @Test
  public void releasedPreviewMeasuresOnlyTheRemainingWaitAndCannotCountTwice() {
    assertFalse(settled(0, 700));
    assertFalse(settled(1000, 700));
    health.begin(2000);
    health.probe(2250);
    health.probe(2500);
    health.begin(2650);
    assertFalse(health.acknowledged(2700));
    assertFalse(health.acknowledged(3200));
    assertFalse(settled(3300, 700));
  }

  @Test
  public void staleWindowsAndLifecycleChangesDiscardOldEvidence() {
    assertFalse(settled(0, 700));
    assertFalse(settled(1000, 700));
    assertFalse(settled(40_000, 700));
    assertFalse(settled(41_000, 700));
    health.reset();
    assertFalse(settled(42_000, 700));
  }
}
