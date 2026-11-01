package io.mediamaster.app.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

/**
 * Complete shuffle rounds with bounded reversible navigation; portrait repeat stays independent.
 */
public final class PlaybackOrder {
  public static final int SEQUENTIAL = 0;
  public static final int SINGLE = 1;
  public static final int SHUFFLE = 2;
  private final int count;
  private final Random random;
  private final Random reverseRandom;
  private final IntToDoubleFunction weights;
  private final ArrayList<ArrayList<Integer>> rounds = new ArrayList<>(3);
  private int roundCursor;
  private int cursor;
  private int current;
  private int mode;
  private int portraitNavigationMode;
  private boolean portraitMode;

  public PlaybackOrder(int count, int initial, int mode) {
    this(count, initial, mode, new Random());
  }

  public PlaybackOrder(int count, int initial, int mode, Random random) {
    this(count, initial, mode, random, null);
  }

  /** The provider is read once per new round and must supply weights from memory without I/O. */
  public PlaybackOrder(
      int count, int initial, int mode, Random random, IntToDoubleFunction weights) {
    if (count < 1) throw new IllegalArgumentException("Queue must contain a video");
    this.count = count;
    this.random = random;
    this.weights = weights;
    // Preparing an unseen previous round must not consume forward random choices.
    reverseRandom = new Random(random.nextLong());
    current = Math.max(0, Math.min(count - 1, initial));
    this.mode = mode;
    portraitNavigationMode = mode == SHUFFLE ? SHUFFLE : SEQUENTIAL;
  }

  public int current() {
    return current;
  }

  public int mode() {
    return portraitMode ? portraitNavigationMode : mode;
  }

  /** The full-screen preference is unaffected by changes to portrait manual navigation. */
  public int fullScreenMode() {
    return mode;
  }

  public boolean portraitMode() {
    return portraitMode;
  }

  public int portraitNavigationMode() {
    return portraitNavigationMode;
  }

  /** Natural completion always loops in portrait, whichever order manual swipes use. */
  public boolean repeatsCurrent() {
    return portraitMode || mode == SINGLE;
  }

  public void setPortraitMode(boolean portraitMode) {
    boolean wasShuffle = isShuffle();
    this.portraitMode = portraitMode;
    if (wasShuffle != isShuffle()) resetShuffleOrder();
  }

  /**
   * Restores a portrait choice without entering portrait or changing the full-screen preference.
   */
  public void setPortraitNavigationMode(int mode) {
    boolean wasShuffle = isShuffle();
    // SINGLE is a full-screen completion policy, not a separate portrait swipe order.
    portraitNavigationMode = mode == SHUFFLE ? SHUFFLE : SEQUENTIAL;
    if (wasShuffle != isShuffle()) resetShuffleOrder();
  }

  /** Updates the mode for the active presentation only. */
  public void setMode(int mode) {
    if (portraitMode) {
      setPortraitNavigationMode(mode);
      return;
    }
    boolean wasShuffle = isShuffle();
    this.mode = Math.max(SEQUENTIAL, Math.min(SHUFFLE, mode));
    if (wasShuffle != isShuffle()) resetShuffleOrder();
  }

  private boolean isShuffle() {
    return mode() == SHUFFLE;
  }

  private void resetShuffleOrder() {
    rounds.clear();
    roundCursor = 0;
    cursor = 0;
  }

  /**
   * Returns a stable manual-navigation candidate without advancing. Single-repeat affects natural
   * completion only, so it still has a manual next candidate.
   */
  public int peekNext() {
    if (count == 1) return current;
    if (!isShuffle()) return (current + 1) % count;
    prepareInitialRound();
    if (cursor + 1 < count) return rounds.get(roundCursor).get(cursor + 1);
    prepareNextRound();
    return rounds.get(roundCursor + 1).get(0);
  }

  /** An unseen previous round is available even before the first forward navigation. */
  public int peekPrevious() {
    if (count == 1) return current;
    if (!isShuffle()) return current == 0 ? count - 1 : current - 1;
    prepareInitialRound();
    if (cursor > 0) return rounds.get(roundCursor).get(cursor - 1);
    preparePreviousRound();
    return rounds.get(roundCursor - 1).get(count - 1);
  }

  public int next(boolean automatic) {
    if (count == 1 || automatic && repeatsCurrent()) return current;
    if (!isShuffle()) {
      current = (current + 1) % count;
      return current;
    }
    int candidate = peekNext();
    if (++cursor == count) {
      roundCursor++;
      cursor = 0;
    }
    return current = candidate;
  }

  private void prepareInitialRound() {
    if (!rounds.isEmpty()) return;
    if (weights != null) {
      rounds.add(WeightedShuffle.initialRound(count, current, weights, random));
      return;
    }
    ArrayList<Integer> first = new ArrayList<>(count);
    for (int i = 0; i < count; i++) if (i != current) first.add(i);
    Collections.shuffle(first, random);
    first.add(0, current);
    rounds.add(first);
  }

  private void prepareNextRound() {
    if (roundCursor + 1 < rounds.size()) return;
    rounds.add(newRound(rounds.get(roundCursor).get(count - 1), false));
    if (rounds.size() > 3) {
      rounds.remove(0);
      roundCursor--;
    }
  }

  private void preparePreviousRound() {
    if (roundCursor > 0) return;
    rounds.add(0, newRound(rounds.get(0).get(0), true));
    roundCursor++;
    if (rounds.size() > 3) rounds.remove(rounds.size() - 1);
  }

  /** New-round weights never change a cached predecessor, successor, or already traversed path. */
  private ArrayList<Integer> newRound(int adjacent, boolean previous) {
    Random source = previous ? reverseRandom : random;
    if (weights != null) {
      ArrayList<Integer> round = WeightedShuffle.nextRound(count, adjacent, weights, source);
      // An unseen previous round is consumed from its end, so prioritize that viewing direction.
      if (previous) Collections.reverse(round);
      return round;
    }
    ArrayList<Integer> round = new ArrayList<>(count);
    for (int i = 0; i < count; i++) round.add(i);
    Collections.shuffle(round, source);
    int boundary = previous ? count - 1 : 0;
    if (round.get(boundary) == adjacent) {
      int other = source.nextInt(count - 1);
      if (!previous) other++;
      Collections.swap(round, boundary, other);
    }
    return round;
  }

  public int previous() {
    if (count == 1) return current;
    if (isShuffle()) {
      int candidate = peekPrevious();
      if (--cursor < 0) {
        roundCursor--;
        cursor = count - 1;
      }
      current = candidate;
    } else current = current == 0 ? count - 1 : current - 1;
    return current;
  }

  public boolean hasPrevious() {
    return count > 1;
  }
}
