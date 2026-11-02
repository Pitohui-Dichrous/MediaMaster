package io.mediamaster.app.player;

import static io.mediamaster.app.player.PlaybackEntryFeedbackPolicy.Action.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class PlaybackEntryFeedbackPolicyTest {
  @Test
  public void ordinaryViewingRecordsOnceStrictlyAfterThreeSeconds() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    PlaybackEntryFeedbackPolicy policy = new PlaybackEntryFeedbackPolicy(tracker);
    policy.start(false);
    assertEquals(NONE, policy.update(true, 100));
    assertEquals(NONE, policy.update(true, 3100));
    assertEquals(RECORD_PLAY, policy.update(true, 3101));
    assertEquals(NONE, policy.update(true, 20_000));
    assertEquals(NONE, policy.depart(true, 20_001));
    assertTrue(tracker.recorded());
  }

  @Test
  public void searchViewingLikesInsteadOfRecordingPlayStrictlyAfterThreeSeconds() {
    PlaybackEntryFeedbackPolicy policy = policy(true);
    assertEquals(NONE, policy.update(true, 0));
    assertEquals(NONE, policy.update(true, 3000));
    assertEquals(LIKE, policy.update(true, 3001));
    assertEquals(NONE, policy.update(true, 60_000));
    assertEquals(NONE, policy.depart(true, 60_001));
  }

  @Test
  public void quickSkipBoundaryIsStrictAndZeroTimeStillAllowsExplicitSkip() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    assertEquals(DISLIKE, policy.depart(true, 0));
    policy.start(false);
    policy.update(true, 100);
    assertEquals(DISLIKE, policy.depart(true, 1599));
    policy.start(false);
    policy.update(true, 100);
    assertEquals(NONE, policy.depart(true, 1600));
    policy.start(false);
    policy.update(true, 100);
    assertEquals(NONE, policy.depart(true, 3100));
  }

  @Test
  public void searchEntriesNeverCreateQuickSkipDislikes() {
    PlaybackEntryFeedbackPolicy policy = policy(true);
    assertEquals(NONE, policy.depart(true, 0));
    policy.start(true);
    policy.update(true, 200);
    assertEquals(NONE, policy.depart(true, 1699));
    policy.start(true);
    policy.update(true, 200);
    assertEquals(NONE, policy.depart(true, 3200));
  }

  @Test
  public void naturalEndAndFailureDoNotCreateQuickSkipDislikes() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    assertEquals(NONE, policy.depart(false, 0));
    policy.start(false);
    policy.update(true, 0);
    assertEquals(NONE, policy.depart(false, 1499));
    policy.start(false);
    policy.update(true, 0);
    assertEquals(RECORD_PLAY, policy.depart(false, 3001));
    policy.start(true);
    policy.update(true, 0);
    assertEquals(LIKE, policy.depart(false, 3001));
  }

  @Test
  public void departureSettlesTheLastIntervalBeforeDeciding() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    policy.update(true, 1_000);
    assertEquals(NONE, policy.update(true, 2_499));
    assertEquals(NONE, policy.depart(true, 2_500));
    policy.start(true);
    policy.update(true, 10_000);
    assertEquals(NONE, policy.update(true, 13_000));
    assertEquals(LIKE, policy.depart(true, 13_001));
  }

  @Test
  public void pauseLoadingBackgroundAndRotationAreNotDeparturesOrViewing() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    PlaybackEntryFeedbackPolicy policy = new PlaybackEntryFeedbackPolicy(tracker);
    policy.start(false);
    policy.update(true, 100);
    assertEquals(NONE, policy.update(false, 900));
    assertFalse(policy.departed());
    assertEquals(NONE, policy.update(false, 50_000));
    assertEquals(800, tracker.watchedMs());
    policy.restore(policy.fromSearch(), tracker.watchedMs(), tracker.recorded(), policy.departed());
    assertEquals(NONE, policy.update(false, 90_000));
    assertEquals(NONE, policy.update(true, 90_000));
    assertEquals(NONE, policy.update(false, 90_700));
    assertEquals(1500, tracker.watchedMs());
    assertEquals(NONE, policy.depart(true, 100_000));
  }

  @Test
  public void repeatedDepartAndLateCallbacksCannotCreateAnotherAction() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    policy.update(true, 0);
    assertEquals(DISLIKE, policy.depart(true, 1400));
    assertTrue(policy.departed());
    assertEquals(NONE, policy.depart(true, 1400));
    assertEquals(NONE, policy.depart(false, 50_000));
    assertEquals(NONE, policy.update(true, 50_000));
    assertEquals(NONE, policy.update(true, 60_000));
  }

  @Test
  public void nextVideoDoesNotInheritSearchExemption() {
    PlaybackEntryFeedbackPolicy policy = policy(true);
    policy.update(true, 0);
    assertEquals(NONE, policy.depart(true, 500));
    policy.start(false);
    assertFalse(policy.fromSearch());
    assertFalse(policy.departed());
    policy.update(true, 500);
    assertEquals(DISLIKE, policy.depart(true, 1000));
  }

  @Test
  public void restoredQualificationAndDepartureNeverReplayAnAction() {
    PlaybackWatchTracker tracker = new PlaybackWatchTracker();
    PlaybackEntryFeedbackPolicy policy = new PlaybackEntryFeedbackPolicy(tracker);
    policy.restore(true, 3001, true, false);
    assertEquals(NONE, policy.update(true, 1_000));
    assertEquals(NONE, policy.update(true, 90_000));
    assertEquals(NONE, policy.depart(true, 100_000));
    policy.restore(false, 700, false, true);
    assertEquals(NONE, policy.depart(true, 100_000));
    assertEquals(NONE, policy.update(true, 100_001));
    assertEquals(700, tracker.watchedMs());
  }

  @Test
  public void restoredSearchEntryFinishesOnlyItsRemainingQualifiedTime() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    policy.restore(true, 2000, false, false);
    assertTrue(policy.fromSearch());
    assertEquals(NONE, policy.update(false, 100_000));
    assertEquals(NONE, policy.update(true, 200_000));
    assertEquals(NONE, policy.update(true, 201_000));
    assertEquals(LIKE, policy.update(false, 201_001));
  }

  @Test
  public void extremeTimesAndRestoredValuesCannotOverflowOrReverseAnExistingQualification() {
    PlaybackEntryFeedbackPolicy policy = policy(false);
    policy.restore(false, Long.MAX_VALUE, false, false);
    assertEquals(RECORD_PLAY, policy.depart(true, 0));
    policy.restore(false, 0, true, false);
    assertEquals(NONE, policy.depart(true, 0));
    policy.restore(true, Long.MIN_VALUE, false, false);
    assertEquals(NONE, policy.update(true, 0));
    assertEquals(LIKE, policy.depart(true, Long.MAX_VALUE));
    policy.start(false);
    assertEquals(NONE, policy.update(true, -1));
    assertEquals(NONE, policy.update(true, Long.MAX_VALUE - 1));
    assertEquals(DISLIKE, policy.depart(true, Long.MAX_VALUE));
  }

  private static PlaybackEntryFeedbackPolicy policy(boolean fromSearch) {
    PlaybackEntryFeedbackPolicy policy =
        new PlaybackEntryFeedbackPolicy(new PlaybackWatchTracker());
    policy.start(fromSearch);
    return policy;
  }
}
