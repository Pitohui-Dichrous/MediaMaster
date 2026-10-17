package io.mediamaster.app.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/** A shuffled deck visits every other item before any repeats, and retains real history. */
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

  public PlaybackOrder(int count, int initial, int mode) {
    this(count, initial, mode, new Random());
  }

  public PlaybackOrder(int count, int initial, int mode, Random random) {
    if (count < 1) throw new IllegalArgumentException("Queue must contain a video");
    this.count = count;
    this.random = random;
    current = Math.max(0, Math.min(count - 1, initial));
    this.mode = mode;
    history.add(current);
  }

  public int current() {
    return current;
  }

  public int mode() {
    return mode;
  }

  public void setMode(int mode) {
    this.mode = Math.max(SEQUENTIAL, Math.min(SHUFFLE, mode));
    deck.clear();
    history.clear();
    history.add(current);
    cursor = 0;
  }

  public int next(boolean automatic) {
    if (automatic && mode == SINGLE) return current;
    if (mode != SHUFFLE) {
      current = (current + 1) % count;
      return current;
    }
    if (cursor + 1 < history.size()) return current = history.get(++cursor);
    if (deck.isEmpty()) {
      for (int i = 0; i < count; i++) if (i != current) deck.add(i);
      Collections.shuffle(deck, random);
    }
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

  public int previous() {
    if (mode == SHUFFLE) {
      if (cursor > 0) current = history.get(--cursor);
    } else current = (current - 1 + count) % count;
    return current;
  }
}
