package io.mediamaster.app.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/** Manual navigation has a stable shuffle deck; portrait repeat is independent of that order. */
public final class PlaybackOrder {
  public static final int SEQUENTIAL = 0;
  public static final int SINGLE = 1;
  public static final int SHUFFLE = 2;
  private final int count;
  private final Random random;
  private final ArrayList<Integer> deck = new ArrayList<>();
  private final ArrayList<Integer> history = new ArrayList<>();
  private int cursor;
  private int current;
  private int mode;
  private int portraitNavigationMode;
  private boolean portraitMode;

  public PlaybackOrder(int count, int initial, int mode) {
    this(count, initial, mode, new Random());
  }

  public PlaybackOrder(int count, int initial, int mode, Random random) {
    if (count < 1) throw new IllegalArgumentException("Queue must contain a video");
    this.count = count;
    this.random = random;
    current = Math.max(0, Math.min(count - 1, initial));
    this.mode = mode;
    portraitNavigationMode = mode == SHUFFLE ? SHUFFLE : SEQUENTIAL;
    history.add(current);
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
    if (wasShuffle != isShuffle()) resetNavigationHistory();
  }

  /**
   * Restores a portrait choice without entering portrait or changing the full-screen preference.
   */
  public void setPortraitNavigationMode(int mode) {
    boolean wasShuffle = isShuffle();
    // SINGLE is a full-screen completion policy, not a separate portrait swipe order.
    portraitNavigationMode = mode == SHUFFLE ? SHUFFLE : SEQUENTIAL;
    if (wasShuffle != isShuffle()) resetNavigationHistory();
  }

  /** Updates the mode for the active presentation only. */
  public void setMode(int mode) {
    if (portraitMode) {
      setPortraitNavigationMode(mode);
      return;
    }
    boolean wasShuffle = isShuffle();
    this.mode = Math.max(SEQUENTIAL, Math.min(SHUFFLE, mode));
    if (wasShuffle != isShuffle()) resetNavigationHistory();
  }

  private boolean isShuffle() {
    return mode() == SHUFFLE;
  }

  private void resetNavigationHistory() {
    deck.clear();
    history.clear();
    history.add(current);
    cursor = 0;
  }

  /**
   * Returns the next manual-navigation candidate without advancing playback or watch history. A
   * shuffle deck may be prepared here, but its candidate is retained until next(false) commits it.
   * Single-repeat affects natural completion only, so it still has a manual next candidate.
   */
  public int peekNext() {
    if (!isShuffle()) return (current + 1) % count;
    if (cursor + 1 < history.size()) return history.get(cursor + 1);
    prepareShuffleDeck();
    return deck.isEmpty() ? current : deck.get(deck.size() - 1);
  }

  /**
   * Returns the previous manual-navigation candidate without changing history. At the start of
   * shuffle history this is the current video; hasPrevious() distinguishes that boundary.
   */
  public int peekPrevious() {
    if (isShuffle()) return cursor > 0 ? history.get(cursor - 1) : current;
    return (current - 1 + count) % count;
  }

  public int next(boolean automatic) {
    if (automatic && repeatsCurrent()) return current;
    if (!isShuffle()) {
      current = (current + 1) % count;
      return current;
    }
    if (cursor + 1 < history.size()) return current = history.get(++cursor);
    prepareShuffleDeck();
    if (!deck.isEmpty()) current = deck.remove(deck.size() - 1);
    history.add(current);
    cursor++;
    // Preserve useful history without letting hours of playback grow it forever.
    if (history.size() > 2048) {
      history.remove(0);
      cursor--;
    }
    return current;
  }

  private void prepareShuffleDeck() {
    if (!deck.isEmpty()) return;
    for (int i = 0; i < count; i++) if (i != current) deck.add(i);
    Collections.shuffle(deck, random);
  }

  public int previous() {
    if (isShuffle()) {
      if (cursor > 0) current = history.get(--cursor);
    } else current = (current - 1 + count) % count;
    return current;
  }

  public boolean hasPrevious() {
    return !isShuffle() || cursor > 0;
  }
}
